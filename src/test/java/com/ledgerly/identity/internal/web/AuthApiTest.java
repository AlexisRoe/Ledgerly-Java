package com.ledgerly.identity.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerly.MutableClock;
import com.ledgerly.TestcontainersConfiguration;
import com.ledgerly.identity.internal.application.AccessTokenIssuer;
import com.ledgerly.identity.internal.application.CapturedVerificationTokens;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class AuthApiTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String PASSWORD = "S3cret-Passw0rd-xyz";

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    CapturedVerificationTokens mailbox;

    @Autowired
    AccessTokenIssuer issuer;

    RestTestClient client;

    @BeforeEach
    void setUp() {
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    record Resp(int status, HttpHeaders headers, JsonNode body) {
        String cookieValue() {
            String header = headers.getFirst(HttpHeaders.SET_COOKIE);
            assertThat(header).isNotNull();
            return header.substring(header.indexOf('=') + 1, header.indexOf(';'));
        }

        String accessToken() {
            return body.get("accessToken").asString();
        }
    }

    Resp send(HttpMethod method, String path, String body, String... headerPairs) {
        var spec = client.method(method).uri(path).headers(h -> {
            for (int i = 0; i < headerPairs.length; i += 2) h.add(headerPairs[i], headerPairs[i + 1]);
            if (body != null) h.add("Content-Type", "application/json");
        });
        var result = (body == null ? spec : spec.body(body)).exchange().returnResult(String.class);
        String text = result.getResponseBody();
        return new Resp(
                result.getStatus().value(),
                result.getResponseHeaders(),
                text == null || text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text));
    }

    static String credentials(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    static String newEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    Resp register(String email) {
        return send(HttpMethod.POST, "/api/v1/auth/register", credentials(email, PASSWORD));
    }

    UUID userId(String email) {
        return jdbc.sql("select id from users where email = :e")
                .param("e", email.toLowerCase())
                .query(UUID.class)
                .single();
    }

    void verify(String email) {
        String token = mailbox.forUser(userId(email));
        assertThat(send(HttpMethod.POST, "/api/v1/auth/verify", "{\"token\":\"" + token + "\"}")
                        .status())
                .isEqualTo(204);
    }

    Resp login(String email, String password) {
        return send(HttpMethod.POST, "/api/v1/auth/login", credentials(email, password));
    }

    Resp refresh(String cookie) {
        return send(
                HttpMethod.POST,
                "/api/v1/auth/refresh",
                null,
                "X-Requested-With",
                "ledgerly",
                "Cookie",
                "refresh_token=" + cookie);
    }

    String registeredEmail() {
        String email = newEmail();
        assertThat(register(email).status()).isEqualTo(202);
        return email;
    }

    Resp loggedIn() {
        String email = registeredEmail();
        verify(email);
        Resp r = login(email, PASSWORD);
        assertThat(r.status()).isEqualTo(200);
        return r;
    }

    // --- registration (API2, API6) ----------------------------------------------------------

    @Test
    void registerAnswersIdenticallyForNewAndTakenEmails() {
        String email = newEmail();
        Resp first = register(email);
        Resp second = register(email);
        Resp upperCase = register(email.toUpperCase());
        assertThat(first.status()).isEqualTo(202);
        assertThat(second.status()).isEqualTo(202);
        assertThat(upperCase.status()).isEqualTo(202);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(upperCase.body()).isEqualTo(first.body());
        assertThat(jdbc.sql("select count(*) from users where email = :e")
                        .param("e", email)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void passwordIsStoredAsAnArgon2idHash() {
        String email = registeredEmail();
        String hash = jdbc.sql("select password_hash from users where email = :e")
                .param("e", email)
                .query(String.class)
                .single();
        assertThat(hash).startsWith("$argon2id$").doesNotContain(PASSWORD);
    }

    @Test
    void weakOrOversizedInputIsRejected() {
        assertThat(send(HttpMethod.POST, "/api/v1/auth/register", credentials(newEmail(), "short"))
                        .status())
                .isEqualTo(400);
        assertThat(send(HttpMethod.POST, "/api/v1/auth/register", credentials("not-an-email", PASSWORD))
                        .status())
                .isEqualTo(400);
        assertThat(send(HttpMethod.POST, "/api/v1/auth/register", credentials(newEmail(), "x".repeat(129)))
                        .status())
                .isEqualTo(400);
    }

    // --- verification ----------------------------------------------------------------------

    @Test
    void verificationTokenWorksOnceAndMarksTheUserVerified() {
        String email = registeredEmail();
        String token = mailbox.forUser(userId(email));
        String body = "{\"token\":\"" + token + "\"}";
        assertThat(send(HttpMethod.POST, "/api/v1/auth/verify", body).status()).isEqualTo(204);
        assertThat(jdbc.sql("select verified from users where email = :e")
                        .param("e", email)
                        .query(Boolean.class)
                        .single())
                .isTrue();
        Resp again = send(HttpMethod.POST, "/api/v1/auth/verify", body);
        assertThat(again.status()).isEqualTo(400);
    }

    @Test
    void verificationTokenIsStoredOnlyAsAHash() {
        String email = registeredEmail();
        String token = mailbox.forUser(userId(email));
        String stored = jdbc.sql("select token_hash from email_verifications where user_id = :u")
                .param("u", userId(email))
                .query(String.class)
                .single();
        assertThat(stored).isNotEqualTo(token).hasSize(64);
    }

    @Test
    void verificationTokenExpiresAfter24Hours() {
        String email = registeredEmail();
        String token = mailbox.forUser(userId(email));
        clock.advance(Duration.ofHours(25));
        assertThat(send(HttpMethod.POST, "/api/v1/auth/verify", "{\"token\":\"" + token + "\"}")
                        .status())
                .isEqualTo(400);
    }

    @Test
    void guessedVerificationTokensAreRejected() {
        assertThat(send(HttpMethod.POST, "/api/v1/auth/verify", "{\"token\":\"" + "a".repeat(43) + "\"}")
                        .status())
                .isEqualTo(400);
    }

    // --- login (API2) ---------------------------------------------------------------------

    @Test
    void loginReturnsAccessTokenAndHardenedRefreshCookie() {
        Resp r = loggedIn();
        assertThat(r.body().get("tokenType").asString()).isEqualTo("Bearer");
        assertThat(r.body().get("expiresIn").asInt()).isEqualTo(600);
        String cookie = r.headers().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(cookie)
                .startsWith("refresh_token=")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Strict")
                .contains("Path=/api/v1/auth");
        assertThat(r.body().toString()).doesNotContain(r.cookieValue());
        assertThat(r.headers().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @Test
    void unverifiedUsersMayLogIn() {
        String email = registeredEmail();
        assertThat(login(email, PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void unknownEmailAndWrongPasswordLookTheSame() {
        String email = registeredEmail();
        Resp wrongPassword = login(email, "wrong-password-123");
        Resp unknownEmail = login(newEmail(), "wrong-password-123");
        assertThat(wrongPassword.status()).isEqualTo(401);
        assertThat(unknownEmail.status()).isEqualTo(401);
        assertThat(unknownEmail.body().get("code"))
                .isEqualTo(wrongPassword.body().get("code"));
        assertThat(unknownEmail.body().get("detail"))
                .isEqualTo(wrongPassword.body().get("detail"));
        assertThat(unknownEmail.body().get("title"))
                .isEqualTo(wrongPassword.body().get("title"));
        assertThat(wrongPassword.headers().containsHeader(HttpHeaders.SET_COOKIE))
                .isFalse();
    }

    @Test
    void accountLocksAfterFiveFailuresEvenForTheRightPassword() {
        String email = registeredEmail();
        for (int i = 0; i < 5; i++) {
            assertThat(login(email, "wrong-password-123").status()).isEqualTo(401);
        }
        Resp locked = login(email, PASSWORD);
        assertThat(locked.status()).isEqualTo(401);
        assertThat(locked.body().get("detail").asString()).doesNotContainIgnoringCase("lock");
    }

    @Test
    void lockExpiresAfterFifteenMinutes() {
        String email = registeredEmail();
        for (int i = 0; i < 5; i++) login(email, "wrong-password-123");
        assertThat(login(email, PASSWORD).status()).isEqualTo(401);
        clock.advance(Duration.ofMinutes(16));
        assertThat(login(email, PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void aSuccessfulLoginResetsTheFailureCounter() {
        String email = registeredEmail();
        for (int i = 0; i < 4; i++) login(email, "wrong-password-123");
        assertThat(login(email, PASSWORD).status()).isEqualTo(200);
        for (int i = 0; i < 4; i++) login(email, "wrong-password-123");
        assertThat(login(email, PASSWORD).status()).isEqualTo(200);
    }

    // --- access tokens (API2) ----------------------------------------------------------------

    @Test
    void accessTokenCarriesOnlySubIatExpAndJti() throws Exception {
        String email = registeredEmail();
        Resp r = login(email, PASSWORD);
        SignedJWT jwt = SignedJWT.parse(r.accessToken());
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
        assertThat(jwt.getHeader().getKeyID()).isNotBlank();
        assertThat(jwt.getJWTClaimsSet().getClaims().keySet()).containsExactlyInAnyOrder("sub", "iat", "exp", "jti");
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(userId(email).toString());
        long lifetime = jwt.getJWTClaimsSet().getExpirationTime().getTime()
                - jwt.getJWTClaimsSet().getIssueTime().getTime();
        assertThat(lifetime).isEqualTo(Duration.ofMinutes(10).toMillis());
    }

    @Test
    void jwksPublishesOnlyThePublicKeyThatVerifiesTokens() throws Exception {
        Resp r = loggedIn();
        Resp jwks = send(HttpMethod.GET, "/.well-known/jwks.json", null);
        assertThat(jwks.status()).isEqualTo(200);
        JsonNode key = jwks.body().get("keys").get(0);
        assertThat(key.get("kty").asString()).isEqualTo("EC");
        assertThat(key.get("crv").asString()).isEqualTo("P-256");
        assertThat(key.has("d")).as("private key material").isFalse();
        assertThat(key.get("kid").asString())
                .isEqualTo(SignedJWT.parse(r.accessToken()).getHeader().getKeyID());
    }

    String protectedStatusWith(String bearer) {
        Resp r = send(
                HttpMethod.GET,
                "/api/v1/teams/" + UUID.randomUUID() + "/expenses",
                null,
                "Authorization",
                "Bearer " + bearer);
        return r.status() + "";
    }

    @Test
    void aValidTokenIsAcceptedAndNoTokenIsNot() {
        Resp r = loggedIn();
        // 404, not 401: authenticated, but not a teammate of this (random) team.
        assertThat(protectedStatusWith(r.accessToken())).isEqualTo("404");
        assertThat(send(HttpMethod.GET, "/api/v1/teams/" + UUID.randomUUID() + "/expenses", null)
                        .status())
                .isEqualTo(401);
    }

    @Test
    void unauthenticatedResponsesAreProblemDetails() {
        Resp r = send(HttpMethod.GET, "/api/v1/teams/" + UUID.randomUUID() + "/expenses", null);
        assertThat(r.body().get("code").asString()).isEqualTo("UNAUTHENTICATED");
        assertThat(r.headers().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(r.headers().getContentType().toString()).contains("application/problem+json");
    }

    @Test
    void expiredTokenGets401() {
        String token = issuer.issue(UUID.randomUUID());
        clock.advance(Duration.ofMinutes(11));
        assertThat(protectedStatusWith(token)).isEqualTo("401");
    }

    @Test
    void tamperedTokenGets401() {
        String token = issuer.issue(UUID.randomUUID());
        String[] parts = token.split("\\.");
        // Change the payload (a different subject) but keep the original signature.
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replaceFirst("\"sub\":\"[^\"]+\"", "\"sub\":\"" + UUID.randomUUID() + "\"");
        String tampered = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];
        assertThat(protectedStatusWith(tampered)).isEqualTo("401");
    }

    @Test
    void algNoneTokenGets401() {
        var enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        long now = Instant.now().getEpochSecond();
        String payload = enc.encodeToString(
                ("{\"sub\":\"" + UUID.randomUUID() + "\",\"iat\":" + now + ",\"exp\":" + (now + 600) + "}")
                        .getBytes(StandardCharsets.UTF_8));
        assertThat(protectedStatusWith(header + "." + payload + ".")).isEqualTo("401");
    }

    @Test
    void tokenSignedWithAnotherKeyGets401() throws Exception {
        var otherKey = new ECKeyGenerator(Curve.P_256).keyID("attacker").generate();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256)
                        .keyID(otherKey.getKeyID())
                        .build(),
                claimsFor(UUID.randomUUID()));
        jwt.sign(new ECDSASigner(otherKey));
        assertThat(protectedStatusWith(jwt.serialize())).isEqualTo("401");
    }

    @Test
    void algorithmConfusionWithAnHmacTokenGets401() throws Exception {
        // The classic attack: sign with HS256 using the (public) key bytes as the secret.
        Resp jwks = send(HttpMethod.GET, "/.well-known/jwks.json", null);
        byte[] secret =
                jwks.body().get("keys").get(0).get("x").asString().repeat(2).getBytes(StandardCharsets.UTF_8);
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsFor(UUID.randomUUID()));
        jwt.sign(new MACSigner(secret));
        assertThat(protectedStatusWith(jwt.serialize())).isEqualTo("401");
    }

    @Test
    void garbageBearerValuesGet401() {
        for (String garbage : new String[] {"abc", "a.b.c", "....", "Bearer"}) {
            assertThat(protectedStatusWith(garbage)).as(garbage).isEqualTo("401");
        }
    }

    private static JWTClaimsSet claimsFor(UUID user) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .subject(user.toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)))
                .jwtID(UUID.randomUUID().toString())
                .build();
    }

    // --- refresh and logout (API2) ---------------------------------------------------------

    @Test
    void refreshRotatesTheTokenAndIssuesANewAccessToken() {
        Resp login = loggedIn();
        Resp refreshed = refresh(login.cookieValue());
        assertThat(refreshed.status()).isEqualTo(200);
        assertThat(refreshed.cookieValue()).isNotEqualTo(login.cookieValue());
        assertThat(refreshed.accessToken()).isNotBlank();
        assertThat(refreshed.headers().getFirst(HttpHeaders.SET_COOKIE)).contains("HttpOnly", "SameSite=Strict");
        // The new cookie keeps working.
        assertThat(refresh(refreshed.cookieValue()).status()).isEqualTo(200);
    }

    @Test
    void replayingARotatedTokenRevokesTheWholeFamily() {
        Resp login = loggedIn();
        Resp refreshed = refresh(login.cookieValue());
        assertThat(refreshed.status()).isEqualTo(200);

        Resp replay = refresh(login.cookieValue());
        assertThat(replay.status()).isEqualTo(401);

        // The attacker's replay killed the legitimate session too: the successor is dead.
        assertThat(refresh(refreshed.cookieValue()).status()).isEqualTo(401);
    }

    @Test
    void refreshTokensAreStoredOnlyAsHashes() {
        Resp login = loggedIn();
        Integer matches = jdbc.sql("select count(*) from refresh_tokens where token_hash = :t")
                .param("t", login.cookieValue())
                .query(Integer.class)
                .single();
        assertThat(matches).isZero();
    }

    @Test
    void refreshNeedsTheCustomHeaderAndTheCookie() {
        Resp login = loggedIn();
        // Missing X-Requested-With: the CSRF defence of ADR 0004.
        Resp noHeader =
                send(HttpMethod.POST, "/api/v1/auth/refresh", null, "Cookie", "refresh_token=" + login.cookieValue());
        assertThat(noHeader.status()).isEqualTo(400);
        Resp wrongHeader = send(
                HttpMethod.POST,
                "/api/v1/auth/refresh",
                null,
                "X-Requested-With",
                "XMLHttpRequest",
                "Cookie",
                "refresh_token=" + login.cookieValue());
        assertThat(wrongHeader.status()).isEqualTo(400);
        // Missing cookie.
        assertThat(send(HttpMethod.POST, "/api/v1/auth/refresh", null, "X-Requested-With", "ledgerly")
                        .status())
                .isEqualTo(401);
        // The rejected attempts did not consume the token.
        assertThat(refresh(login.cookieValue()).status()).isEqualTo(200);
    }

    @Test
    void unknownRefreshTokenGets401() {
        assertThat(refresh("a".repeat(43)).status()).isEqualTo(401);
    }

    @Test
    void expiredRefreshTokenGets401() {
        Resp login = loggedIn();
        clock.advance(Duration.ofDays(15));
        assertThat(refresh(login.cookieValue()).status()).isEqualTo(401);
    }

    @Test
    void logoutRevokesTheFamilyAndClearsTheCookie() {
        Resp login = loggedIn();
        Resp refreshed = refresh(login.cookieValue());
        Resp out = send(
                HttpMethod.POST,
                "/api/v1/auth/logout",
                null,
                "X-Requested-With",
                "ledgerly",
                "Cookie",
                "refresh_token=" + refreshed.cookieValue());
        assertThat(out.status()).isEqualTo(204);
        assertThat(out.headers().getFirst(HttpHeaders.SET_COOKIE)).contains("Max-Age=0", "Path=/api/v1/auth");
        assertThat(refresh(refreshed.cookieValue()).status()).isEqualTo(401);
    }

    @Test
    void logoutIsIdempotentButStillNeedsTheCustomHeader() {
        assertThat(send(HttpMethod.POST, "/api/v1/auth/logout", null, "X-Requested-With", "ledgerly")
                        .status())
                .isEqualTo(204);
        assertThat(send(HttpMethod.POST, "/api/v1/auth/logout", null).status()).isEqualTo(400);
    }

    // --- log hygiene (API2) ---------------------------------------------------------------

    @Test
    void logsNeverContainPasswordsOrTokens(CapturedOutput output) {
        String email = registeredEmail();
        String verificationToken = mailbox.forUser(userId(email));
        verify(email);
        Resp login = login(email, PASSWORD);
        login(email, "Wrong-Passw0rd-abc");
        Resp refreshed = refresh(login.cookieValue());
        refresh(login.cookieValue()); // replay: logs a warning about reuse
        send(
                HttpMethod.POST,
                "/api/v1/auth/logout",
                null,
                "X-Requested-With",
                "ledgerly",
                "Cookie",
                "refresh_token=" + refreshed.cookieValue());

        String logs = output.getAll();
        assertThat(logs)
                .doesNotContain(PASSWORD)
                .doesNotContain("Wrong-Passw0rd-abc")
                .doesNotContain(verificationToken)
                .doesNotContain(login.accessToken())
                .doesNotContain(refreshed.accessToken())
                .doesNotContain(login.cookieValue())
                .doesNotContain(refreshed.cookieValue());
    }
}
