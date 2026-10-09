package io.github.anuragbhandary.ticketing.bookings;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.anuragbhandary.ticketing.common.ApiException;
import io.github.anuragbhandary.ticketing.events.SeatRepository;
import io.github.anuragbhandary.ticketing.holds.HoldRepository;
import io.github.anuragbhandary.ticketing.live.SeatChangePublisher;
import io.github.anuragbhandary.ticketing.payments.PaymentOrderRepository;

/** Turns a paid order into a booking, exactly once. */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    public enum Outcome { BOOKED, ALREADY_BOOKED, REFUNDED }

    private final PaymentOrderRepository orders;
    private final HoldRepository holds;
    private final SeatRepository seats;
    private final SeatChangePublisher publisher;
    private final JdbcClient jdbc;
    private final Clock clock;

    BookingService(PaymentOrderRepository orders, HoldRepository holds, SeatRepository seats,
                   SeatChangePublisher publisher, JdbcClient jdbc, Clock clock) {
        this.orders = orders;
        this.holds = holds;
        this.seats = seats;
        this.publisher = publisher;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Called when the gateway reports a captured payment. The order row is locked first, so two
     * deliveries of the same payment serialise here and the second sees PAID. The seats are
     * locked in label order and must still be this hold's, or free (a hold that lapsed but that
     * nobody else took is still honoured). If someone else has them, the payment is refunded.
     */
    @Transactional
    public Outcome confirmPayment(String orderId, String paymentId, int amountPaise) {
        var order = orders.lock(orderId).orElseThrow(() -> ApiException.notFound("order"));
        if ("PAID".equals(order.status())) return Outcome.ALREADY_BOOKED;
        if ("REFUNDED".equals(order.status())) return Outcome.REFUNDED;
        if (order.amountPaise() != amountPaise) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "amount_mismatch",
                    "Paid " + amountPaise + " but the order is for " + order.amountPaise());
        }
        var hold = holds.lock(order.holdId()).orElseThrow();
        Instant now = clock.instant();
        var locked = seats.lockForBooking(hold.eventId(), hold.seats());
        boolean ours = locked.size() == hold.seats().size()
                && locked.stream().allMatch(s -> s.claimableBy(hold.id(), now));
        if (!ours) {
            // The hold lapsed and someone else holds or bought a seat: give the money back.
            orders.settle(orderId, "REFUNDED", paymentId);
            holds.setStatus(hold.id(), "EXPIRED");
            publisher.publish(hold.eventId(), seats.release(hold.eventId(), hold.id()));
            log.info("refunding payment {} for order {}: seats no longer available", paymentId, orderId);
            return Outcome.REFUNDED;
        }
        var bookingId = UUID.randomUUID();
        var changes = seats.markBooked(hold.eventId(), hold.seats(), bookingId);
        jdbc.sql("""
                INSERT INTO bookings (id, user_id, event_id, hold_id, order_id, payment_id, seat_labels, amount_paise)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """).params(bookingId, hold.userId(), hold.eventId(), hold.id(), orderId, paymentId,
                hold.seats().toArray(String[]::new), amountPaise).update();
        orders.settle(orderId, "PAID", paymentId);
        holds.setStatus(hold.id(), "CONFIRMED");
        publisher.publish(hold.eventId(), changes);
        return Outcome.BOOKED;
    }

    /** A failed payment leaves the hold active, so the buyer can try again before it lapses. */
    @Transactional
    public void failPayment(String orderId, String paymentId) {
        var order = orders.lock(orderId).orElseThrow(() -> ApiException.notFound("order"));
        if ("CREATED".equals(order.status())) orders.settle(orderId, "FAILED", null);
        log.info("payment {} failed for order {}", paymentId, orderId);
    }

    public record BookingView(UUID id, long eventId, String eventTitle, String venue, String city, Instant startsAt,
                              List<String> seats, int amountPaise, String paymentId, Instant createdAt) {}

    public List<BookingView> forUser(long userId) {
        return jdbc.sql("""
                SELECT b.*, e.title, e.venue, e.city, e.starts_at FROM bookings b JOIN events e ON e.id = b.event_id
                WHERE b.user_id = ? ORDER BY b.created_at DESC
                """).param(userId).query((rs, i) -> new BookingView(rs.getObject("id", UUID.class),
                        rs.getLong("event_id"), rs.getString("title"), rs.getString("venue"), rs.getString("city"),
                        rs.getTimestamp("starts_at").toInstant(),
                        List.of((String[]) rs.getArray("seat_labels").getArray()), rs.getInt("amount_paise"),
                        rs.getString("payment_id"), rs.getTimestamp("created_at").toInstant()))
                .list();
    }
}
