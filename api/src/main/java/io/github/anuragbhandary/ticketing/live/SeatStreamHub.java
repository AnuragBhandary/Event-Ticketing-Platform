package io.github.anuragbhandary.ticketing.live;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.github.anuragbhandary.ticketing.config.AppProperties;

/**
 * This instance's open Server-Sent Events connections, grouped by event.
 *
 * <p>It is a lifecycle bean in the default (last) phase, so on shutdown it stops before the web
 * server's graceful shutdown begins. Open streams are long-running requests: if they were left
 * open, graceful shutdown would wait out its whole timeout on every deploy. Closing them makes
 * browsers reconnect, through the load balancer, to an instance that is still up.
 */
@Component
public class SeatStreamHub implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SeatStreamHub.class);
    private static final long TIMEOUT_MS = 30 * 60 * 1000L; // the browser's EventSource reconnects

    private final Map<Long, Set<Client>> byEvent = new ConcurrentHashMap<>();
    private final String instanceId;
    private volatile boolean running;

    SeatStreamHub(AppProperties props) {
        this.instanceId = props.instanceId();
    }

    /** SseEmitter is not safe for concurrent sends (heartbeat vs broadcast), hence the lock. */
    private final class Client {
        final long eventId;
        final SseEmitter emitter = new SseEmitter(TIMEOUT_MS);

        Client(long eventId) {
            this.eventId = eventId;
        }

        synchronized boolean send(SseEmitter.SseEventBuilder event) {
            try {
                emitter.send(event);
                return true;
            } catch (IOException | IllegalStateException e) {
                remove(this);
                return false;
            }
        }
    }

    public SseEmitter subscribe(long eventId) {
        var client = new Client(eventId);
        byEvent.computeIfAbsent(eventId, k -> ConcurrentHashMap.newKeySet()).add(client);
        client.emitter.onCompletion(() -> remove(client));
        client.emitter.onTimeout(() -> remove(client));
        client.emitter.onError(e -> remove(client));
        client.send(SseEmitter.event().name("hello").data("{\"instance\":\"" + instanceId + "\"}"));
        return client.emitter;
    }

    /** Seat changes for one event, as JSON (see {@link SeatChangePublisher#toJson}). */
    public void broadcast(long eventId, String json) {
        var clients = byEvent.get(eventId);
        if (clients == null) return;
        var event = SseEmitter.event().name("seats").data(json);
        clients.forEach(c -> c.send(event));
    }

    /** Notifications may have been missed (the listener reconnected): tell everyone to refetch. */
    public void resyncAll() {
        log.info("asking {} live clients to resync", connections());
        byEvent.values().forEach(set -> set.forEach(c -> c.send(SseEmitter.event().name("resync").data("{}"))));
    }

    /** A comment line every 15 s keeps idle connections open through proxies and load balancers. */
    @Scheduled(fixedRate = 15_000)
    void heartbeat() {
        byEvent.values().forEach(set -> set.forEach(c -> c.send(SseEmitter.event().comment("hb"))));
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        int closed = connections();
        byEvent.values().forEach(set -> set.forEach(c -> c.emitter.complete()));
        byEvent.clear();
        if (closed > 0) log.info("closed {} live streams for shutdown", closed);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public int connections() {
        return byEvent.values().stream().mapToInt(Set::size).sum();
    }

    private void remove(Client client) {
        byEvent.computeIfPresent(client.eventId, (k, set) -> {
            set.remove(client);
            return set.isEmpty() ? null : set;
        });
    }
}
