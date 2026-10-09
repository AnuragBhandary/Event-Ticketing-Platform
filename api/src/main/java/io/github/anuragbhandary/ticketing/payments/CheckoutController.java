package io.github.anuragbhandary.ticketing.payments;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.anuragbhandary.ticketing.auth.CurrentUser;
import io.github.anuragbhandary.ticketing.common.ApiException;
import io.github.anuragbhandary.ticketing.holds.HoldRepository;

/**
 * Starts payment for a hold: creates the gateway order (once per hold, so retries and double
 * clicks get the same order) and tells the browser where to pay. In production this would call the
 * gateway's Orders API (Razorpay: POST /v1/orders); here the fake gateway shares the table.
 */
@RestController
class CheckoutController {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final HoldRepository holds;
    private final PaymentOrderRepository orders;
    private final Clock clock;

    CheckoutController(HoldRepository holds, PaymentOrderRepository orders, Clock clock) {
        this.holds = holds;
        this.orders = orders;
        this.clock = clock;
    }

    record Checkout(String orderId, int amountPaise, String status, Instant holdExpiresAt, String paymentUrl) {}

    @PostMapping("/api/holds/{holdId}/checkout")
    @Transactional
    Checkout checkout(@PathVariable UUID holdId, @AuthenticationPrincipal Jwt jwt) {
        var hold = holds.lock(holdId).filter(h -> h.userId() == CurrentUser.from(jwt).id())
                .orElseThrow(() -> ApiException.notFound("hold"));
        if (!"ACTIVE".equals(hold.status()) || !hold.expiresAt().isAfter(clock.instant())) {
            throw ApiException.conflict("hold_expired", "This hold has expired; pick your seats again");
        }
        var order = orders.createOnce("order_" + HexFormat.of().formatHex(randomBytes()), hold.id(), hold.amountPaise());
        return new Checkout(order.id(), order.amountPaise(), order.status(), hold.expiresAt(), "/pay/" + order.id());
    }

    private static byte[] randomBytes() {
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        return b;
    }
}
