package io.github.anuragbhandary.ticketing.payments;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import io.github.anuragbhandary.ticketing.common.ApiException;
import io.github.anuragbhandary.ticketing.config.AppProperties;

/**
 * A stand-in payment gateway for local development and tests, playing Razorpay's part: it shows
 * an order on its checkout page and, when the buyer "pays", delivers a signed webhook to the API
 * over HTTP, like the real one would. {@code deliveries > 1} sends the same webhook several times
 * at once, to exercise duplicate handling. Disabled with ticketing.payments.fake-gateway.enabled.
 */
@RestController
@RequestMapping("/api/fake-gateway")
@ConditionalOnProperty("ticketing.payments.fake-gateway.enabled")
class FakeGatewayController {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PaymentOrderRepository orders;
    private final AppProperties props;
    private final RestClient http;
    private final Environment env;

    FakeGatewayController(PaymentOrderRepository orders, AppProperties props, Environment env) {
        this.orders = orders;
        this.props = props;
        this.http = RestClient.create();
        this.env = env;
    }

    /** The configured URL, or this instance's own webhook endpoint (port known only once started). */
    private String webhookUrl() {
        String url = props.payments().fakeGateway().webhookUrl();
        if (url != null && !url.isBlank()) return url;
        return "http://localhost:" + env.getProperty("local.server.port", "8080") + "/api/payments/webhook";
    }

    record OrderInfo(String orderId, int amountPaise, String status, String merchant) {}

    record PayRequest(@Pattern(regexp = "success|failure") String outcome, @Min(1) @Max(5) Integer deliveries) {}

    record PayResult(String paymentId, String outcome, List<String> deliveries) {}

    @GetMapping("/orders/{orderId}")
    OrderInfo order(@PathVariable String orderId) {
        var o = orders.find(orderId).orElseThrow(() -> ApiException.notFound("order"));
        return new OrderInfo(o.id(), o.amountPaise(), o.status(), "livefeed tickets");
    }

    @PostMapping("/orders/{orderId}/pay")
    PayResult pay(@PathVariable String orderId, @Valid @RequestBody PayRequest req) throws Exception {
        var order = orders.find(orderId).orElseThrow(() -> ApiException.notFound("order"));
        String paymentId = "pay_" + hex(8);
        String event = "success".equals(req.outcome()) ? "payment.captured" : "payment.failed";
        String body = "{\"event\":\"" + event + "\",\"orderId\":\"" + order.id() + "\",\"paymentId\":\""
                + paymentId + "\",\"amountPaise\":" + order.amountPaise() + "}";
        String eventId = "evt_" + hex(8);
        String signature = WebhookSignature.sign(props.payments().webhookSecret(), body);

        int n = req.deliveries() == null ? 1 : req.deliveries();
        var results = new ArrayList<String>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var sent = new ArrayList<Future<String>>();
            for (int i = 0; i < n; i++) sent.add(pool.submit(() -> deliver(body, eventId, signature)));
            for (var f : sent) results.add(f.get());
        }
        return new PayResult(paymentId, req.outcome(), results);
    }

    private String deliver(String body, String eventId, String signature) {
        try {
            return http.post().uri(webhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(WebhookController.EVENT_ID_HEADER, eventId)
                    .header(WebhookController.SIGNATURE_HEADER, signature)
                    .body(body).retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            return "HTTP " + e.getStatusCode().value();
        }
    }

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
