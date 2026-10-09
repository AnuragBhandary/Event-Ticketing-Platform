package io.github.anuragbhandary.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AuthAndEventsIT extends IntegrationTest {

    @Test
    void registerLoginAndMe() {
        var reg = post("/api/auth/register", null, Map.of("email", "Asha@Example.com", "name", "Asha", "password", "long enough pw"));
        assertThat(reg.status()).isEqualTo(201);
        assertThat(reg.get("user").get("email").asString()).isEqualTo("asha@example.com"); // normalised
        assertThat(reg.get("user").get("role").asString()).isEqualTo("USER");

        assertThat(post("/api/auth/register", null, Map.of("email", "asha@example.com", "name", "A", "password", "long enough pw")).status())
                .isEqualTo(409);
        assertThat(post("/api/auth/login", null, Map.of("email", "asha@example.com", "password", "wrong password")).status())
                .isEqualTo(401);
        assertThat(post("/api/auth/login", null, Map.of("email", "nobody@example.com", "password", "whatever123")).status())
                .isEqualTo(401);

        var login = post("/api/auth/login", null, Map.of("email", "ASHA@example.com", "password", "long enough pw"));
        assertThat(login.status()).isEqualTo(200);
        var me = get("/api/me", login.get("token").asString());
        assertThat(me.get("name").asString()).isEqualTo("Asha");
    }

    @Test
    void validationAndAuthorisation() {
        var bad = post("/api/auth/register", null, Map.of("email", "not-an-email", "name", "", "password", "short"));
        assertThat(bad.status()).isEqualTo(400);
        assertThat(get("/api/me", null).status()).isEqualTo(401);
        assertThat(get("/api/me", "not.a.jwt").status()).isEqualTo(401);
        assertThat(get("/api/bookings", null).status()).isEqualTo(401);

        var createAsUser = post("/api/events", user(), Map.of("title", "x"));
        assertThat(createAsUser.status()).isEqualTo(403); // only admins create events
    }

    @Test
    void createListAndSeatMap() {
        long id = event(4, 5);
        var seats = get("/api/events/" + id + "/seats", null);
        assertThat(seats.body().size()).isEqualTo(20);
        var first = seats.body().get(0);
        assertThat(first.get("label").asString()).isEqualTo("A1");
        assertThat(first.get("pricePaise").asInt()).isEqualTo(200_000);
        assertThat(seats.body().get(19).get("label").asString()).isEqualTo("D5");
        assertThat(seats.body().get(19).get("pricePaise").asInt()).isEqualTo(100_000);

        var list = get("/api/events", null);
        assertThat(list.body().size()).isEqualTo(1);
        var summary = list.body().get(0);
        assertThat(summary.get("available").asInt()).isEqualTo(20);
        assertThat(summary.get("minPricePaise").asInt()).isEqualTo(100_000);
        assertThat(summary.get("maxPricePaise").asInt()).isEqualTo(200_000);

        assertThat(get("/api/events/999", null).status()).isEqualTo(404);
        assertThat(get("/api/events/999/seats", null).status()).isEqualTo(404);
    }

    @Test
    void priceTiersMustCoverEveryRowOnce() {
        String admin = admin();
        var starts = Instant.now().plus(Duration.ofDays(3)).toString();
        Map<String, Object> base = Map.of("title", "T", "venue", "V", "city", "C", "startsAt", starts, "rows", 3, "seatsPerRow", 2);
        var gap = new java.util.HashMap<>(base);
        gap.put("tiers", List.of(Map.of("fromRow", "A", "toRow", "B", "pricePaise", 1000)));
        var res = post("/api/events", admin, gap);
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.get("detail").asString()).isEqualTo("Row C has no price");
        assertThat(res.get("code").asString()).isEqualTo("bad_tiers");

        var overlap = new java.util.HashMap<>(base);
        overlap.put("tiers", List.of(Map.of("fromRow", "A", "toRow", "C", "pricePaise", 1000),
                Map.of("fromRow", "C", "toRow", "C", "pricePaise", 2000)));
        assertThat(post("/api/events", admin, overlap).get("detail").asString()).isEqualTo("Row C is priced twice");

        var outside = new java.util.HashMap<>(base);
        outside.put("tiers", List.of(Map.of("fromRow", "A", "toRow", "D", "pricePaise", 1000)));
        assertThat(post("/api/events", admin, outside).get("detail").asString()).contains("outside rows A-C");

        var past = new java.util.HashMap<>(base);
        past.put("startsAt", Instant.now().minusSeconds(60).toString());
        past.put("tiers", List.of(Map.of("fromRow", "A", "toRow", "C", "pricePaise", 1000)));
        assertThat(post("/api/events", admin, past).status()).isEqualTo(400);
    }
}
