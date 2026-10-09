package io.github.anuragbhandary.ticketing.events;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.anuragbhandary.ticketing.common.ApiException;

@Service
public class EventService {

    private final EventRepository events;
    private final SeatRepository seats;
    private final JdbcClient jdbc;
    private final Clock clock;

    EventService(EventRepository events, SeatRepository seats, JdbcClient jdbc, Clock clock) {
        this.events = events;
        this.seats = seats;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Rows {@code fromRow..toRow} (inclusive, letters) cost {@code pricePaise} each. */
    public record PriceTier(@Pattern(regexp = "[A-Z]") String fromRow, @Pattern(regexp = "[A-Z]") String toRow,
                            @Min(100) @Max(10_000_000) int pricePaise) {}

    public record CreateEvent(@NotBlank @Size(max = 120) String title, @NotBlank @Size(max = 120) String venue,
                              @NotBlank @Size(max = 60) String city, @Size(max = 2000) String description,
                              @NotNull @Future Instant startsAt, @Min(1) @Max(26) int rows,
                              @Min(1) @Max(40) int seatsPerRow, @NotEmpty List<@Valid PriceTier> tiers) {}

    public record EventSummary(long id, String title, String venue, String city, String description,
                               Instant startsAt, int rows, int seatsPerRow, int available, int held, int booked,
                               int minPricePaise, int maxPricePaise) {}

    @Transactional
    public EventSummary create(CreateEvent req, long adminId) {
        int[] priceByRow = pricesByRow(req.rows(), req.tiers());
        var event = events.saveAndFlush(new Event(req.title().trim(), req.venue().trim(), req.city().trim(),
                req.description() == null ? "" : req.description().trim(), req.startsAt(), req.rows(),
                req.seatsPerRow(), adminId));
        var list = new ArrayList<SeatRepository.Seat>(req.rows() * req.seatsPerRow());
        for (int r = 0; r < req.rows(); r++) {
            String row = String.valueOf((char) ('A' + r));
            for (int n = 1; n <= req.seatsPerRow(); n++) {
                list.add(new SeatRepository.Seat(row + n, row, n, priceByRow[r], "AVAILABLE", 0));
            }
        }
        seats.createSeats(event.getId(), list);
        return get(event.getId());
    }

    /** Every row must be priced by exactly one tier. */
    static int[] pricesByRow(int rows, List<PriceTier> tiers) {
        int[] prices = new int[rows];
        for (var t : tiers) {
            int from = t.fromRow().charAt(0) - 'A';
            int to = t.toRow().charAt(0) - 'A';
            if (from > to || to >= rows) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "bad_tiers",
                        "Tier " + t.fromRow() + "-" + t.toRow() + " is outside rows A-" + (char) ('A' + rows - 1));
            }
            for (int r = from; r <= to; r++) {
                if (prices[r] != 0) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "bad_tiers", "Row " + (char) ('A' + r) + " is priced twice");
                }
                prices[r] = t.pricePaise();
            }
        }
        for (int r = 0; r < rows; r++) {
            if (prices[r] == 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "bad_tiers", "Row " + (char) ('A' + r) + " has no price");
            }
        }
        return prices;
    }

    private static final String SUMMARY_SQL = """
            SELECT e.id, e.title, e.venue, e.city, e.description, e.starts_at, e.seat_rows, e.seats_per_row,
                   count(*) FILTER (WHERE s.status = 'AVAILABLE' OR (s.status = 'HELD' AND s.hold_expires_at < :now)) AS available,
                   count(*) FILTER (WHERE s.status = 'HELD' AND s.hold_expires_at >= :now) AS held,
                   count(*) FILTER (WHERE s.status = 'BOOKED') AS booked,
                   min(s.price_paise) AS min_price, max(s.price_paise) AS max_price
            FROM events e JOIN seats s ON s.event_id = e.id
            """;

    public List<EventSummary> upcoming() {
        var now = java.sql.Timestamp.from(clock.instant());
        return jdbc.sql(SUMMARY_SQL + " WHERE e.starts_at > :now GROUP BY e.id ORDER BY e.starts_at, e.id")
                .param("now", now).query(this::summary).list();
    }

    public EventSummary get(long id) {
        var now = java.sql.Timestamp.from(clock.instant());
        return jdbc.sql(SUMMARY_SQL + " WHERE e.id = :id GROUP BY e.id")
                .param("now", now).param("id", id).query(this::summary).optional()
                .orElseThrow(() -> ApiException.notFound("event"));
    }

    public boolean exists(long id) {
        return events.existsById(id);
    }

    private EventSummary summary(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new EventSummary(rs.getLong("id"), rs.getString("title"), rs.getString("venue"), rs.getString("city"),
                rs.getString("description"), rs.getTimestamp("starts_at").toInstant(), rs.getInt("seat_rows"),
                rs.getInt("seats_per_row"), rs.getInt("available"), rs.getInt("held"), rs.getInt("booked"),
                rs.getInt("min_price"), rs.getInt("max_price"));
    }
}
