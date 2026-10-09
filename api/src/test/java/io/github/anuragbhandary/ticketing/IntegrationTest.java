package io.github.anuragbhandary.ticketing;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestClient;

import io.github.anuragbhandary.ticketing.auth.UserAccount;
import io.github.anuragbhandary.ticketing.auth.UserRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base for integration tests: the whole app on a random port, real HTTP, PostgreSQL in a
 * container, and a clock the test controls. Tables are emptied before each test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ticketing.sweep-interval=1h", "ticketing.demo.enabled=false"})
@Import({TestcontainersConfiguration.class, IntegrationTest.ClockConfig.class})
public abstract class IntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    private static final AtomicInteger USERS = new AtomicInteger();

    @LocalServerPort protected int port;
    @Autowired protected JdbcClient jdbc;
    @Autowired protected MutableClock clock;
    @Autowired protected JsonMapper json;
    @Autowired protected UserRepository users;
    @Autowired protected PasswordEncoder passwords;

    protected RestClient http;

    @BeforeEach
    void cleanSlate() {
        jdbc.sql("TRUNCATE webhook_events, bookings, payment_orders, holds, seats, events, users RESTART IDENTITY CASCADE").update();
        clock.reset();
        http = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(s -> true, (req, res) -> {}) // tests assert on status themselves
                .build();
    }

    /** A response's status and parsed JSON body (null when empty). */
    protected record Res(int status, JsonNode body) {
        public JsonNode get(String field) {
            return body.get(field);
        }
    }

    protected Res call(HttpMethod method, String path, String token, Object body) {
        var spec = http.method(method).uri(path);
        if (token != null) spec = spec.header("Authorization", "Bearer " + token);
        if (body != null) spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        return spec.exchange((req, res) -> {
            byte[] bytes = res.getBody().readAllBytes();
            return new Res(res.getStatusCode().value(), bytes.length == 0 ? null : json.readTree(bytes));
        });
    }

    protected Res get(String path, String token) {
        return call(HttpMethod.GET, path, token, null);
    }

    protected Res post(String path, String token, Object body) {
        return call(HttpMethod.POST, path, token, body);
    }

    /** Register a fresh user and return their token. */
    protected String user() {
        var res = post("/api/auth/register", null,
                Map.of("email", "user" + USERS.incrementAndGet() + "-" + UUID.randomUUID() + "@example.com",
                        "name", "Test User", "password", "correct horse battery"));
        return res.get("token").asString();
    }

    protected String admin() {
        String email = "admin-" + UUID.randomUUID() + "@example.com";
        users.save(new UserAccount(email, "Admin", passwords.encode("admin-password"), UserAccount.Role.ADMIN));
        return post("/api/auth/login", null, Map.of("email", email, "password", "admin-password")).get("token").asString();
    }

    /** An event in 10 days, rows A..(rows) of `perRow` seats; rows A-B cost 2,000 rupees, the rest 1,000. */
    protected long event(int rows, int perRow) {
        var tiers = rows > 2
                ? List.of(Map.of("fromRow", "A", "toRow", "B", "pricePaise", 200_000),
                          Map.of("fromRow", "C", "toRow", String.valueOf((char) ('A' + rows - 1)), "pricePaise", 100_000))
                : List.of(Map.of("fromRow", "A", "toRow", String.valueOf((char) ('A' + rows - 1)), "pricePaise", 200_000));
        var res = post("/api/events", admin(), Map.of("title", "Test show", "venue", "Hall", "city", "Mumbai",
                "description", "", "startsAt", Instant.now().plus(Duration.ofDays(10)).toString(),
                "rows", rows, "seatsPerRow", perRow, "tiers", tiers));
        if (res.status() != 201) throw new AssertionError("create event: " + res);
        return res.get("id").asLong();
    }

    protected Res hold(String token, long eventId, String... seats) {
        return post("/api/holds", token, Map.of("eventId", eventId, "seats", List.of(seats)));
    }

    protected String seatStatus(long eventId, String label) {
        for (JsonNode s : get("/api/events/" + eventId + "/seats", null).body()) {
            if (s.get("label").asString().equals(label)) return s.get("status").asString();
        }
        throw new AssertionError("no seat " + label);
    }
}
