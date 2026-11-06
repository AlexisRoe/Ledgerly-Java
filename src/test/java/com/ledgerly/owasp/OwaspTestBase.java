package com.ledgerly.owasp;

import com.ledgerly.ExpenseSeeder;
import com.ledgerly.MutableClock;
import com.ledgerly.ReceiptSeeder;
import com.ledgerly.TeamSeeder;
import com.ledgerly.TestUsers;
import com.ledgerly.TestUsers.TestUser;
import com.ledgerly.TestcontainersConfiguration;
import com.ledgerly.identity.internal.application.AccessTokenIssuer;
import com.ledgerly.identity.internal.application.CapturedVerificationTokens;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared set-up of the OWASP API Top 10 tests: one running application, a real HTTP client that never retries on
 * its own, and a team with one user of each role. Every {@code ApiNNN...Test} class proves one row of the table in
 * CONCEPT.md section 5.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "ledgerly.limits.requests-per-minute=250",
            "ledgerly.security.allowed-origins=https://app.example.com"
        })
@Import(TestcontainersConfiguration.class)
abstract class OwaspTestBase {

    protected static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    protected int port;

    @Value("${local.management.port}")
    protected int managementPort;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected AccessTokenIssuer issuer;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected CapturedVerificationTokens mailbox;

    protected RestTestClient client;
    protected TestUsers users;
    protected TeamSeeder teams;
    protected ExpenseSeeder expenses;
    protected ReceiptSeeder receipts;

    /** A team with one user of each role, plus a second member who is a colleague of the first. */
    protected record Team(UUID id, TestUser owner, TestUser approver, TestUser member, TestUser colleague) {
        public String path() {
            return "/api/v1/teams/" + id;
        }
    }

    protected record Resp(int status, HttpHeaders headers, String text) {
        public JsonNode json() {
            return text == null || text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text);
        }

        public String id() {
            return json().get("id").asString();
        }

        public String code() {
            return json().get("code").asString();
        }
    }

    @BeforeEach
    void baseSetUp() {
        // The JDK client does not retry; Apache HttpClient would wait out a Retry-After header for hours.
        client = RestTestClient.bindToServer(new JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + port)
                .build();
        users = new TestUsers(jdbc, issuer);
        teams = new TeamSeeder(jdbc);
        expenses = new ExpenseSeeder(jdbc);
        receipts = new ReceiptSeeder(jdbc);
    }

    @AfterEach
    void baseTearDown() {
        clock.reset();
    }

    protected Team newTeam() {
        Team team = new Team(
                UUID.randomUUID(), users.create(true), users.create(true), users.create(true), users.create(true));
        teams.member(team.id(), team.owner().id(), "OWNER");
        teams.member(team.id(), team.approver().id(), "APPROVER");
        teams.member(team.id(), team.member().id(), "MEMBER");
        teams.member(team.id(), team.colleague().id(), "MEMBER");
        return team;
    }

    /** A DRAFT (or other status) expense claimed by {@code claimant}, written straight to the database. */
    protected UUID expense(Team team, TestUser claimant, String status) {
        return expenses.insert(
                team.id(), claimant.id(), "10.00", "EUR", "MEALS", status, LocalDate.now(), Instant.now());
    }

    protected Resp send(HttpMethod method, String path, TestUser user, String body, String... headerPairs) {
        var spec = client.method(method).uri(path).headers(h -> {
            if (user != null) h.add("Authorization", user.bearer());
            for (int i = 0; i < headerPairs.length; i += 2) h.add(headerPairs[i], headerPairs[i + 1]);
            if (body != null && !h.containsHeader("Content-Type")) h.add("Content-Type", "application/json");
        });
        var result = (body == null ? spec : spec.body(body)).exchange().returnResult(String.class);
        return new Resp(result.getStatus().value(), result.getResponseHeaders(), result.getResponseBody());
    }

    protected Resp get(String path, TestUser user) {
        return send(HttpMethod.GET, path, user, null);
    }

    protected Resp upload(String path, TestUser user, String filename, byte[] bytes, String contentType) {
        var builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(bytes))
                .filename(filename)
                .contentType(MediaType.parseMediaType(contentType));
        var result = client.post()
                .uri(path)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .headers(h -> {
                    if (user != null) h.add("Authorization", user.bearer());
                })
                .body(builder.build())
                .exchange()
                .returnResult(String.class);
        return new Resp(result.getStatus().value(), result.getResponseHeaders(), result.getResponseBody());
    }

    protected static String expenseJson(String amount, String merchant) {
        return "{\"money\":{\"amount\":\"" + amount
                + "\",\"currency\":\"EUR\"},\"category\":\"MEALS\",\"incurredOn\":\"" + LocalDate.now()
                + "\",\"merchant\":\"" + merchant + "\"}";
    }

    protected static final byte[] PDF =
            "%PDF-1.4\n1 0 obj << >> endobj\n%%EOF\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
}
