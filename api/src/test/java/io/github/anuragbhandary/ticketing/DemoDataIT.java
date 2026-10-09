package io.github.anuragbhandary.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

import io.github.anuragbhandary.ticketing.demo.DemoSeeder;

/** The demo seed docker compose relies on: an admin who can log in, and three upcoming events. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ticketing.demo.enabled=true", "ticketing.demo.admin-password=demo-admin-pw"})
@Import(TestcontainersConfiguration.class)
class DemoDataIT {

    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @org.springframework.beans.factory.annotation.Autowired DemoSeeder seeder;

    @Test
    void seedsAnAdminAndEventsOnce() throws Exception {
        var http = RestClient.create("http://localhost:" + port);
        var events = http.get().uri("/api/events").retrieve().body(Map[].class);
        assertThat(events).hasSize(3);
        assertThat(events[0].get("title")).isEqualTo("IPL Screening: MI vs CSK"); // soonest first

        var login = http.post().uri("/api/auth/login").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of("email", "admin@example.com", "password", "demo-admin-pw")).retrieve().body(Map.class);
        assertThat(((Map<?, ?>) login.get("user")).get("role")).isEqualTo("ADMIN");

        seeder.run(null); // a restart (or a second instance) must not seed again
        assertThat(http.get().uri("/api/events").retrieve().body(Map[].class)).hasSize(3);
    }
}
