package com.ledgerly.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerly.ExpenseSeeder;
import com.ledgerly.TeamSeeder;
import com.ledgerly.TestUsers;
import com.ledgerly.TestUsers.TestUser;
import com.ledgerly.TestcontainersConfiguration;
import com.ledgerly.identity.internal.application.AccessTokenIssuer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The role × endpoint matrix (API1, API5): for every team-scoped endpoint, what each kind of caller gets. Not a
 * teammate is 404 (existence is not revealed); a teammate with too low a role is 403, or 404 where the object
 * itself is hidden from that role (a MEMBER never sees a colleague's expense).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@Import(TestcontainersConfiguration.class)
class RoleMatrixTest {

    enum Who {
        NO_TOKEN,
        NON_MEMBER,
        MEMBER,
        APPROVER,
        OWNER
    }

    /** One team with a colleague who claims the expense under test, and a second OWNER so leaving is possible. */
    record Ctx(UUID team, TestUser member, TestUser approver, TestUser owner, TestUser nonMember, TestUser claimant) {}

    record Request(HttpMethod method, String path, String body, String ifMatch) {
        static Request get(String path) {
            return new Request(HttpMethod.GET, path, null, null);
        }

        static Request post(String path, String body) {
            return new Request(HttpMethod.POST, path, body, null);
        }
    }

    interface Prepare extends Function<Fixture, Request> {}

    record Endpoint(String name, Prepare prepare, int[] expected) {
        @Override
        public String toString() {
            return name;
        }
    }

    /** Everything a prepare function may use to set up its state. */
    record Fixture(Ctx ctx, ExpenseSeeder expenses, JdbcClient jdbc) {
        String team() {
            return "/teams/" + ctx.team();
        }

        UUID expense(String status) {
            return expenses.insert(
                    ctx.team(), ctx.claimant().id(), "10.00", "EUR", "MEALS", status, LocalDate.now(), Instant.now());
        }

        UUID invitation() {
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                            insert into invitations (id, team_id, email, role, status, invited_by, created_at, expires_at)
                            values (:id, :team, :email, 'MEMBER', 'PENDING', :by, now(), now() + interval '7 days')
                            """)
                    .param("id", id)
                    .param("team", ctx.team())
                    .param("email", "matrix-" + id + "@example.com")
                    .param("by", ctx.owner().id())
                    .update();
            return id;
        }
    }

    static final String NEW_EXPENSE = "{\"money\":{\"amount\":\"5.00\",\"currency\":\"EUR\"},\"category\":\"MEALS\","
            + "\"incurredOn\":\"" + LocalDate.now() + "\",\"merchant\":\"Cafe\"}";

    //                                       NO_TOKEN, NON_MEMBER, MEMBER, APPROVER, OWNER
    static final Endpoint[] ENDPOINTS = {
        new Endpoint("POST   expenses", f -> Request.post(f.team() + "/expenses", NEW_EXPENSE), new int[] {
            401, 404, 201, 201, 201
        }),
        new Endpoint("GET    expenses", f -> Request.get(f.team() + "/expenses"), new int[] {401, 404, 200, 200, 200}),
        new Endpoint("GET    expenses/{id}", f -> Request.get(f.team() + "/expenses/" + f.expense("DRAFT")), new int[] {
            401, 404, 404, 200, 200
        }),
        new Endpoint(
                "PATCH  expenses/{id}",
                f -> new Request(
                        HttpMethod.PATCH,
                        f.team() + "/expenses/" + f.expense("DRAFT"),
                        "{\"merchant\":\"X\"}",
                        "\"0\""),
                new int[] {401, 404, 404, 403, 403}),
        new Endpoint(
                "DELETE expenses/{id}",
                f -> new Request(HttpMethod.DELETE, f.team() + "/expenses/" + f.expense("DRAFT"), null, "\"0\""),
                new int[] {401, 404, 404, 403, 403}),
        new Endpoint(
                "POST   expenses/{id}:submit",
                f -> Request.post(f.team() + "/expenses/" + f.expense("DRAFT") + ":submit", null),
                new int[] {401, 404, 404, 403, 403}),
        new Endpoint(
                "POST   expenses/{id}:withdraw",
                f -> Request.post(f.team() + "/expenses/" + f.expense("SUBMITTED") + ":withdraw", null),
                new int[] {401, 404, 404, 403, 403}),
        new Endpoint(
                "POST   expenses/{id}:approve",
                f -> Request.post(f.team() + "/expenses/" + f.expense("SUBMITTED") + ":approve", null),
                new int[] {401, 404, 404, 200, 200}),
        new Endpoint(
                "POST   expenses/{id}:reject",
                f -> Request.post(f.team() + "/expenses/" + f.expense("SUBMITTED") + ":reject", "{\"reason\":\"no\"}"),
                new int[] {401, 404, 404, 200, 200}),
        new Endpoint(
                "POST   expenses/{id}:reopen",
                f -> Request.post(f.team() + "/expenses/" + f.expense("REJECTED") + ":reopen", null),
                new int[] {401, 404, 404, 403, 403}),
        new Endpoint(
                "POST   expenses/{id}:reimburse",
                f -> Request.post(f.team() + "/expenses/" + f.expense("APPROVED") + ":reimburse", null),
                new int[] {401, 404, 404, 403, 200}),
        new Endpoint(
                "POST   invitations",
                f -> Request.post(f.team() + "/invitations", "{\"email\":\"new@example.com\",\"role\":\"MEMBER\"}"),
                new int[] {401, 404, 403, 403, 201}),
        new Endpoint(
                "DELETE invitations/{id}",
                f -> new Request(HttpMethod.DELETE, f.team() + "/invitations/" + f.invitation(), null, null),
                new int[] {401, 404, 403, 403, 204}),
        new Endpoint(
                "PATCH  members/{id}",
                f -> new Request(
                        HttpMethod.PATCH,
                        f.team() + "/members/" + f.ctx().claimant().id(),
                        "{\"role\":\"APPROVER\"}",
                        null),
                new int[] {401, 404, 403, 403, 200}),
        new Endpoint(
                "DELETE members/{id}",
                f -> new Request(
                        HttpMethod.DELETE,
                        f.team() + "/members/" + f.ctx().claimant().id(),
                        null,
                        null),
                new int[] {401, 404, 403, 403, 204}),
        new Endpoint("POST   :leave", f -> Request.post("/teams/" + f.ctx().team() + ":leave", null), new int[] {
            401, 404, 204, 204, 204
        }),
    };

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AccessTokenIssuer issuer;

    RestTestClient client;
    TestUsers users;
    TeamSeeder teams;
    ExpenseSeeder expenses;

    @BeforeEach
    void setUp() {
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
        users = new TestUsers(jdbc, issuer);
        teams = new TeamSeeder(jdbc);
        expenses = new ExpenseSeeder(jdbc);
    }

    static Stream<Arguments> cells() {
        return Stream.of(ENDPOINTS)
                .flatMap(e -> Stream.of(Who.values()).map(who -> Arguments.of(e, who, e.expected()[who.ordinal()])));
    }

    @ParameterizedTest(name = "{0} as {1} -> {2}")
    @MethodSource("cells")
    void everyCellOfTheMatrix(Endpoint endpoint, Who who, int expectedStatus) {
        TestUser member = users.create(true);
        TestUser approver = users.create(true);
        TestUser owner = users.create(true);
        TestUser secondOwner = users.create(true);
        TestUser claimant = users.create(true);
        TestUser nonMember = users.create(true);
        UUID team = UUID.randomUUID();
        teams.member(team, member.id(), "MEMBER");
        teams.member(team, claimant.id(), "MEMBER");
        teams.member(team, approver.id(), "APPROVER");
        teams.member(team, owner.id(), "OWNER");
        teams.member(team, secondOwner.id(), "OWNER");
        Fixture fixture = new Fixture(new Ctx(team, member, approver, owner, nonMember, claimant), expenses, jdbc);

        Request request = endpoint.prepare().apply(fixture);
        TestUser caller = switch (who) {
            case NO_TOKEN -> null;
            case NON_MEMBER -> nonMember;
            case MEMBER -> member;
            case APPROVER -> approver;
            case OWNER -> owner;
        };

        var spec = client.method(request.method())
                .uri("/api/v1" + request.path())
                .headers(h -> {
                    if (caller != null) h.add("Authorization", caller.bearer());
                    if (request.ifMatch() != null) h.add("If-Match", request.ifMatch());
                    if (request.body() != null) h.add("Content-Type", "application/json");
                });
        int status = (request.body() == null ? spec : spec.body(request.body()))
                .exchange()
                .returnResult(String.class)
                .getStatus()
                .value();

        assertThat(status).as("%s as %s", endpoint, who).isEqualTo(expectedStatus);
    }
}
