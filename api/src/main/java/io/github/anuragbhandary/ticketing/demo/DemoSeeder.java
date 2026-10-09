package io.github.anuragbhandary.ticketing.demo;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import io.github.anuragbhandary.ticketing.auth.UserAccount;
import io.github.anuragbhandary.ticketing.auth.UserRepository;
import io.github.anuragbhandary.ticketing.config.AppProperties;
import io.github.anuragbhandary.ticketing.events.EventRepository;
import io.github.anuragbhandary.ticketing.events.EventService;
import io.github.anuragbhandary.ticketing.events.EventService.PriceTier;

/** On an empty database (local runs and docker compose), create an admin and a few events. */
@Component
@ConditionalOnProperty("ticketing.demo.enabled")
public class DemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    private final UserRepository users;
    private final EventRepository events;
    private final EventService eventService;
    private final PasswordEncoder passwords;
    private final AppProperties props;
    private final Clock clock;

    DemoSeeder(UserRepository users, EventRepository events, EventService eventService, PasswordEncoder passwords,
             AppProperties props, Clock clock) {
        this.users = users;
        this.events = events;
        this.eventService = eventService;
        this.passwords = passwords;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        UserAccount admin;
        try {
            admin = users.findByEmail(props.demo().adminEmail()).orElseGet(() -> users.saveAndFlush(new UserAccount(
                    props.demo().adminEmail(), "Admin", passwords.encode(props.demo().adminPassword()),
                    UserAccount.Role.ADMIN)));
        } catch (DataIntegrityViolationException raced) { // another instance created it first
            admin = users.findByEmail(props.demo().adminEmail()).orElseThrow();
        }
        if (events.count() > 0) return;
        var now = clock.instant();
        var tiers = List.of(new PriceTier("A", "C", 250_000), new PriceTier("D", "H", 150_000), new PriceTier("I", "L", 80_000));
        eventService.create(new EventService.CreateEvent("Arijit Singh Live", "NSCI Dome", "Mumbai",
                "An evening of hits, live with a full band.", now.plus(Duration.ofDays(20)), 12, 20, tiers), admin.getId());
        eventService.create(new EventService.CreateEvent("Stand-up Night: Zakir Khan", "Shanmukhananda Hall", "Mumbai",
                "New material, recorded live.", now.plus(Duration.ofDays(9)), 8, 16,
                List.of(new PriceTier("A", "B", 199_900), new PriceTier("C", "H", 99_900))), admin.getId());
        eventService.create(new EventService.CreateEvent("IPL Screening: MI vs CSK", "PVR Phoenix", "Mumbai",
                "The big match on the big screen.", now.plus(Duration.ofDays(4)), 10, 14,
                List.of(new PriceTier("A", "J", 49_900))), admin.getId());
        log.info("seeded demo admin {} and 3 events", admin.getEmail());
    }
}
