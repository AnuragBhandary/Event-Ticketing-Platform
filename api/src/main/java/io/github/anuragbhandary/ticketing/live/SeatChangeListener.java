package io.github.anuragbhandary.ticketing.live;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * LISTENs on {@link SeatChangePublisher#CHANNEL} with one dedicated connection and hands each
 * notification to this instance's SSE clients. If the connection drops, notifications sent while
 * it was down are lost, so after reconnecting it tells every client to refetch the seat map.
 */
@Component
class SeatChangeListener implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SeatChangeListener.class);

    private final DataSource dataSource;
    private final SeatStreamHub hub;
    private volatile boolean running;
    private Thread thread;

    SeatChangeListener(DataSource dataSource, SeatStreamHub hub) {
        this.dataSource = dataSource;
        this.hub = hub;
    }

    @Override
    public void start() {
        running = true;
        thread = Thread.ofPlatform().daemon().name("seat-change-listener").start(this::loop);
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        boolean first = true;
        while (running) {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(true);
                try (var st = conn.createStatement()) {
                    st.execute("LISTEN " + SeatChangePublisher.CHANNEL);
                }
                if (!first) hub.resyncAll();
                first = false;
                var pg = conn.unwrap(PGConnection.class);
                while (running) {
                    PGNotification[] batch = pg.getNotifications(1_000);
                    if (batch == null) continue;
                    for (var n : batch) {
                        hub.broadcast(SeatChangePublisher.eventIdOf(n.getParameter()), n.getParameter());
                    }
                }
            } catch (SQLException | RuntimeException e) {
                if (!running) return;
                log.warn("seat change listener lost its connection; reconnecting", e);
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }
}
