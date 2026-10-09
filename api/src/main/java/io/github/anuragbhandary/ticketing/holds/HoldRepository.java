package io.github.anuragbhandary.ticketing.holds;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class HoldRepository {

    private final JdbcClient jdbc;

    HoldRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Hold(UUID id, long eventId, long userId, List<String> seats, int amountPaise, String status,
                       Instant expiresAt) {}

    public void insert(Hold h) {
        jdbc.sql("""
                INSERT INTO holds (id, event_id, user_id, seat_labels, amount_paise, status, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """).params(h.id(), h.eventId(), h.userId(), h.seats().toArray(String[]::new), h.amountPaise(),
                h.status(), Timestamp.from(h.expiresAt())).update();
    }

    public Optional<Hold> find(UUID id) {
        return jdbc.sql("SELECT * FROM holds WHERE id = ?").param(id).query(this::map).optional();
    }

    public Optional<Hold> lock(UUID id) {
        return jdbc.sql("SELECT * FROM holds WHERE id = ? FOR UPDATE").param(id).query(this::map).optional();
    }

    public Optional<Hold> activeFor(long userId, long eventId) {
        return jdbc.sql("SELECT * FROM holds WHERE user_id = ? AND event_id = ? AND status = 'ACTIVE' FOR UPDATE")
                .params(userId, eventId).query(this::map).optional();
    }

    public void setStatus(UUID id, String status) {
        jdbc.sql("UPDATE holds SET status = ? WHERE id = ?").params(status, id).update();
    }

    public int expireLapsed(Instant now) {
        return jdbc.sql("UPDATE holds SET status = 'EXPIRED' WHERE status = 'ACTIVE' AND expires_at < ?")
                .param(Timestamp.from(now)).update();
    }

    private Hold map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        var labels = (String[]) rs.getArray("seat_labels").getArray();
        return new Hold(rs.getObject("id", UUID.class), rs.getLong("event_id"), rs.getLong("user_id"),
                Arrays.asList(labels), rs.getInt("amount_paise"), rs.getString("status"),
                rs.getTimestamp("expires_at").toInstant());
    }
}
