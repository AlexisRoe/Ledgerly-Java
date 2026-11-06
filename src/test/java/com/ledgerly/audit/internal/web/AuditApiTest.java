package com.ledgerly.audit.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerly.ReceiptSeeder;
import com.ledgerly.TeamSeeder;
import com.ledgerly.TestUsers;
import com.ledgerly.TestUsers.TestUser;
import com.ledgerly.TestcontainersConfiguration;
import com.ledgerly.identity.internal.application.AccessTokenIssuer;
import com.ledgerly.identity.internal.application.CapturedVerificationTokens;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@Import(TestcontainersConfiguration.class)
class AuditApiTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AccessTokenIssuer issuer;

    @Autowired
    CapturedVerificationTokens mailbox;

    RestTestClient client;
    TestUsers users;
    UUID team;
    TestUser owner;
    TestUser approver;
    TestUser member;
    TestUser outsider;

    @BeforeEach
    void setUp() {
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
        users = new TestUsers(jdbc, issuer);
        team = UUID.randomUUID();
        owner = users.create(true);
        approver = users.create(true);
        member = users.create(true);
        outsider = users.create(true);
        var teams = new TeamSeeder(jdbc);
        teams.member(team, owner.id(), "OWNER");
        teams.member(team, approver.id(), "APPROVER");
        teams.member(team, member.id(), "MEMBER");
    }

    record Resp(int status, JsonNode body) {}

    Resp call(TestUser user, HttpMethod method, String path, String body) {
        var spec = client.method(method).uri(path).headers(h -> {
            if (user != null) h.add("Authorization", user.bearer());
            if (body != null) h.add("Content-Type", "application/json");
        });
        var result = (body == null ? spec : spec.body(body)).exchange().returnResult(String.class);
        String text = result.getResponseBody();
        return new Resp(
                result.getStatus().value(),
                text == null || text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text));
    }

    Resp trail(TestUser user, UUID forTeam, String query) {
        return call(user, HttpMethod.GET, "/api/v1/teams/" + forTeam + "/audit-events" + query, null);
    }

    List<String> types(Resp r) {
        List<String> types = new ArrayList<>();
        r.body().get("items").forEach(i -> types.add(i.get("eventType").asString()));
        return types;
    }

    /** Events are written asynchronously, so poll until the trail satisfies the condition. */
    Resp awaitTrail(UUID forTeam, Predicate<Resp> condition) {
        return await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(150))
                .until(() -> trail(owner, forTeam, "?limit=100"), r -> r.status() == 200 && condition.test(r));
    }

    // --- the story of one expense ----------------------------------------------------------------------------------

    @Test
    void submittingAnExpenseLeavesARowAndTheWholeLifeIsTold() {
        String base = "/api/v1/teams/" + team + "/expenses";
        String body = "{\"money\":{\"amount\":\"10.00\",\"currency\":\"EUR\"},\"category\":\"MEALS\",\"incurredOn\":\""
                + LocalDate.now() + "\",\"merchant\":\"Cafe Central\"}";
        Resp created = call(member, HttpMethod.POST, base, body);
        String id = created.body().get("id").asString();
        call(member, HttpMethod.PATCH, base + "/" + id, "{\"merchant\":\"Bakery\"}");
        // PATCH needs If-Match; the line above used none, so it was refused (428) and must leave no trace.
        new ReceiptSeeder(jdbc).accepted(team, UUID.fromString(id));
        assertThat(call(member, HttpMethod.POST, base + "/" + id + ":submit", null)
                        .status())
                .isEqualTo(200);
        assertThat(call(approver, HttpMethod.POST, base + "/" + id + ":approve", null)
                        .status())
                .isEqualTo(200);
        assertThat(call(owner, HttpMethod.POST, base + "/" + id + ":reimburse", null)
                        .status())
                .isEqualTo(200);

        Resp r = awaitTrail(team, x -> types(x).contains("EXPENSE_REIMBURSED"));
        // Newest first.
        assertThat(types(r))
                .containsExactly("EXPENSE_REIMBURSED", "EXPENSE_APPROVED", "EXPENSE_SUBMITTED", "EXPENSE_CREATED");

        JsonNode submitted = r.body().get("items").get(2);
        assertThat(submitted.get("actorId").asString()).isEqualTo(member.id().toString());
        assertThat(submitted.get("subjectType").asString()).isEqualTo("EXPENSE");
        assertThat(submitted.get("subjectId").asString()).isEqualTo(id);
        assertThat(r.body().get("items").get(1).get("actorId").asString())
                .isEqualTo(approver.id().toString());
        assertThat(r.body().get("items").get(0).get("actorId").asString())
                .isEqualTo(owner.id().toString());
    }

    @Test
    void anEditRecordsWhichFieldsChangedButNeverTheirValues() {
        String base = "/api/v1/teams/" + team + "/expenses";
        var created = client.post()
                .uri(base)
                .headers(h -> {
                    h.add("Authorization", member.bearer());
                    h.add("Content-Type", "application/json");
                })
                .body("{\"money\":{\"amount\":\"10.00\",\"currency\":\"EUR\"},\"category\":\"MEALS\",\"incurredOn\":\""
                        + LocalDate.now() + "\",\"merchant\":\"Cafe Central\"}")
                .exchange()
                .returnResult(String.class);
        String id = JSON.readTree(created.getResponseBody()).get("id").asString();
        String etag = created.getResponseHeaders().getETag();
        var edited = client.patch()
                .uri(base + "/" + id)
                .headers(h -> {
                    h.add("Authorization", member.bearer());
                    h.add("Content-Type", "application/json");
                    h.add("If-Match", etag);
                })
                .body("{\"merchant\":\"Very Secret Bakery\",\"note\":\"private note\"}")
                .exchange()
                .returnResult(String.class);
        assertThat(edited.getStatus().value()).isEqualTo(200);

        Resp r = awaitTrail(team, x -> types(x).contains("EXPENSE_EDITED"));
        JsonNode event = r.body().get("items").get(0);
        assertThat(event.get("eventType").asString()).isEqualTo("EXPENSE_EDITED");
        assertThat(event.get("details").get("changedFields").toString()).isEqualTo("[\"merchant\",\"note\"]");
        assertThat(r.body().toString())
                .doesNotContain("Bakery")
                .doesNotContain("private note")
                .doesNotContain("Cafe");
    }

    @Test
    void teamEventsAppearToo() {
        Resp created = call(owner, HttpMethod.POST, "/api/v1/teams", "{\"name\":\"Acme\"}");
        UUID newTeam = UUID.fromString(created.body().get("id").asString());
        Resp r = awaitTrail(newTeam, x -> types(x).contains("TEAM_CREATED"));
        assertThat(r.body().get("items").get(0).get("subjectId").asString()).isEqualTo(newTeam.toString());
    }

    // --- events without a team ---------------------------------------------------------------------------------

    @Test
    void registeringAndVerifyingAreStoredWithoutATeamAndNeverShownInOne() {
        String email = "audit-" + UUID.randomUUID() + "@example.com";
        assertThat(call(
                                null,
                                HttpMethod.POST,
                                "/api/v1/auth/register",
                                "{\"email\":\"" + email + "\",\"password\":\"S3cret-Passw0rd-xyz\"}")
                        .status())
                .isEqualTo(202);
        UUID userId = jdbc.sql("select id from users where email = :e")
                .param("e", email)
                .query(UUID.class)
                .single();
        call(null, HttpMethod.POST, "/api/v1/auth/verify", "{\"token\":\"" + mailbox.forUser(userId) + "\"}");

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(jdbc.sql("""
                                select count(*) from audit_events
                                where team_id is null and actor_id = :u and event_type in ('USER_REGISTERED', 'USER_VERIFIED')
                                """)
                                .param("u", userId)
                                .query(Integer.class)
                                .single())
                        .isEqualTo(2));

        // Even a team whose OWNER is that very user does not list them.
        UUID usersTeam = UUID.randomUUID();
        new TeamSeeder(jdbc).member(usersTeam, userId, "OWNER");
        TestUser asThatUser = new TestUser(userId, email, issuer.issue(userId));
        Resp r = trail(asThatUser, usersTeam, "");
        assertThat(r.status()).isEqualTo(200);
        assertThat(types(r)).doesNotContain("USER_REGISTERED", "USER_VERIFIED");
    }

    // --- who may read it (API1, API5) ------------------------------------------------------------------------------

    @Test
    void onlyOwnersMayReadTheTrail() {
        assertThat(trail(owner, team, "").status()).isEqualTo(200);
        assertThat(trail(approver, team, "").status()).isEqualTo(403);
        assertThat(trail(member, team, "").status()).isEqualTo(403);
        assertThat(trail(outsider, team, "").status()).isEqualTo(404);
        assertThat(trail(null, team, "").status()).isEqualTo(401);
    }

    @Test
    void anotherTeamsEventsAreNeverVisible_API1() {
        UUID otherTeam = UUID.randomUUID();
        TestUser otherOwner = users.create(true);
        new TeamSeeder(jdbc).member(otherTeam, otherOwner.id(), "OWNER");
        jdbc.sql("""
                        insert into audit_events (id, event_id, team_id, event_type, details, occurred_at, created_at)
                        values (:id, :e, :t, 'SECRET_OF_OTHER_TEAM', '{}'::jsonb, now(), now())
                        """)
                .param("id", UUID.randomUUID())
                .param("e", UUID.randomUUID())
                .param("t", otherTeam)
                .update();
        assertThat(types(trail(owner, team, ""))).doesNotContain("SECRET_OF_OTHER_TEAM");
        assertThat(types(trail(otherOwner, otherTeam, ""))).containsExactly("SECRET_OF_OTHER_TEAM");
    }

    // --- cursor paging (decision D8, wired up)
    // ---------------------------------------------------------------------------

    void seed(int count, Instant start) {
        for (int i = 0; i < count; i++) {
            jdbc.sql("""
                            insert into audit_events (id, event_id, team_id, event_type, details, occurred_at, created_at)
                            values (:id, :e, :t, 'SEEDED', jsonb_build_object('n', cast(:n as int)), :ts, :ts)
                            """)
                    .param("id", com.ledgerly.shared.id.UuidV7.generate())
                    .param("e", UUID.randomUUID())
                    .param("t", team)
                    .param("n", i)
                    // pairs of rows share a timestamp, so the id tie-breaker is used
                    .param("ts", Timestamp.from(start.plusSeconds(i / 2)))
                    .update();
        }
    }

    @Test
    void pagingWithCursorsVisitsEveryRowOnceNewestFirst() {
        seed(120, Instant.parse("2026-01-01T00:00:00Z"));
        Set<String> seen = new HashSet<>();
        List<Instant> created = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            Resp r = trail(owner, team, "?limit=50" + (cursor == null ? "" : "&after=" + cursor));
            assertThat(r.status()).isEqualTo(200);
            r.body().get("items").forEach(i -> {
                assertThat(seen.add(i.get("id").asString()))
                        .as("row seen twice")
                        .isTrue();
                created.add(Instant.parse(i.get("occurredAt").asString()));
            });
            cursor = r.body().has("nextCursor") && !r.body().get("nextCursor").isNull()
                    ? r.body().get("nextCursor").asString()
                    : null;
            pages++;
        } while (cursor != null);
        assertThat(pages).isEqualTo(3);
        assertThat(seen).hasSize(120);
        assertThat(created).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void newRowsArrivingWhilePagingNeverCauseRepeats() {
        seed(60, Instant.parse("2026-01-01T00:00:00Z"));
        Resp first = trail(owner, team, "?limit=30");
        String cursor = first.body().get("nextCursor").asString();
        Set<String> seen = new HashSet<>();
        first.body().get("items").forEach(i -> seen.add(i.get("id").asString()));
        seed(10, Instant.now().plusSeconds(3600)); // newer than everything, arriving mid-way
        while (cursor != null) {
            Resp r = trail(owner, team, "?limit=30&after=" + cursor);
            r.body()
                    .get("items")
                    .forEach(i -> assertThat(seen.add(i.get("id").asString())).isTrue());
            cursor = r.body().has("nextCursor") && !r.body().get("nextCursor").isNull()
                    ? r.body().get("nextCursor").asString()
                    : null;
        }
        assertThat(seen).hasSize(60);
    }

    @Test
    void theLastPageHasNoCursor() {
        seed(3, Instant.parse("2026-01-01T00:00:00Z"));
        Resp r = trail(owner, team, "?limit=50");
        assertThat(r.body().get("items")).hasSize(3);
        assertThat(r.body().has("nextCursor") && !r.body().get("nextCursor").isNull())
                .isFalse();
    }

    @Test
    void badCursorsAndLimitsGet400() {
        assertThat(trail(owner, team, "?after=not-a-cursor").status()).isEqualTo(400);
        assertThat(trail(owner, team, "?after=e30").status()).isEqualTo(400);
        assertThat(trail(owner, team, "?limit=0").status()).isEqualTo(400);
        assertThat(trail(owner, team, "?limit=101").status()).isEqualTo(400);
    }
}
