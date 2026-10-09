package io.github.anuragbhandary.ticketing.payments;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import io.github.anuragbhandary.ticketing.bookings.BookingService;
import io.github.anuragbhandary.ticketing.common.ApiException;
import io.github.anuragbhandary.ticketing.config.AppProperties;
import tools.jackson.databind.json.JsonMapper;

/**
 * Payment gateway webhooks. The body is verified against its HMAC signature before it is parsed.
 * Deliveries are at least once (a gateway retries until it gets a 2xx, and may send concurrently),
 * so the event id is recorded in the same transaction as its effects: a duplicate finds the id
 * already there and does nothing; a crash part way rolls both back and the retry does the work.
 */
@RestController
class WebhookController {

    static final String SIGNATURE_HEADER = "X-Gateway-Signature";
    static final String EVENT_ID_HEADER = "X-Gateway-Event-Id";

    private final AppProperties props;
    private final JsonMapper json;
    private final JdbcClient jdbc;
    private final BookingService bookings;

    WebhookController(AppProperties props, JsonMapper json, JdbcClient jdbc, BookingService bookings) {
        this.props = props;
        this.json = json;
        this.jdbc = jdbc;
        this.bookings = bookings;
    }

    record Payload(String event, String orderId, String paymentId, int amountPaise) {}

    @PostMapping("/api/payments/webhook")
    @Transactional
    Map<String, String> receive(@RequestBody String body,
                                @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
                                @RequestHeader(value = EVENT_ID_HEADER, required = false) String eventId) {
        if (!WebhookSignature.valid(props.payments().webhookSecret(), body, signature)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "bad_signature", "Webhook signature does not match");
        }
        if (eventId == null || eventId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "missing_event_id", "Missing " + EVENT_ID_HEADER);
        }
        var payload = json.readValue(body, Payload.class);
        // A concurrent duplicate waits here for the first delivery's transaction, then inserts nothing.
        int fresh = jdbc.sql("INSERT INTO webhook_events (id, type) VALUES (?, ?) ON CONFLICT DO NOTHING")
                .params(eventId, payload.event()).update();
        if (fresh == 0) return Map.of("result", "duplicate");
        return switch (payload.event()) {
            case "payment.captured" -> Map.of("result",
                    bookings.confirmPayment(payload.orderId(), payload.paymentId(), payload.amountPaise()).name().toLowerCase());
            case "payment.failed" -> {
                bookings.failPayment(payload.orderId(), payload.paymentId());
                yield Map.of("result", "failed");
            }
            default -> Map.of("result", "ignored");
        };
    }
}
