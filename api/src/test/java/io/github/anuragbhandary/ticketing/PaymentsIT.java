package io.github.anuragbhandary.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import io.github.anuragbhandary.ticketing.payments.WebhookSignature;

class PaymentsIT extends IntegrationTest {

    private Res checkout(String token, String holdId) {
        return post("/api/holds/" + holdId + "/checkout", token, null);
    }

    private Res pay(String orderId, String outcome, int deliveries) {
        return post("/api/fake-gateway/orders/" + orderId + "/pay", null, Map.of("outcome", outcome, "deliveries", deliveries));
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    @Test
    void payingTurnsTheHoldIntoABooking() {
        long ev = event(2, 5);
        String me = user();
        String holdId = hold(me, ev, "A1", "A2").get("id").asString();
        var order = checkout(me, holdId);
        assertThat(order.status()).isEqualTo(200);
        assertThat(order.get("amountPaise").asInt()).isEqualTo(400_000);
        assertThat(checkout(me, holdId).get("orderId")).isEqualTo(order.get("orderId")); // idempotent per hold
        assertThat(checkout(user(), holdId).status()).isEqualTo(404); // not your hold

        var gatewayView = get("/api/fake-gateway/orders/" + order.get("orderId").asString(), null);
        assertThat(gatewayView.get("amountPaise").asInt()).isEqualTo(400_000);

        var paid = pay(order.get("orderId").asString(), "success", 1);
        assertThat(paid.get("deliveries").toString()).contains("booked");

        var holdView = get("/api/holds/" + holdId, me);
        assertThat(holdView.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(holdView.get("orderStatus").asString()).isEqualTo("PAID");
        assertThat(holdView.get("bookingId").isNull()).isFalse();
        assertThat(seatStatus(ev, "A1")).isEqualTo("BOOKED");

        var bookings = get("/api/bookings", me);
        assertThat(bookings.body().size()).isEqualTo(1);
        assertThat(bookings.body().get(0).get("seats").toString()).isEqualTo("[\"A1\",\"A2\"]");
        assertThat(bookings.body().get(0).get("eventTitle").asString()).isEqualTo("Test show");
        assertThat(checkout(me, holdId).get("code").asString()).isEqualTo("hold_expired"); // already used
    }

    @Test
    void duplicateWebhooksDeliveredAtOnceBookExactlyOnce() {
        long ev = event(2, 5);
        String me = user();
        String holdId = hold(me, ev, "B3").get("id").asString();
        var res = pay(checkout(me, holdId).get("orderId").asString(), "success", 5);
        var results = res.get("deliveries").toString();
        assertThat(results.split("booked", -1).length - 1).isEqualTo(1);
        assertThat(results.split("duplicate", -1).length - 1).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM bookings")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM webhook_events")).isEqualTo(1);
    }

    @Test
    void aFailedPaymentKeepsTheHoldSoYouCanRetry() {
        long ev = event(2, 5);
        String me = user();
        String holdId = hold(me, ev, "A3").get("id").asString();
        String orderId = checkout(me, holdId).get("orderId").asString();
        assertThat(pay(orderId, "failure", 1).get("deliveries").toString()).contains("failed");
        var view = get("/api/holds/" + holdId, me);
        assertThat(view.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(view.get("orderStatus").asString()).isEqualTo("FAILED");
        assertThat(seatStatus(ev, "A3")).isEqualTo("HELD");

        pay(orderId, "success", 1);
        assertThat(get("/api/holds/" + holdId, me).get("orderStatus").asString()).isEqualTo("PAID");
    }

    @Test
    void lateButUncontestedPaymentStillBooks() {
        long ev = event(2, 5);
        String me = user();
        String holdId = hold(me, ev, "A4").get("id").asString();
        String orderId = checkout(me, holdId).get("orderId").asString();
        clock.advance(Duration.ofMinutes(15)); // the hold lapsed while the buyer was paying
        assertThat(checkout(me, holdId).get("code").asString()).isEqualTo("hold_expired");
        pay(orderId, "success", 1);
        assertThat(seatStatus(ev, "A4")).isEqualTo("BOOKED");
    }

    @Test
    void latePaymentForASeatSomeoneElseTookIsRefunded() {
        long ev = event(2, 5);
        String me = user();
        String holdId = hold(me, ev, "A5").get("id").asString();
        String orderId = checkout(me, holdId).get("orderId").asString();
        clock.advance(Duration.ofMinutes(15));
        assertThat(hold(user(), ev, "A5").status()).isEqualTo(201); // taken by someone else meanwhile
        assertThat(pay(orderId, "success", 1).get("deliveries").toString()).contains("refunded");
        var view = get("/api/holds/" + holdId, me);
        assertThat(view.get("orderStatus").asString()).isEqualTo("REFUNDED");
        assertThat(view.get("status").asString()).isEqualTo("EXPIRED");
        assertThat(count("SELECT count(*) FROM bookings")).isZero();
        assertThat(seatStatus(ev, "A5")).isEqualTo("HELD"); // still the other buyer's
    }

    @Test
    void webhooksMustBeSignedAndMatchTheOrder() {
        long ev = event(2, 5);
        String me = user();
        String orderId = checkout(me, hold(me, ev, "B1").get("id").asString()).get("orderId").asString();
        String body = "{\"event\":\"payment.captured\",\"orderId\":\"" + orderId + "\",\"paymentId\":\"pay_x\",\"amountPaise\":1}";

        var unsigned = webhook(body, "evt_1", "deadbeef");
        assertThat(unsigned.status()).isEqualTo(401);
        String sig = WebhookSignature.sign("local-dev-webhook-secret", body);
        assertThat(webhook(body, null, sig).status()).isEqualTo(400);
        var wrongAmount = webhook(body, "evt_2", sig);
        assertThat(wrongAmount.status()).isEqualTo(422);
        assertThat(wrongAmount.get("code").asString()).isEqualTo("amount_mismatch");
        assertThat(count("SELECT count(*) FROM webhook_events")).isZero(); // rolled back with the failure

        String other = "{\"event\":\"refund.processed\",\"orderId\":\"" + orderId + "\",\"paymentId\":\"p\",\"amountPaise\":1}";
        assertThat(webhook(other, "evt_3", WebhookSignature.sign("local-dev-webhook-secret", other)).get("result").asString())
                .isEqualTo("ignored");
    }

    private Res webhook(String body, String eventId, String signature) {
        var spec = http.method(HttpMethod.POST).uri("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON)
                .header("X-Gateway-Signature", signature);
        if (eventId != null) spec = spec.header("X-Gateway-Event-Id", eventId);
        return spec.body(body).exchange((req, res) -> {
            byte[] bytes = res.getBody().readAllBytes();
            return new Res(res.getStatusCode().value(), bytes.length == 0 ? null : json.readTree(bytes));
        });
    }
}
