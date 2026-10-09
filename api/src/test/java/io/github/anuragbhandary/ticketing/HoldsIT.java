package io.github.anuragbhandary.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import io.github.anuragbhandary.ticketing.holds.HoldService;

class HoldsIT extends IntegrationTest {

    @Autowired HoldService holds;

    @Test
    void holdsSeatsAndPricesThem() {
        long ev = event(4, 5);
        var res = hold(user(), ev, "C2", "A1");
        assertThat(res.status()).isEqualTo(201);
        assertThat(res.get("seats").toString()).isEqualTo("[\"A1\",\"C2\"]"); // sorted
        assertThat(res.get("amountPaise").asInt()).isEqualTo(300_000);
        assertThat(res.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(seatStatus(ev, "A1")).isEqualTo("HELD");
        assertThat(get("/api/events/" + ev, null).get("held").asInt()).isEqualTo(2);
    }

    @Test
    void allOrNothingAndTellsYouWhichSeatsWereTaken() {
        long ev = event(2, 5);
        assertThat(hold(user(), ev, "A2").status()).isEqualTo(201);
        var res = hold(user(), ev, "A1", "A2", "A3");
        assertThat(res.status()).isEqualTo(409);
        assertThat(res.get("code").asString()).isEqualTo("seats_unavailable");
        assertThat(res.get("taken").toString()).isEqualTo("[\"A2\"]");
        assertThat(seatStatus(ev, "A1")).isEqualTo("AVAILABLE"); // nothing partially held
        assertThat(seatStatus(ev, "A3")).isEqualTo("AVAILABLE");
    }

    @Test
    void pickingAgainReplacesYourPreviousHold() {
        long ev = event(2, 5);
        String me = user();
        String first = hold(me, ev, "A1", "A2").get("id").asString();
        assertThat(hold(me, ev, "A2", "A3").status()).isEqualTo(201); // A2 again is fine: it was mine
        assertThat(seatStatus(ev, "A1")).isEqualTo("AVAILABLE");
        assertThat(get("/api/holds/" + first, me).get("status").asString()).isEqualTo("RELEASED");
    }

    @Test
    void cancelReleasesAndOnlyTheOwnerCanSeeOrCancel() {
        long ev = event(2, 5);
        String me = user();
        String id = hold(me, ev, "B5").get("id").asString();
        String other = user();
        assertThat(get("/api/holds/" + id, other).status()).isEqualTo(404);
        assertThat(call(HttpMethod.DELETE, "/api/holds/" + id, other, null).status()).isEqualTo(404);
        assertThat(call(HttpMethod.DELETE, "/api/holds/" + id, me, null).status()).isEqualTo(204);
        assertThat(seatStatus(ev, "B5")).isEqualTo("AVAILABLE");
        assertThat(call(HttpMethod.DELETE, "/api/holds/" + id, me, null).status()).isEqualTo(409);
    }

    @Test
    void aLapsedHoldIsFreeAtOnceAndTheSweeperTidiesUp() {
        long ev = event(2, 5);
        String me = user();
        String id = hold(me, ev, "A1").get("id").asString();
        clock.advance(Duration.ofMinutes(11)); // TTL is 10 minutes
        assertThat(seatStatus(ev, "A1")).isEqualTo("AVAILABLE"); // before any sweep
        assertThat(hold(user(), ev, "A1").status()).isEqualTo(201); // someone else can take it

        String mine2 = hold(me, ev, "B1").get("id").asString();
        clock.advance(Duration.ofMinutes(11));
        holds.sweepExpired();
        Long heldRows = jdbc.sql("SELECT count(*) FROM seats WHERE status = 'HELD'").query(Long.class).single();
        assertThat(heldRows).isZero();
        assertThat(get("/api/holds/" + mine2, me).get("status").asString()).isEqualTo("EXPIRED");
        assertThat(get("/api/holds/" + id, me).get("status").asString()).isEqualTo("RELEASED"); // replaced by B1
    }

    @Test
    void rejectsBadSeatRequests() {
        long ev = event(2, 5);
        String me = user();
        assertThat(hold(me, ev, "C1").get("detail").asString()).isEqualTo("No seat C1 at this event");
        assertThat(hold(me, ev, "A6").status()).isEqualTo(400);
        assertThat(hold(me, ev, "a1").status()).isEqualTo(400);
        assertThat(hold(me, ev, "A1", "A1").get("detail").asString()).isEqualTo("Seat A1 is listed twice");
        assertThat(hold(me, ev).status()).isEqualTo(400);
        assertThat(hold(me, ev, "A1", "A2", "A3", "A4", "A5", "B1", "B2", "B3", "B4", "B5", "A1").status()).isEqualTo(400);
        assertThat(hold(me, 12345, "A1").status()).isEqualTo(404);
        assertThat(post("/api/holds", me, Map.of("seats", List.of("A1"))).status()).isEqualTo(400);
    }

    @Test
    void cannotHoldOnceTheEventHasStarted() {
        long ev = event(2, 5);
        clock.advance(Duration.ofDays(11));
        assertThat(hold(user(), ev, "A1").get("code").asString()).isEqualTo("event_started");
    }
}
