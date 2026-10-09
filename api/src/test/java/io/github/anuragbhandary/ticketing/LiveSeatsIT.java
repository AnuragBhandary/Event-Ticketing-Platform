package io.github.anuragbhandary.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/** Seat changes reach Server-Sent Events subscribers through PostgreSQL LISTEN/NOTIFY. */
class LiveSeatsIT extends IntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    io.github.anuragbhandary.ticketing.live.SeatStreamHub hub;

    private static final String END = "<end of stream>";

    private BlockingQueue<String> subscribe(long eventId) {
        var lines = new LinkedBlockingQueue<String>();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/events/" + eventId + "/seats/stream"))
                .header("Accept", "text/event-stream").build();
        HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .thenAccept(res -> {
                    res.body().forEach(lines::add);
                    lines.add(END);
                });
        return lines;
    }

    private String next(BlockingQueue<String> lines, String prefix) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            String line = lines.poll(100, TimeUnit.MILLISECONDS);
            if (line != null && line.startsWith(prefix)) return line;
        }
        throw new AssertionError("no line starting with " + prefix);
    }

    @Test
    void subscribersSeeHoldsAndBookingsAsTheyCommit() throws Exception {
        long ev = event(2, 5);
        var lines = subscribe(ev);
        assertThat(next(lines, "event:")).isEqualTo("event:hello");

        String me = user();
        String holdId = hold(me, ev, "A1", "A2").get("id").asString();
        assertThat(next(lines, "event:")).isEqualTo("event:seats");
        String held = next(lines, "data:");
        assertThat(held).contains("\"eventId\":" + ev).contains("{\"label\":\"A1\",\"status\":\"HELD\",\"version\":1}")
                .contains("\"A2\"");

        String orderId = post("/api/holds/" + holdId + "/checkout", me, null).get("orderId").asString();
        post("/api/fake-gateway/orders/" + orderId + "/pay", null, java.util.Map.of("outcome", "success"));
        next(lines, "event:seats");
        assertThat(next(lines, "data:")).contains("{\"label\":\"A1\",\"status\":\"BOOKED\",\"version\":2}");
    }

    @Test
    void aRejectedHoldAnnouncesNothing() throws Exception {
        long ev = event(2, 5);
        hold(user(), ev, "B1");
        var lines = subscribe(ev);
        next(lines, "event:hello");
        assertThat(hold(user(), ev, "B1", "B2").status()).isEqualTo(409); // rolled back
        hold(user(), ev, "B5"); // a later, successful hold
        next(lines, "event:seats");
        String data = next(lines, "data:");
        assertThat(data).contains("B5").doesNotContain("B2"); // B2 was never announced
    }

    @Test
    void shutdownClosesOpenStreamsSoGracefulShutdownDoesNotWait() throws Exception {
        long ev = event(1, 3);
        var lines = subscribe(ev);
        next(lines, "event:hello");
        hub.stop();
        try {
            assertThat(next(lines, END)).isEqualTo(END); // the server ended the response
            assertThat(hub.connections()).isZero();
        } finally {
            hub.start();
        }
    }

    @Test
    void unknownEventHasNoStream() {
        assertThat(get("/api/events/4242/seats/stream", null).status()).isEqualTo(404);
    }
}
