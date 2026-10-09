package io.github.anuragbhandary.ticketing.config;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Everything under {@code ticketing.*} in application.yml, validated at startup. */
@Validated
@ConfigurationProperties("ticketing")
public record AppProperties(
        @NotBlank String instanceId,
        Duration holdTtl,
        @Min(1) int maxSeatsPerHold,
        Jwt jwt,
        Payments payments,
        Demo demo) {

    public record Jwt(@Size(min = 32, message = "must be at least 32 bytes") String secret, Duration ttl) {}

    public record Payments(@NotBlank String webhookSecret, FakeGateway fakeGateway) {}

    public record FakeGateway(boolean enabled, String webhookUrl) {}

    public record Demo(boolean enabled, String adminEmail, String adminPassword) {}
}
