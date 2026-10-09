package io.github.anuragbhandary.ticketing.events;

import java.time.Clock;
import java.util.List;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.github.anuragbhandary.ticketing.auth.CurrentUser;
import io.github.anuragbhandary.ticketing.common.ApiException;
import io.github.anuragbhandary.ticketing.live.SeatStreamHub;

@RestController
@RequestMapping("/api/events")
class EventController {

    private final EventService service;
    private final SeatRepository seats;
    private final SeatStreamHub hub;
    private final Clock clock;

    EventController(EventService service, SeatRepository seats, SeatStreamHub hub, Clock clock) {
        this.service = service;
        this.seats = seats;
        this.hub = hub;
        this.clock = clock;
    }

    @GetMapping
    List<EventService.EventSummary> upcoming() {
        return service.upcoming();
    }

    @GetMapping("/{id}")
    EventService.EventSummary get(@PathVariable long id) {
        return service.get(id);
    }

    @GetMapping("/{id}/seats")
    List<SeatRepository.Seat> seatMap(@PathVariable long id) {
        if (!service.exists(id)) throw ApiException.notFound("event");
        return seats.seatMap(id, clock.instant());
    }

    /** Live seat changes (Server-Sent Events). Clients fetch the seat map, then apply these. */
    @GetMapping(path = "/{id}/seats/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@PathVariable long id, HttpServletResponse response) {
        if (!service.exists(id)) throw ApiException.notFound("event");
        response.setHeader("X-Accel-Buffering", "no"); // nginx: pass events through unbuffered
        response.setHeader("Cache-Control", "no-cache");
        return hub.subscribe(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    EventService.EventSummary create(@Valid @RequestBody EventService.CreateEvent body, @AuthenticationPrincipal Jwt jwt) {
        return service.create(body, CurrentUser.from(jwt).id());
    }
}
