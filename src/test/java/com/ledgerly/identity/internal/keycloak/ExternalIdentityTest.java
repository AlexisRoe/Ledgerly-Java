package com.ledgerly.identity.internal.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.ledgerly.TestcontainersConfiguration;
import com.ledgerly.identity.internal.application.ExternalIdentityProvisioner;
import com.ledgerly.identity.internal.application.ExternalSubjectJwtConverter;
import com.ledgerly.shared.id.UserId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The rules for turning an identity provider's token into a Ledgerly user, with hand-made tokens (no Keycloak needed:
 * the signature check is not what is tested here; see {@link KeycloakEndToEndTest} for that).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            // Never contacted: the decoder is built on first use and these tests never present a real token.
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:1/realms/unused"
        })
@ActiveProfiles({"keycloak", "test"})
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ExternalIdentityTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ExternalIdentityProvisioner provisioner;

    @Autowired
    ExternalSubjectJwtConverter converter;

    static String newSubject() {
        return UUID.randomUUID().toString();
    }

    static String newEmail() {
        return "kc-" + UUID.randomUUID() + "@example.com";
    }

    int rowsFor(String subject) {
        return jdbc.sql("select count(*) from users where external_subject = :s")
                .param("s", subject)
                .query(Integer.class)
                .single();
    }

    Jwt token(String subject, String email, Boolean emailVerified) {
        var builder = Jwt.withTokenValue("opaque-token-value")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600));
        if (email != null) builder.claim("email", email);
        if (emailVerified != null) builder.claim("email_verified", emailVerified);
        return builder.build();
    }

    // --- first use creates the user ------------------------------------------------------------------------------

    @Test
    void theFirstTokenCreatesAUserWithOurOwnIdAndNoPassword() {
        String subject = newSubject();
        String email = newEmail();
        UserId id = provisioner.resolve(subject, email.toUpperCase(), true);

        var row = jdbc.sql(
                        "select id, email, verified, password_hash, external_subject from users where external_subject = :s")
                .param("s", subject)
                .query((rs, n) -> new Object[] {
                    rs.getObject("id", UUID.class),
                    rs.getString("email"),
                    rs.getBoolean("verified"),
                    rs.getString("password_hash"),
                    rs.getString("external_subject")
                })
                .single();
        assertThat(row[0]).isEqualTo(id.value());
        assertThat(id.value().toString()).isNotEqualTo(subject); // our id, not the provider's
        assertThat(row[1]).isEqualTo(email); // normalised
        assertThat(row[2]).isEqualTo(true);
        assertThat(row[3]).isNull();
    }

    @Test
    void theSameSubjectIsTheSameUserEveryTime() {
        String subject = newSubject();
        String email = newEmail();
        UserId first = provisioner.resolve(subject, email, true);
        UserId second = provisioner.resolve(subject, email, true);
        UserId third = provisioner.resolve(subject, "changed-" + email, true); // the subject wins over a changed email
        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
        assertThat(rowsFor(subject)).isEqualTo(1);
    }

    @Test
    void creatingAUserIsAuditedWithoutAnyEmail() {
        String subject = newSubject();
        UserId id = provisioner.resolve(subject, newEmail(), true);
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(jdbc.sql("""
                                select count(*) from audit_events
                                where actor_id = :u and event_type in ('USER_REGISTERED', 'USER_VERIFIED') and team_id is null
                                """)
                                .param("u", id.value())
                                .query(Integer.class)
                                .single())
                        .isEqualTo(2));
        assertThat(jdbc.sql("select count(*) from audit_events where cast(details as text) like '%@%'")
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    // --- verification follows the provider, towards verified only
    // ---------------------------------------------------------

    @Test
    void anUnverifiedEmailStaysUnverifiedUntilTheProviderSaysOtherwise() {
        String subject = newSubject();
        String email = newEmail();
        provisioner.resolve(subject, email, false);
        assertThat(verified(subject)).isFalse();
        provisioner.resolve(subject, email, true);
        assertThat(verified(subject)).isTrue();
        provisioner.resolve(subject, email, false); // a later token never takes it back
        assertThat(verified(subject)).isTrue();
    }

    private boolean verified(String subject) {
        return jdbc.sql("select verified from users where external_subject = :s")
                .param("s", subject)
                .query(Boolean.class)
                .single();
    }

    // --- what is refused
    // ---------------------------------------------------------------------------------------------------

    @Test
    void aNewPersonWithoutAnEmailClaimIsRefused() {
        String subject = newSubject();
        assertThatThrownBy(() -> provisioner.resolve(subject, null, true))
                .isInstanceOf(InvalidBearerTokenException.class);
        assertThatThrownBy(() -> provisioner.resolve(subject, "  ", true))
                .isInstanceOf(InvalidBearerTokenException.class);
        assertThat(rowsFor(subject)).isZero();
    }

    @Test
    void aMissingSubjectIsRefused() {
        assertThatThrownBy(() -> converter.convert(token(" ", newEmail(), true)))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    // --- linking a local account
    // -----------------------------------------------------------------------------------------

    @Test
    void anExistingLocalAccountIsLinkedWhenTheProviderVerifiesTheSameEmail() {
        UUID existing = UUID.randomUUID();
        String email = newEmail();
        insertLocalUser(existing, email, false);

        String subject = newSubject();
        UserId id = provisioner.resolve(subject, email.toUpperCase(), true);

        assertThat(id.value()).isEqualTo(existing); // same account, no duplicate
        assertThat(rowsFor(subject)).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from users where email = :e")
                        .param("e", email)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(verified(subject)).isTrue();
    }

    @Test
    void anExistingLocalAccountIsNeverLinkedOnAnUnverifiedEmail() {
        UUID existing = UUID.randomUUID();
        String email = newEmail();
        insertLocalUser(existing, email, true);
        String subject = newSubject();
        assertThatThrownBy(() -> provisioner.resolve(subject, email, false))
                .isInstanceOf(InvalidBearerTokenException.class);
        assertThat(rowsFor(subject)).isZero();
        assertThat(jdbc.sql("select external_subject from users where id = :id")
                        .param("id", existing)
                        .query(String.class)
                        .optional())
                .isEmpty();
    }

    @Test
    void anAccountAlreadyLinkedToSomeoneElseCannotBeTakenOver() {
        String email = newEmail();
        provisioner.resolve(newSubject(), email, true);
        assertThatThrownBy(() -> provisioner.resolve(newSubject(), email, true))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    private void insertLocalUser(UUID id, String email, boolean verified) {
        jdbc.sql(
                        "insert into users (id, email, password_hash, verified, created_at) values (:id, :e, 'hash', :v, now())")
                .param("id", id)
                .param("e", email)
                .param("v", verified)
                .update();
    }

    // --- two first requests at once
    // ------------------------------------------------------------------------------------------

    @Test
    void simultaneousFirstRequestsOfTheSamePersonCreateOneUser() throws Exception {
        String subject = newSubject();
        String email = newEmail();
        var gate = new CyclicBarrier(8);
        Set<UserId> ids = new HashSet<>();
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<java.util.concurrent.Future<UserId>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    return provisioner.resolve(subject, email, true);
                }));
            }
            for (var f : futures) ids.add(f.get());
        }
        assertThat(ids).hasSize(1);
        assertThat(rowsFor(subject)).isEqualTo(1);
    }

    // --- the converter: what the rest of Ledgerly sees
    // -----------------------------------------------------------------------------

    @Test
    void theConverterReplacesSubWithOurIdSoNothingElseHasToKnowAboutKeycloak() {
        String subject = newSubject();
        var authentication = (JwtAuthenticationToken) converter.convert(token(subject, newEmail(), true));
        Jwt seenByTheRestOfTheApplication = authentication.getToken();
        UUID ourId = UUID.fromString(seenByTheRestOfTheApplication.getSubject());
        assertThat(ourId.toString()).isNotEqualTo(subject);
        assertThat(jdbc.sql("select external_subject from users where id = :id")
                        .param("id", ourId)
                        .query(String.class)
                        .single())
                .isEqualTo(subject);
        assertThat(seenByTheRestOfTheApplication.getClaimAsString("external_sub"))
                .isEqualTo(subject);
        assertThat(seenByTheRestOfTheApplication.getTokenValue()).isEqualTo("opaque-token-value");
    }

    // --- the parts that do not exist in this mode
    // ------------------------------------------------------------------------------------

    @Test
    void ourOwnLoginRegistrationAndKeyEndpointsAreGone() {
        var client = RestTestClient.bindToServer(new JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + port)
                .build();
        for (String path : List.of(
                "/api/v1/auth/login",
                "/api/v1/auth/register",
                "/api/v1/auth/refresh",
                "/api/v1/auth/logout",
                "/api/v1/auth/verify")) {
            int status = client.post()
                    .uri(path)
                    .header("Content-Type", "application/json")
                    .body("{}")
                    .exchange()
                    .returnResult(String.class)
                    .getStatus()
                    .value();
            assertThat(status).as(path).isEqualTo(404);
        }
        assertThat(client.get()
                        .uri("/.well-known/jwks.json")
                        .exchange()
                        .returnResult(String.class)
                        .getStatus()
                        .value())
                .isEqualTo(404);
    }

    @Test
    void aTokenIsRefusedCleanlyWhenTheIdentityProviderCannotBeReached(CapturedOutput output) {
        // The issuer configured for this test does not exist, which is what Keycloak being down looks like.
        var client = RestTestClient.bindToServer(new JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + port)
                .build();
        String forged =
                "eyJhbGciOiJFUzI1NiIsImtpZCI6Im9sZCJ9.eyJzdWIiOiIxMTExMTExMS0xMTExLTExMTEtMTExMS0xMTExMTExMTExMTEifQ.AAAA";
        var result = client.get()
                .uri("/api/v1/me")
                .header("Authorization", "Bearer " + forged)
                .exchange()
                .returnResult(String.class);
        assertThat(result.getStatus().value()).isEqualTo(401);
        assertThat(result.getResponseHeaders().getContentType().toString()).contains("application/problem+json");
        assertThat(result.getResponseBody()).contains("UNAUTHENTICATED").doesNotContain("Exception");
        assertThat(output.getAll())
                .contains("the identity provider is not reachable")
                .doesNotContain("Servlet.service() for servlet");
    }
}
