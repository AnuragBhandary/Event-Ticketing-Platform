package io.github.anuragbhandary.ticketing.auth;

import java.time.Instant;
import java.util.Locale;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.github.anuragbhandary.ticketing.common.ApiException;

@RestController
@RequestMapping("/api")
class AuthController {

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    // Checked against when the email is unknown, so a login costs one bcrypt comparison either way
    // and response time does not reveal which emails have accounts.
    private final String dummyHash;

    AuthController(UserRepository users, PasswordEncoder passwords, TokenService tokens) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.dummyHash = passwords.encode("not-a-real-password");
    }

    record RegisterRequest(@NotBlank @Email String email, @NotBlank @Size(max = 80) String name,
                           @NotBlank @Size(min = 8, max = 72) String password) {}

    record LoginRequest(@NotBlank String email, @NotBlank String password) {}

    record UserView(long id, String email, String name, String role) {
        static UserView of(UserAccount u) {
            return new UserView(u.getId(), u.getEmail(), u.getName(), u.getRole().name());
        }
    }

    record Session(String token, Instant expiresAt, UserView user) {}

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Session register(@Valid @RequestBody RegisterRequest body) {
        String email = body.email().trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("email_taken", "An account with this email already exists");
        }
        var user = users.save(new UserAccount(email, body.name().trim(),
                passwords.encode(body.password()), UserAccount.Role.USER));
        var issued = tokens.issue(user);
        return new Session(issued.token(), issued.expiresAt(), UserView.of(user));
    }

    @PostMapping("/auth/login")
    Session login(@Valid @RequestBody LoginRequest body) {
        var user = users.findByEmail(body.email().trim().toLowerCase(Locale.ROOT));
        String hash = user.map(UserAccount::getPasswordHash).orElse(dummyHash);
        if (!passwords.matches(body.password(), hash) || user.isEmpty()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "bad_credentials", "Email or password is wrong");
        }
        var issued = tokens.issue(user.get());
        return new Session(issued.token(), issued.expiresAt(), UserView.of(user.get()));
    }

    @GetMapping("/me")
    UserView me(@AuthenticationPrincipal Jwt jwt) {
        return users.findById(CurrentUser.from(jwt).id()).map(UserView::of)
                .orElseThrow(() -> ApiException.notFound("user"));
    }
}
