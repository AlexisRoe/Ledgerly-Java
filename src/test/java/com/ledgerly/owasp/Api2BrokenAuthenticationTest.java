package com.ledgerly.owasp;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerly.TestUsers.TestUser;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

/**
 * API2: Broken authentication. Controls: Argon2id, 10-minute asymmetric-signed JWTs, refresh rotation with reuse
 * detection, lock-out after failed logins, verified email needed to accept an invitation. Proof: a replayed refresh
 * token revokes the whole session family; an unverified account cannot accept an invitation.
 */
class Api2BrokenAuthenticationTest extends OwaspTestBase {

    static final String PASSWORD = "S3cret-Passw0rd-xyz";

    String registerAndVerify() {
        String email = "owasp-" + UUID.randomUUID() + "@example.com";
        assertThat(send(HttpMethod.POST, "/api/v1/auth/register", null, credentials(email, PASSWORD))
                        .status())
                .isEqualTo(202);
        UUID id = jdbc.sql("select id from users where email = :e")
                .param("e", email)
                .query(UUID.class)
                .single();
        send(HttpMethod.POST, "/api/v1/auth/verify", null, "{\"token\":\"" + mailbox.forUser(id) + "\"}");
        return email;
    }

    static String credentials(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    Resp login(String email, String password) {
        return send(HttpMethod.POST, "/api/v1/auth/login", null, credentials(email, password));
    }

    Resp refresh(String cookie) {
        return send(
                HttpMethod.POST,
                "/api/v1/auth/refresh",
                null,
                null,
                "X-Requested-With",
                "ledgerly",
                "Cookie",
                "refresh_token=" + cookie);
    }

    static String cookieValue(Resp r) {
        String header = r.headers().getFirst(HttpHeaders.SET_COOKIE);
        return header.substring(header.indexOf('=') + 1, header.indexOf(';'));
    }

    @Test
    void aReplayedRefreshTokenRevokesTheWholeSessionFamily() {
        String email = registerAndVerify();
        Resp login = login(email, PASSWORD);
        Resp rotated = refresh(cookieValue(login));
        assertThat(rotated.status()).isEqualTo(200);

        assertThat(refresh(cookieValue(login)).status()).isEqualTo(401); // the stolen, already used token
        assertThat(refresh(cookieValue(rotated)).status()).isEqualTo(401); // and the honest session dies with it
    }

    @Test
    void anUnverifiedAccountCannotAcceptAnInvitation() {
        Team team = newTeam();
        TestUser unverified = users.create(false);
        Resp invitation = send(
                HttpMethod.POST,
                team.path() + "/invitations",
                team.owner(),
                "{\"email\":\"" + unverified.email() + "\",\"role\":\"MEMBER\"}");
        Resp accept = send(HttpMethod.POST, "/api/v1/invitations/" + invitation.id() + ":accept", unverified, null);
        assertThat(accept.status()).isEqualTo(403);
        assertThat(accept.code()).isEqualTo("EMAIL_NOT_VERIFIED");
    }

    @Test
    void failedLoginsLockTheAccountEvenAgainstTheRightPassword() {
        String email = registerAndVerify();
        for (int i = 0; i < 5; i++)
            assertThat(login(email, "wrong-password-123").status()).isEqualTo(401);
        assertThat(login(email, PASSWORD).status()).isEqualTo(401);
        clock.advance(Duration.ofMinutes(16));
        assertThat(login(email, PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void unknownEmailAndWrongPasswordAreIndistinguishable() {
        String email = registerAndVerify();
        Resp wrong = login(email, "wrong-password-123");
        Resp unknown = login("nobody-" + UUID.randomUUID() + "@example.com", "wrong-password-123");
        assertThat(wrong.status()).isEqualTo(unknown.status());
        assertThat(wrong.json().get("detail")).isEqualTo(unknown.json().get("detail"));
        assertThat(wrong.json().get("code")).isEqualTo(unknown.json().get("code"));
    }

    @Test
    void registeringAnExistingEmailLooksLikeRegisteringANewOne() {
        String email = registerAndVerify();
        Resp again = send(HttpMethod.POST, "/api/v1/auth/register", null, credentials(email, PASSWORD));
        Resp fresh = send(
                HttpMethod.POST,
                "/api/v1/auth/register",
                null,
                credentials("fresh-" + UUID.randomUUID() + "@example.com", PASSWORD));
        assertThat(again.status()).isEqualTo(fresh.status());
        assertThat(again.json()).isEqualTo(fresh.json());
    }

    @Test
    void passwordsAreStoredAsArgon2idHashes() {
        String email = registerAndVerify();
        String hash = jdbc.sql("select password_hash from users where email = :e")
                .param("e", email)
                .query(String.class)
                .single();
        assertThat(hash).startsWith("$argon2id$").doesNotContain(PASSWORD);
    }

    @Test
    void accessTokensAreShortLivedAndTamperedOnesAreRefused() {
        TestUser user = users.create(true);
        String token = user.token();
        String[] parts = token.split("\\.");
        String forged = parts[0] + "." + parts[1] + "." + "A".repeat(parts[2].length());
        assertThat(send(HttpMethod.GET, "/api/v1/me", null, null, "Authorization", "Bearer " + forged)
                        .status())
                .isEqualTo(401);
        clock.advance(Duration.ofMinutes(11));
        assertThat(send(HttpMethod.GET, "/api/v1/me", null, null, "Authorization", "Bearer " + token)
                        .status())
                .isEqualTo(401);
    }
}
