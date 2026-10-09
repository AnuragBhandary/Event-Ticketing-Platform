package io.github.anuragbhandary.ticketing.bookings;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.anuragbhandary.ticketing.auth.CurrentUser;

@RestController
class BookingController {

    private final BookingService service;

    BookingController(BookingService service) {
        this.service = service;
    }

    @GetMapping("/api/bookings")
    List<BookingService.BookingView> mine(@AuthenticationPrincipal Jwt jwt) {
        return service.forUser(CurrentUser.from(jwt).id());
    }
}
