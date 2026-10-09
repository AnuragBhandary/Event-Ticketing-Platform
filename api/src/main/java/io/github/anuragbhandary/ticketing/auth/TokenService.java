package io.github.anuragbhandary.ticketing.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import io.github.anuragbhandary.ticketing.config.AppProperties;

/** Issues the access tokens the API itself validates (subject = user id). */
@Service
public class TokenService {

    private final JwtEncoder encoder;
    private final AppProperties props;
    private final Clock clock;

    TokenService(JwtEncoder encoder, AppProperties props, Clock clock) {
        this.encoder = encoder;
        this.props = props;
        this.clock = clock;
    }

    public record IssuedToken(String token, Instant expiresAt) {}

    public IssuedToken issue(UserAccount user) {
        Instant now = clock.instant();
        Instant expires = now.plus(props.jwt().ttl());
        var claims = JwtClaimsSet.builder()
                .issuer("ticketing")
                .subject(String.valueOf(user.getId()))
                .issuedAt(now)
                .expiresAt(expires)
                .claim("email", user.getEmail())
                .claim("name", user.getName())
                .claim("roles", List.of(user.getRole().name()))
                .build();
        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expires);
    }
}
