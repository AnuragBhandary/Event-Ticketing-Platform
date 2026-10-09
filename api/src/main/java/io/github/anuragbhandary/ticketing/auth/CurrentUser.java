package io.github.anuragbhandary.ticketing.auth;

import org.springframework.security.oauth2.jwt.Jwt;

/** The caller, read from a validated JWT. */
public record CurrentUser(long id, String email, boolean admin) {

    public static CurrentUser from(Jwt jwt) {
        var roles = jwt.getClaimAsStringList("roles");
        return new CurrentUser(Long.parseLong(jwt.getSubject()), jwt.getClaimAsString("email"),
                roles != null && roles.contains("ADMIN"));
    }
}
