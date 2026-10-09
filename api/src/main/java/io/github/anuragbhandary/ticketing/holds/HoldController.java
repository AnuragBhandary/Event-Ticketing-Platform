package io.github.anuragbhandary.ticketing.holds;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.github.anuragbhandary.ticketing.auth.CurrentUser;
import io.github.anuragbhandary.ticketing.payments.PaymentOrderRepository;

@RestController
@RequestMapping("/api/holds")
class HoldController {

    private final HoldService service;
    private final PaymentOrderRepository orders;

    HoldController(HoldService service, PaymentOrderRepository orders) {
        this.service = service;
        this.orders = orders;
    }

    record HoldRequest(@NotNull Long eventId, @NotNull List<String> seats) {}

    /** The hold, plus its payment order and booking once they exist. */
    record HoldView(UUID id, long eventId, List<String> seats, int amountPaise, String status, Instant expiresAt,
                    String orderId, String orderStatus, UUID bookingId) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    HoldView create(@RequestBody @jakarta.validation.Valid HoldRequest body, @AuthenticationPrincipal Jwt jwt) {
        return view(service.hold(CurrentUser.from(jwt).id(), body.eventId(), body.seats()));
    }

    @GetMapping("/{id}")
    HoldView get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return view(service.get(CurrentUser.from(jwt).id(), id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        service.cancel(CurrentUser.from(jwt).id(), id);
    }

    private HoldView view(HoldRepository.Hold h) {
        var order = orders.findByHold(h.id());
        return new HoldView(h.id(), h.eventId(), h.seats(), h.amountPaise(), h.status(), h.expiresAt(),
                order.map(PaymentOrderRepository.Order::id).orElse(null),
                order.map(PaymentOrderRepository.Order::status).orElse(null),
                order.map(PaymentOrderRepository.Order::bookingId).orElse(null));
    }
}
