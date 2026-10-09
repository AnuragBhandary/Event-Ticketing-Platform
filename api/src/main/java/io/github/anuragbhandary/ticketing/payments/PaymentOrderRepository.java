package io.github.anuragbhandary.ticketing.payments;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentOrderRepository {

    private final JdbcClient jdbc;

    PaymentOrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Order(String id, UUID holdId, int amountPaise, String status, String paymentId, UUID bookingId) {}

    private static final String SELECT = """
            SELECT o.id, o.hold_id, o.amount_paise, o.status, o.payment_id, b.id AS booking_id
            FROM payment_orders o LEFT JOIN bookings b ON b.order_id = o.id
            """;

    /** Create the hold's order unless it already has one; return whichever exists. */
    public Order createOnce(String id, UUID holdId, int amountPaise) {
        jdbc.sql("""
                INSERT INTO payment_orders (id, hold_id, amount_paise, status) VALUES (?, ?, ?, 'CREATED')
                ON CONFLICT (hold_id) DO NOTHING
                """).params(id, holdId, amountPaise).update();
        return findByHold(holdId).orElseThrow();
    }

    public Optional<Order> find(String id) {
        return jdbc.sql(SELECT + " WHERE o.id = ?").param(id).query(this::map).optional();
    }

    public Optional<Order> findByHold(UUID holdId) {
        return jdbc.sql(SELECT + " WHERE o.hold_id = ?").param(holdId).query(this::map).optional();
    }

    public Optional<Order> lock(String id) {
        return jdbc.sql("SELECT id, hold_id, amount_paise, status, payment_id, NULL::uuid AS booking_id "
                + "FROM payment_orders WHERE id = ? FOR UPDATE").param(id).query(this::map).optional();
    }

    public void settle(String id, String status, String paymentId) {
        jdbc.sql("UPDATE payment_orders SET status = ?, payment_id = coalesce(?, payment_id), updated_at = now() WHERE id = ?")
                .params(status, paymentId, id).update();
    }

    private Order map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Order(rs.getString("id"), rs.getObject("hold_id", UUID.class), rs.getInt("amount_paise"),
                rs.getString("status"), rs.getString("payment_id"), rs.getObject("booking_id", UUID.class));
    }
}
