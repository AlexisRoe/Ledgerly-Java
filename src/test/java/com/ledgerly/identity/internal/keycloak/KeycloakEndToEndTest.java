package com.ledgerly.identity.internal.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerly.TestcontainersConfiguration;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ledgerly as a pure resource server of a real Keycloak (the realm from {@code keycloak/ledgerly-realm.json}, imported
 * at start-up). Tokens are fetched from Keycloak exactly as a client would; the application never sees a password.
 * The point of the test: every module that is not identity works unchanged with these tokens.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@ActiveProfiles({"keycloak", "test"})
@Import(TestcontainersConfiguration.class)
class KeycloakEndToEndTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>("quay.io/keycloak/keycloak:26.8")
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("keycloak/ledgerly-realm.json"),
                    "/opt/keycloak/data/import/ledgerly-realm.json")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/ledgerly/.well-known/openid-configuration")
                    .forPort(8080)
                    .withStartupTimeout(Duration.ofMinutes(5)));

    static {
        KEYCLOAK.start();
    }

    static String issuer() {
        return "http://localhost:" + KEYCLOAK.getMappedPort(8080) + "/realms/ledgerly";
    }

    @DynamicPropertySource
    static void keycloak(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", KeycloakEndToEndTest::issuer);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    RestTestClient client() {
        return RestTestClient.bindToServer(new JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + port)
                .build();
    }

    /** The password grant, enabled in the development realm only; a real client uses the authorization code flow. */
    static String tokenFor(String clientId, String username, String password) throws Exception {
        String form = "grant_type=password&scope=openid%20email&client_id=" + enc(clientId) + "&username="
                + enc(username) + "&password=" + enc(password);
        var request = HttpRequest.newBuilder(URI.create(issuer() + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body()).get("access_token").asString();
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static JsonNode claims(String token) {
        return JSON.readTree(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8));
    }

    record Resp(int status, String text) {
        JsonNode json() {
            return text == null || text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text);
        }
    }

    Resp call(HttpMethod method, String path, String token, String body) {
        var spec = client().method(method).uri(path).headers(h -> {
            if (token != null) h.add("Authorization", "Bearer " + token);
            if (body != null) h.add("Content-Type", "application/json");
        });
        var result = (body == null ? spec : spec.body(body)).exchange().returnResult(String.class);
        return new Resp(result.getStatus().value(), result.getResponseBody());
    }

    // --- a Keycloak token is a Ledgerly session
    // --------------------------------------------------------------------------

    @Test
    void aKeycloakTokenWorksAndCreatesOurUserOnFirstUse() throws Exception {
        String token = tokenFor("ledgerly-web", "alice@example.com", "alice-password");
        String keycloakSub = claims(token).get("sub").asString();
        assertThat(claims(token).get("aud").toString()).contains("ledgerly-api");

        // Tests share one database: another test may already have created or linked Alice.
        boolean createdHere = jdbc.sql("select count(*) from users where email = 'alice@example.com'")
                        .query(Integer.class)
                        .single()
                == 0;
        Resp me = call(HttpMethod.GET, "/api/v1/me", token, null);
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.json().get("email").asString()).isEqualTo("alice@example.com");
        assertThat(me.json().get("verified").asBoolean()).isTrue();
        String ourId = me.json().get("id").asString();
        assertThat(ourId).isNotEqualTo(keycloakSub); // Ledgerly's own id, not Keycloak's

        assertThat(jdbc.sql("select external_subject from users where id = :id")
                        .param("id", UUID.fromString(ourId))
                        .query(String.class)
                        .single())
                .isEqualTo(keycloakSub);
        if (createdHere) {
            assertThat(jdbc.sql("select password_hash from users where id = :id")
                            .param("id", UUID.fromString(ourId))
                            .query(String.class)
                            .optional())
                    .isEmpty(); // just-in-time users never get a password
        }
        // And the next call is the same person.
        assertThat(call(HttpMethod.GET, "/api/v1/me", token, null)
                        .json()
                        .get("id")
                        .asString())
                .isEqualTo(ourId);
    }

    @Test
    void theRestOfLedgerlyWorksUnchangedWithKeycloakIdentities() throws Exception {
        String alice = tokenFor("ledgerly-web", "alice@example.com", "alice-password");
        String ourId =
                call(HttpMethod.GET, "/api/v1/me", alice, null).json().get("id").asString();

        // Teams, expenses, receipts' rules, audit: nothing knows the identity comes from Keycloak.
        Resp team = call(HttpMethod.POST, "/api/v1/teams", alice, "{\"name\":\"Keycloak team\"}");
        assertThat(team.status()).isEqualTo(201);
        String teamId = team.json().get("id").asString();
        String expense =
                "{\"money\":{\"amount\":\"12.50\",\"currency\":\"EUR\"},\"category\":\"MEALS\",\"incurredOn\":\""
                        + LocalDate.now() + "\",\"merchant\":\"Cafe\"}";
        Resp created = call(HttpMethod.POST, "/api/v1/teams/" + teamId + "/expenses", alice, expense);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().get("claimantId").asString()).isEqualTo(ourId);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            Resp trail = call(HttpMethod.GET, "/api/v1/teams/" + teamId + "/audit-events", alice, null);
            assertThat(trail.status()).isEqualTo(200);
            List<String> types = trail.json()
                    .get("items")
                    .valueStream()
                    .map(i -> i.get("eventType").asString())
                    .toList();
            assertThat(types).contains("TEAM_CREATED", "EXPENSE_CREATED");
            assertThat(trail.json().get("items").get(0).get("actorId").asString())
                    .isEqualTo(ourId);
        });
    }

    @Test
    void anUnverifiedEmailStaysUnverifiedAndCannotAcceptInvitations() throws Exception {
        String alice = tokenFor("ledgerly-web", "alice@example.com", "alice-password");
        String bob = tokenFor("ledgerly-web", "bob@example.com", "bob-password");
        assertThat(claims(bob).get("email_verified").asBoolean()).isFalse();

        Resp me = call(HttpMethod.GET, "/api/v1/me", bob, null);
        assertThat(me.json().get("verified").asBoolean()).isFalse();

        String teamId = call(HttpMethod.POST, "/api/v1/teams", alice, "{\"name\":\"Invite bob\"}")
                .json()
                .get("id")
                .asString();
        Resp invitation = call(
                HttpMethod.POST,
                "/api/v1/teams/" + teamId + "/invitations",
                alice,
                "{\"email\":\"bob@example.com\",\"role\":\"MEMBER\"}");
        Resp accept = call(
                HttpMethod.POST,
                "/api/v1/invitations/" + invitation.json().get("id").asString() + ":accept",
                bob,
                null);
        assertThat(accept.status()).isEqualTo(403);
        assertThat(accept.json().get("code").asString()).isEqualTo("EMAIL_NOT_VERIFIED");
        // Verified Alice, however, can see nothing of bob's invitation: it is addressed to him.
        assertThat(call(HttpMethod.GET, "/api/v1/me", bob, null).json().get("invitations"))
                .isEmpty(); // unverified: hidden
    }

    // --- what must be refused
    // -------------------------------------------------------------------------------------------------------

    @Test
    void aTokenForAnotherApplicationOfTheSameRealmIsRefused() throws Exception {
        String foreign = tokenFor("other-app", "alice@example.com", "alice-password");
        assertThat(claims(foreign).path("aud").toString()).doesNotContain("ledgerly-api");
        assertThat(call(HttpMethod.GET, "/api/v1/me", foreign, null).status()).isEqualTo(401);
    }

    @Test
    void tamperedAndGarbageTokensAreRefused() throws Exception {
        String token = tokenFor("ledgerly-web", "alice@example.com", "alice-password");
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replaceFirst("\"sub\":\"[^\"]+\"", "\"sub\":\"" + UUID.randomUUID() + "\"");
        String tampered = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "."
                + parts[2];
        assertThat(call(HttpMethod.GET, "/api/v1/me", tampered, null).status()).isEqualTo(401);
        assertThat(call(HttpMethod.GET, "/api/v1/me", "not.a.jwt", null).status())
                .isEqualTo(401);
        assertThat(call(HttpMethod.GET, "/api/v1/me", null, null).status()).isEqualTo(401);
    }

    @Test
    void anExistingLocalAccountWithTheSameVerifiedEmailIsLinkedNotDuplicated() throws Exception {
        // A person who registered with Ledgerly's own login before the switch.
        UUID legacy = UUID.randomUUID();
        jdbc.sql(
                        "insert into users (id, email, password_hash, verified, created_at) values (:id, 'alice@example.com', 'old-hash', true, now()) on conflict (email) do nothing")
                .param("id", legacy)
                .update();
        String alice = tokenFor("ledgerly-web", "alice@example.com", "alice-password");
        String ourId =
                call(HttpMethod.GET, "/api/v1/me", alice, null).json().get("id").asString();
        assertThat(jdbc.sql("select count(*) from users where email = 'alice@example.com'")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("select external_subject from users where id = :id")
                        .param("id", UUID.fromString(ourId))
                        .query(String.class)
                        .single())
                .isEqualTo(claims(alice).get("sub").asString());
    }

    @Test
    void theApplicationNoLongerIssuesOrAcceptsItsOwnLogins() {
        for (String path : List.of("/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/refresh")) {
            assertThat(call(
                                    HttpMethod.POST,
                                    path,
                                    null,
                                    "{\"email\":\"alice@example.com\",\"password\":\"alice-password\"}")
                            .status())
                    .as(path)
                    .isEqualTo(404);
        }
        assertThat(call(HttpMethod.GET, "/.well-known/jwks.json", null, null).status())
                .isEqualTo(404);
    }
}
