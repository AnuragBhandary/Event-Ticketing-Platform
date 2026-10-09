package io.github.anuragbhandary.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

/** The core promise: however many buyers race, a seat is never held or booked twice. */
class ConcurrencyIT extends IntegrationTest {

    @Test
    void fiftyBuyersRaceForOneSeatAndExactlyOneWins() throws Exception {
        long ev = event(1, 10);
        var tokens = new ArrayList<String>();
        for (int i = 0; i < 50; i++) tokens.add(user());
        var statuses = new ConcurrentLinkedQueue<Integer>();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String t : tokens) {
                pool.submit(() -> {
                    start.await();
                    statuses.add(hold(t, ev, "A5").status());
                    return null;
                });
            }
            start.countDown();
        }
        assertThat(statuses).hasSize(50);
        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(49);
    }

    @Test
    void overlappingMultiSeatHoldsNeverShareASeat() throws Exception {
        long ev = event(2, 10); // 20 seats
        var all = new ArrayList<String>();
        for (char r : new char[] {'A', 'B'}) for (int n = 1; n <= 10; n++) all.add(r + "" + n);
        var tokens = new ArrayList<String>();
        for (int i = 0; i < 80; i++) tokens.add(user());
        var won = new ConcurrentLinkedQueue<List<String>>();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < tokens.size(); i++) {
                var rnd = new Random(i);
                var pick = new HashSet<String>();
                while (pick.size() < 3) pick.add(all.get(rnd.nextInt(all.size())));
                String t = tokens.get(i);
                pool.submit(() -> {
                    start.await();
                    var res = hold(t, ev, pick.toArray(String[]::new));
                    if (res.status() == 201) won.add(List.copyOf(pick));
                    return null;
                });
            }
            start.countDown();
        }
        var seen = new HashSet<String>();
        for (var seats : won) for (var s : seats) assertThat(seen.add(s)).as("seat %s held twice", s).isTrue();
        Long held = jdbc.sql("SELECT count(*) FROM seats WHERE status = 'HELD'").query(Long.class).single();
        assertThat(held).isEqualTo(seen.size()); // the database agrees with the winners, seat for seat
        assertThat(won).isNotEmpty();
    }
}
