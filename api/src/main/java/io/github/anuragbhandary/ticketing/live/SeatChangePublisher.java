package io.github.anuragbhandary.ticketing.live;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Announces seat changes with PostgreSQL NOTIFY from inside the transaction that made them.
 * NOTIFY is transactional: listeners hear it only if and when that transaction commits, never for
 * a rolled-back hold. Every API instance LISTENs (see {@link SeatChangeListener}), so a browser
 * connected to any instance sees changes made on any other.
 */
@Component
public class SeatChangePublisher {

    public static final String CHANNEL = "seat_changes";
    private static final int SEATS_PER_MESSAGE = 100; // NOTIFY payloads are limited to 8,000 bytes

    private final JdbcClient jdbc;

    SeatChangePublisher(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void publish(long eventId, List<SeatChange> changes) {
        for (int i = 0; i < changes.size(); i += SEATS_PER_MESSAGE) {
            var chunk = changes.subList(i, Math.min(changes.size(), i + SEATS_PER_MESSAGE));
            jdbc.sql("SELECT pg_notify(?, ?)").params(CHANNEL, toJson(eventId, chunk)).query().singleRow();
        }
    }

    /** {"eventId":1,"seats":[{"label":"A1","status":"HELD","version":3}]}. Labels are [A-Z][0-9]+. */
    static String toJson(long eventId, List<SeatChange> seats) {
        var sb = new StringBuilder(32 + seats.size() * 48).append("{\"eventId\":").append(eventId).append(",\"seats\":[");
        for (int i = 0; i < seats.size(); i++) {
            var s = seats.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"label\":\"").append(s.label()).append("\",\"status\":\"").append(s.status())
                    .append("\",\"version\":").append(s.version()).append('}');
        }
        return sb.append("]}").toString();
    }

    /** The event id at the start of a payload, without parsing the rest. */
    static long eventIdOf(String json) {
        int start = json.indexOf(':') + 1;
        return Long.parseLong(json.substring(start, json.indexOf(',', start)));
    }
}
