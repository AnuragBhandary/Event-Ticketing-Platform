package io.github.anuragbhandary.ticketing.events;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import io.github.anuragbhandary.ticketing.live.SeatChange;

/**
 * All seat-state SQL. The hot paths (hold, book, expire) are single conditional statements over
 * locked rows, written with JdbcClient rather than JPA so the locking is explicit and reviewable.
 * A HELD seat whose hold has lapsed counts as available everywhere: expiry needs no background
 * job to be correct; the sweeper only tidies up and announces it.
 */
@Repository
public class SeatRepository {

    private final JdbcClient jdbc;

    SeatRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Seat(String label, String row, int number, int pricePaise, String status, long version) {}

    public record SeatLock(String label, String status, UUID holdId, Instant holdExpiresAt, int pricePaise) {
        public boolean claimableBy(UUID hold, Instant now) {
            return "AVAILABLE".equals(status)
                    || ("HELD".equals(status) && (hold.equals(holdId) || holdExpiresAt.isBefore(now)));
        }

        public boolean freeAt(Instant now) {
            return "AVAILABLE".equals(status) || ("HELD".equals(status) && holdExpiresAt.isBefore(now));
        }
    }

    /** Insert all of an event's seats in one statement. */
    public void createSeats(long eventId, List<Seat> seats) {
        jdbc.sql("""
                INSERT INTO seats (event_id, label, row_label, number, price_paise, status)
                SELECT ?, * , 'AVAILABLE' FROM unnest(?::text[], ?::text[], ?::int[], ?::int[])
                """).params(eventId,
                        seats.stream().map(Seat::label).toArray(String[]::new),
                        seats.stream().map(Seat::row).toArray(String[]::new),
                        seats.stream().map(Seat::number).toArray(Integer[]::new),
                        seats.stream().map(Seat::pricePaise).toArray(Integer[]::new))
                .update();
    }

    /** The seat map as buyers see it: lapsed holds show as available. */
    public List<Seat> seatMap(long eventId, Instant now) {
        return jdbc.sql("""
                SELECT label, row_label, number, price_paise, version,
                       CASE WHEN status = 'HELD' AND hold_expires_at < ? THEN 'AVAILABLE' ELSE status END AS status
                FROM seats WHERE event_id = ? ORDER BY row_label, number
                """).params(Timestamp.from(now), eventId)
                .query((rs, i) -> new Seat(rs.getString("label"), rs.getString("row_label"), rs.getInt("number"),
                        rs.getInt("price_paise"), rs.getString("status"), rs.getLong("version")))
                .list();
    }

    /**
     * Lock the requested seats for a hold without waiting: SKIP LOCKED leaves out any row another
     * transaction is changing right now, which the caller treats as "taken". Two buyers racing for
     * one seat get an immediate yes and an immediate no, and no lock order can deadlock.
     */
    public List<SeatLock> lockForHold(long eventId, List<String> labels) {
        return jdbc.sql("""
                SELECT label, status, hold_id, hold_expires_at, price_paise FROM seats
                WHERE event_id = ? AND label = ANY (?)
                FOR UPDATE SKIP LOCKED
                """).params(eventId, labels.toArray(String[]::new)).query(this::lock).list();
    }

    /** Lock seats to book them, waiting for any in-flight change, in label order (no deadlocks). */
    public List<SeatLock> lockForBooking(long eventId, List<String> labels) {
        return jdbc.sql("""
                SELECT label, status, hold_id, hold_expires_at, price_paise FROM seats
                WHERE event_id = ? AND label = ANY (?)
                ORDER BY label FOR UPDATE
                """).params(eventId, labels.toArray(String[]::new)).query(this::lock).list();
    }

    public List<SeatChange> markHeld(long eventId, List<String> labels, UUID holdId, Instant expiresAt) {
        return change("""
                UPDATE seats SET status = 'HELD', hold_id = ?, hold_expires_at = ?, booking_id = NULL,
                                 version = version + 1
                WHERE event_id = ? AND label = ANY (?)
                RETURNING label, status, version
                """, holdId, Timestamp.from(expiresAt), eventId, labels.toArray(String[]::new));
    }

    public List<SeatChange> markBooked(long eventId, List<String> labels, UUID bookingId) {
        return change("""
                UPDATE seats SET status = 'BOOKED', booking_id = ?, hold_id = NULL, hold_expires_at = NULL,
                                 version = version + 1
                WHERE event_id = ? AND label = ANY (?)
                RETURNING label, status, version
                """, bookingId, eventId, labels.toArray(String[]::new));
    }

    /** Release seats still held by this hold (a cancelled hold). */
    public List<SeatChange> release(long eventId, UUID holdId) {
        return change("""
                UPDATE seats SET status = 'AVAILABLE', hold_id = NULL, hold_expires_at = NULL, version = version + 1
                WHERE event_id = ? AND hold_id = ? AND status = 'HELD'
                RETURNING label, status, version
                """, eventId, holdId);
    }

    public record Expired(long eventId, SeatChange change) {}

    /** Free up to {@code limit} lapsed holds, skipping rows someone is changing right now. */
    public List<Expired> releaseExpired(Instant now, int limit) {
        return jdbc.sql("""
                UPDATE seats s SET status = 'AVAILABLE', hold_id = NULL, hold_expires_at = NULL, version = s.version + 1
                FROM (SELECT event_id, label FROM seats
                      WHERE status = 'HELD' AND hold_expires_at < ?
                      ORDER BY hold_expires_at LIMIT ? FOR UPDATE SKIP LOCKED) due
                WHERE s.event_id = due.event_id AND s.label = due.label
                RETURNING s.event_id, s.label, s.status, s.version
                """).params(Timestamp.from(now), limit)
                .query((rs, i) -> new Expired(rs.getLong("event_id"),
                        new SeatChange(rs.getString("label"), rs.getString("status"), rs.getLong("version"))))
                .list();
    }

    private List<SeatChange> change(String sql, Object... params) {
        return new ArrayList<>(jdbc.sql(sql).params(params)
                .query((rs, i) -> new SeatChange(rs.getString("label"), rs.getString("status"), rs.getLong("version")))
                .list());
    }

    private SeatLock lock(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        var expires = rs.getTimestamp("hold_expires_at");
        return new SeatLock(rs.getString("label"), rs.getString("status"), rs.getObject("hold_id", UUID.class),
                expires == null ? null : expires.toInstant(), rs.getInt("price_paise"));
    }
}
