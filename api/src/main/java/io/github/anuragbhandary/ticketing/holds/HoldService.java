package io.github.anuragbhandary.ticketing.holds;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.anuragbhandary.ticketing.common.ApiException;
import io.github.anuragbhandary.ticketing.config.AppProperties;
import io.github.anuragbhandary.ticketing.events.Event;
import io.github.anuragbhandary.ticketing.events.EventRepository;
import io.github.anuragbhandary.ticketing.events.SeatRepository;
import io.github.anuragbhandary.ticketing.live.SeatChange;
import io.github.anuragbhandary.ticketing.live.SeatChangePublisher;

/**
 * Holding seats while a buyer pays. A hold is all or nothing: either every requested seat is
 * held for this buyer, or none is and the response lists which ones were taken.
 */
@Service
public class HoldService {

    private static final Pattern LABEL = Pattern.compile("([A-Z])([1-9][0-9]?)");

    private final HoldRepository holds;
    private final SeatRepository seats;
    private final EventRepository events;
    private final SeatChangePublisher publisher;
    private final AppProperties props;
    private final Clock clock;

    HoldService(HoldRepository holds, SeatRepository seats, EventRepository events, SeatChangePublisher publisher,
                AppProperties props, Clock clock) {
        this.holds = holds;
        this.seats = seats;
        this.events = events;
        this.publisher = publisher;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public HoldRepository.Hold hold(long userId, long eventId, List<String> requested) {
        Instant now = clock.instant();
        var event = events.findById(eventId).orElseThrow(() -> ApiException.notFound("event"));
        if (!event.getStartsAt().isAfter(now)) {
            throw ApiException.conflict("event_started", "This event has already started");
        }
        List<String> labels = validate(event, requested);

        // One active hold per buyer per event: picking seats again replaces the previous hold.
        holds.activeFor(userId, eventId).ifPresent(previous -> release(previous, "RELEASED"));

        var locked = seats.lockForHold(eventId, labels);
        var free = locked.stream().filter(s -> s.freeAt(now)).map(SeatRepository.SeatLock::label)
                .collect(Collectors.toSet());
        var taken = labels.stream().filter(l -> !free.contains(l)).sorted().toList();
        if (!taken.isEmpty()) {
            // Locked-but-busy and skipped (being changed by someone else right now) both count.
            throw new ApiException(HttpStatus.CONFLICT, "seats_unavailable",
                    "Some of these seats were just taken: " + String.join(", ", taken), Map.of("taken", taken));
        }
        int amount = locked.stream().mapToInt(SeatRepository.SeatLock::pricePaise).sum();
        var hold = new HoldRepository.Hold(UUID.randomUUID(), eventId, userId, labels, amount, "ACTIVE",
                now.plus(props.holdTtl()));
        holds.insert(hold);
        publisher.publish(eventId, seats.markHeld(eventId, labels, hold.id(), hold.expiresAt()));
        return hold;
    }

    @Transactional
    public void cancel(long userId, UUID holdId) {
        var hold = holds.lock(holdId).filter(h -> h.userId() == userId)
                .orElseThrow(() -> ApiException.notFound("hold"));
        if (!"ACTIVE".equals(hold.status())) {
            throw ApiException.conflict("hold_not_active", "This hold is already " + hold.status().toLowerCase());
        }
        release(hold, "RELEASED");
    }

    public HoldRepository.Hold get(long userId, UUID holdId) {
        return holds.find(holdId).filter(h -> h.userId() == userId).orElseThrow(() -> ApiException.notFound("hold"));
    }

    private void release(HoldRepository.Hold hold, String status) {
        holds.setStatus(hold.id(), status);
        publisher.publish(hold.eventId(), seats.release(hold.eventId(), hold.id()));
    }

    /**
     * Tidy up lapsed holds and announce the freed seats. Correctness does not depend on this
     * (lapsed holds already count as free), so it can run late, twice, or on several instances.
     */
    @Scheduled(fixedDelayString = "${ticketing.sweep-interval:2s}")
    @Transactional
    public void sweepExpired() {
        Instant now = clock.instant();
        var expired = seats.releaseExpired(now, 1_000);
        expired.stream().collect(Collectors.groupingBy(SeatRepository.Expired::eventId,
                        Collectors.mapping(SeatRepository.Expired::change, Collectors.toList())))
                .forEach((eventId, changes) -> publisher.publish(eventId, new ArrayList<SeatChange>(changes)));
        holds.expireLapsed(now);
    }

    private List<String> validate(Event event, List<String> requested) {
        if (requested == null || requested.isEmpty() || requested.size() > props.maxSeatsPerHold()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_seats",
                    "Pick between 1 and " + props.maxSeatsPerHold() + " seats");
        }
        var unique = new HashSet<String>();
        for (String label : requested) {
            var m = LABEL.matcher(label == null ? "" : label);
            if (!m.matches() || m.group(1).charAt(0) - 'A' >= event.getSeatRows()
                    || Integer.parseInt(m.group(2)) > event.getSeatsPerRow()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "bad_seats", "No seat " + label + " at this event");
            }
            if (!unique.add(label)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "bad_seats", "Seat " + label + " is listed twice");
            }
        }
        return requested.stream().sorted().toList();
    }
}
