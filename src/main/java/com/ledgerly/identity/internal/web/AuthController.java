package com.ledgerly.identity.internal.web;

import com.ledgerly.identity.internal.OwnTokenIssuing;
import com.ledgerly.identity.internal.application.Login;
import com.ledgerly.identity.internal.application.Logout;
import com.ledgerly.identity.internal.application.Refresh;
import com.ledgerly.identity.internal.application.Register;
import com.ledgerly.identity.internal.application.Session;
import com.ledgerly.identity.internal.application.VerifyEmail;
import com.ledgerly.shared.api.AuthApi;
import com.ledgerly.shared.api.model.Accepted;
import com.ledgerly.shared.api.model.LoginRequest;
import com.ledgerly.shared.api.model.RegisterRequest;
import com.ledgerly.shared.api.model.TokenResponse;
import com.ledgerly.shared.api.model.VerifyRequest;
import com.ledgerly.shared.error.InvalidRequest;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Register, verify, login, refresh and logout under /api/v1/auth. The refresh token travels in an HttpOnly cookie; refresh and logout also need the X-Requested-With header (ADR 0004).
 */
@OwnTokenIssuing
@RestController
@RequestMapping("/api/v1")
class AuthController implements AuthApi {

    static final String COOKIE = "refresh_token";
    static final String COOKIE_PATH = "/api/v1/auth";

    private final Register register;
    private final VerifyEmail verify;
    private final Login login;
    private final Refresh refresh;
    private final Logout logout;

    AuthController(Register register, VerifyEmail verify, Login login, Refresh refresh, Logout logout) {
        this.register = register;
        this.verify = verify;
        this.login = login;
        this.refresh = refresh;
        this.logout = logout;
    }

    /** 202 with the same body whether or not the address was already registered. */
    @Override
    public ResponseEntity<Accepted> register(RegisterRequest request) {
        register.execute(request.getEmail(), request.getPassword());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new Accepted("If this address can be registered, a verification message has been sent."));
    }

    /** 204 when the token was valid. */
    @Override
    public ResponseEntity<Void> verifyEmail(VerifyRequest request) {
        verify.execute(request.getToken());
        return ResponseEntity.noContent().build();
    }

    /** Access token in the body, refresh token in the cookie. */
    @Override
    public ResponseEntity<TokenResponse> login(LoginRequest request) {
        return tokens(login.execute(request.getEmail(), request.getPassword()));
    }

    /** Rotates the cookie and returns a new access token. */
    @Override
    public ResponseEntity<TokenResponse> refresh(String xRequestedWith, @Nullable String refreshToken) {
        requireCustomHeader(xRequestedWith);
        return tokens(refresh.execute(refreshToken));
    }

    /** 204 and an expired cookie, whether or not a session existed. */
    @Override
    public ResponseEntity<Void> logout(String xRequestedWith, @Nullable String refreshToken) {
        requireCustomHeader(xRequestedWith);
        logout.execute(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
                .build();
    }

    /**
     * The CSRF defence of ADR 0004. Checked here because the generated code only checks that the header is
     * present, not its value.
     */
    private static void requireCustomHeader(String value) {
        if (!"ledgerly".equals(value)) {
            throw new InvalidRequest("X-Requested-With", "must be 'ledgerly'");
        }
    }

    private static ResponseEntity<TokenResponse> tokens(Session session) {
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        cookie(session.refreshToken(), session.refreshTtl()).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new TokenResponse(
                        session.accessToken(), TokenResponse.TokenTypeEnum.BEARER, (int) session.expiresInSeconds()));
    }

    /** HttpOnly (no script access), Secure, SameSite=Strict, and only sent to the auth endpoints. */
    private static ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }
}
