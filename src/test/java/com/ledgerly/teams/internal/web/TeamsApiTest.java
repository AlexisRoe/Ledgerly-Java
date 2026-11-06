package com.ledgerly.teams.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerly.MutableClock;
import com.ledgerly.TeamSeeder;
import com.ledgerly.TestUsers;
import com.ledgerly.TestUsers.TestUser;
import com.ledgerly.TestcontainersConfiguration;
import com.ledgerly.identity.internal.application.AccessTokenIssuer;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
class TeamsApiTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AccessTokenIssuer issuer;

    @Autowired
    MutableClock clock;

    RestTestClient client;
    TestUsers users;
    TeamSeeder seeder;

    @BeforeEach
    void setUp() {
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
        users = new TestUsers(jdbc, issuer);
        seeder = new TeamSeeder(jdbc);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    record Resp(int status, HttpHeaders headers, JsonNode body) {
        String id() {
            return body.get("id").asString();
        }

        String code() {
            return body.get("code").asString();
        }
    }

    Resp call(TestUser user, HttpMethod method, String path, String body) {
        var spec = client.method(method).uri("/api/v1" + path).headers(h -> {
            if (user != null) h.add("Authorization", user.bearer());
            if (body != null) h.add("Content-Type", "application/json");
        });
        var result = (body == null ? spec : spec.body(body)).exchange().returnResult(String.class);
        String text = result.getResponseBody();
        return new Resp(
                result.getStatus().value(),
                result.getResponseHeaders(),
                text == null || text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text));
    }

    /** After moving the clock a long way the old access token is expired; mint one valid at the new time. */
    TestUser fresh(TestUser user) {
        return new TestUser(user.id(), user.email(), issuer.issue(user.id()));
    }

    /** A team created through the API by a fresh verified user, who is its OWNER. */
    record Team(UUID id, TestUser owner) {}

    Team newTeam() {
        TestUser owner = users.create(true);
        Resp r = call(owner, HttpMethod.POST, "/teams", "{\"name\":\"Acme\"}");
        assertThat(r.status()).isEqualTo(201);
        return new Team(UUID.fromString(r.id()), owner);
    }

    static String invite(String email, String role) {
        return "{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}";
    }

    Resp invitation(Team team, String email, String role) {
        Resp r = call(team.owner(), HttpMethod.POST, "/teams/" + team.id() + "/invitations", invite(email, role));
        assertThat(r.status()).isEqualTo(201);
        return r;
    }

    Resp accept(TestUser user, String invitationId) {
        return call(user, HttpMethod.POST, "/invitations/" + invitationId + ":accept", null);
    }

    String roleIn(TestUser user, UUID team) {
        Resp me = call(user, HttpMethod.GET, "/me", null);
        for (JsonNode t : me.body().get("teams")) {
            if (t.get("teamId").asString().equals(team.toString()))
                return t.get("role").asString();
        }
        return null;
    }

    // --- creating a team ------------------------------------------------------------------

    @Test
    void creatorBecomesOwnerAndGetsALocation() {
        TestUser user = users.create(true);
        Resp r = call(user, HttpMethod.POST, "/teams", "{\"name\":\"Acme\"}");
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.headers().getLocation().getPath()).isEqualTo("/api/v1/teams/" + r.id());
        assertThat(roleIn(user, UUID.fromString(r.id()))).isEqualTo("OWNER");
    }

    @Test
    void teamNameIsValidated() {
        TestUser user = users.create(true);
        assertThat(call(user, HttpMethod.POST, "/teams", "{\"name\":\"\"}").status())
                .isEqualTo(400);
        assertThat(call(user, HttpMethod.POST, "/teams", "{\"name\":\"" + "x".repeat(101) + "\"}")
                        .status())
                .isEqualTo(400);
        assertThat(call(user, HttpMethod.POST, "/teams", "{}").status()).isEqualTo(400);
        assertThat(call(null, HttpMethod.POST, "/teams", "{\"name\":\"A\"}").status())
                .isEqualTo(401);
    }

    // --- inviting (API6) --------------------------------------------------------------------

    @Test
    void invitingARegisteredAndAnUnknownEmailLooksIdentical() {
        Team team = newTeam();
        TestUser registered = users.create(true);
        Resp known = invitation(team, registered.email(), "MEMBER");
        Resp unknown = invitation(team, "nobody-" + UUID.randomUUID() + "@example.com", "MEMBER");
        assertThat(known.status()).isEqualTo(unknown.status());
        assertThat(known.body().propertyNames()).isEqualTo(unknown.body().propertyNames());
        assertThat(known.body().get("status")).isEqualTo(unknown.body().get("status"));
        assertThat(known.body().get("role")).isEqualTo(unknown.body().get("role"));
    }

    @Test
    void invitingAnExistingTeammateLooksIdentical() {
        Team team = newTeam();
        // The owner's own address is already a teammate; the answer must not reveal that.
        Resp self = invitation(team, team.owner().email(), "MEMBER");
        Resp other = invitation(team, "other-" + UUID.randomUUID() + "@example.com", "MEMBER");
        assertThat(self.status()).isEqualTo(other.status());
        assertThat(self.body().propertyNames()).isEqualTo(other.body().propertyNames());
    }

    @Test
    void invitationInputIsValidated() {
        Team team = newTeam();
        String path = "/teams/" + team.id() + "/invitations";
        assertThat(call(team.owner(), HttpMethod.POST, path, invite("not-an-email", "MEMBER"))
                        .status())
                .isEqualTo(400);
        assertThat(call(team.owner(), HttpMethod.POST, path, invite("a@example.com", "BOSS"))
                        .status())
                .isEqualTo(400);
    }

    @Test
    void reInvitingReplacesThePendingInvitation() {
        Team team = newTeam();
        TestUser invitee = users.create(true);
        Resp first = invitation(team, invitee.email(), "MEMBER");
        Resp second = invitation(team, invitee.email().toUpperCase(), "APPROVER");
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(jdbc.sql("select count(*) from invitations where team_id = :t and status = 'PENDING'")
                        .param("t", team.id())
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(accept(invitee, first.id()).status()).isEqualTo(409);
        Resp accepted = accept(invitee, second.id());
        assertThat(accepted.status()).isEqualTo(200);
        assertThat(accepted.body().get("role").asString()).isEqualTo("APPROVER");
    }

    // --- accepting and declining (API2, API6) ----------------------------------------------------

    @Test
    void verifiedInviteeAcceptsAndJoinsWithTheInvitedRole() {
        Team team = newTeam();
        TestUser invitee = users.create(true);
        Resp inv = invitation(team, invitee.email(), "APPROVER");
        Resp r = accept(invitee, inv.id());
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("teamId").asString()).isEqualTo(team.id().toString());
        assertThat(roleIn(invitee, team.id())).isEqualTo("APPROVER");
    }

    @Test
    void emailMatchingIgnoresCase() {
        Team team = newTeam();
        TestUser invitee = users.create(true);
        Resp inv = invitation(team, invitee.email().toUpperCase(), "MEMBER");
        assertThat(accept(invitee, inv.id()).status()).isEqualTo(200);
    }

    @Test
    void unverifiedUserCannotAcceptAnInvitation_API2() {
        Team team = newTeam();
        TestUser invitee = users.create(false);
        Resp inv = invitation(team, invitee.email(), "MEMBER");
        Resp r = accept(invitee, inv.id());
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.code()).isEqualTo("EMAIL_NOT_VERIFIED");
        assertThat(roleIn(invitee, team.id())).isNull();
    }

    @Test
    void someoneElseGets404ForAnInvitationNotAddressedToThem() {
        Team team = newTeam();
        TestUser invitee = users.create(true);
        Resp inv = invitation(team, invitee.email(), "MEMBER");
        TestUser stranger = users.create(true);
        assertThat(accept(stranger, inv.id()).status()).isEqualTo(404);
        assertThat(call(stranger, HttpMethod.POST, "/invitations/" + inv.id() + ":decline", null)
                        .status())
                .isEqualTo(404);
        assertThat(accept(invitee, UUID.randomUUID().toString()).status()).isEqualTo(404);
    }

    @Test
    void expiredInvitationCannotBeAcceptedAndNeedsNoScheduler() {
        Team team = newTeam();
        TestUser invitee = users.create(true);
        Resp inv = invitation(team, invitee.email(), "MEMBER");
        clock.advance(Duration.ofDays(8));
        Resp r = accept(fresh(invitee), inv.id());
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("INVITATION_EXPIRED");
    }

    @Test
    void anInvitationCanBeAcceptedOnlyOnce() {
        Team team = newTeam();
        TestUser invitee = users.create(true);
        Resp inv = invitation(team, invitee.email(), "MEMBER");
        assertThat(accept(invitee, inv.id()).status()).isEqualTo(200);
        Resp again = accept(invitee, inv.id());
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("INVITATION_NOT_PENDING");
    }

    @Test
    void acceptingWhileAlreadyATeammateIsRefused() {
        Team team = newTeam();
        Resp inv = invitation(team, team.owner().email(), "MEMBER");
        Resp r = accept(team.owner(), inv.id());
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("ALREADY_MEMBER");
        assertThat(roleIn(team.owner(), team.id())).isEqualTo("OWNER");
    }

    @Test
    void declineClosesTheInvitation() {
        Team team = newTeam();
        TestUser invitee = users.create(true);
        Resp inv = invitation(team, invitee.email(), "MEMBER");
        assertThat(call(invitee, HttpMethod.POST, "/invitations/" + inv.id() + ":decline", null)
                        .status())
                .isEqualTo(204);
        assertThat(accept(invitee, inv.id()).status()).isEqualTo(409);
        assertThat(call(invitee, HttpMethod.POST, "/invitations/" + inv.id() + ":decline", null)
                        .status())
                .isEqualTo(409);
    }

    @Test
    void revokeClosesTheInvitation() {
        Team team = newTeam();
        TestUser invitee = users.create(true);
        Resp inv = invitation(team, invitee.email(), "MEMBER");
        String path = "/teams/" + team.id() + "/invitations/" + inv.id();
        assertThat(call(team.owner(), HttpMethod.DELETE, path, null).status()).isEqualTo(204);
        assertThat(accept(invitee, inv.id()).status()).isEqualTo(409);
        assertThat(call(team.owner(), HttpMethod.DELETE, path, null).status()).isEqualTo(409);
    }

    @Test
    void anotherTeamsInvitationCannotBeRevokedThroughMyTeam_API1() {
        Team mine = newTeam();
        Team theirs = newTeam();
        Resp inv = invitation(theirs, "x-" + UUID.randomUUID() + "@example.com", "MEMBER");
        Resp r = call(mine.owner(), HttpMethod.DELETE, "/teams/" + mine.id() + "/invitations/" + inv.id(), null);
        assertThat(r.status()).isEqualTo(404);
    }

    // --- role ladder and the last-owner rule -------------------------------------------------------

    @Test
    void ownerChangesARoleAndTheTargetMustBeATeammate() {
        Team team = newTeam();
        TestUser member = users.create(true);
        seeder.member(team.id(), member.id(), "MEMBER");
        Resp r = call(
                team.owner(),
                HttpMethod.PATCH,
                "/teams/" + team.id() + "/members/" + member.id(),
                "{\"role\":\"APPROVER\"}");
        assertThat(r.status()).isEqualTo(200);
        assertThat(roleIn(member, team.id())).isEqualTo("APPROVER");
        assertThat(call(
                                team.owner(),
                                HttpMethod.PATCH,
                                "/teams/" + team.id() + "/members/" + UUID.randomUUID(),
                                "{\"role\":\"APPROVER\"}")
                        .status())
                .isEqualTo(404);
        assertThat(call(
                                team.owner(),
                                HttpMethod.PATCH,
                                "/teams/" + team.id() + "/members/" + member.id(),
                                "{\"role\":\"BOSS\"}")
                        .status())
                .isEqualTo(400);
    }

    @Test
    void theLastOwnerCannotBeDemotedRemovedOrLeave() {
        Team team = newTeam();
        String self = "/teams/" + team.id() + "/members/" + team.owner().id();
        Resp demote = call(team.owner(), HttpMethod.PATCH, self, "{\"role\":\"MEMBER\"}");
        assertThat(demote.status()).isEqualTo(409);
        assertThat(demote.code()).isEqualTo("LAST_OWNER");
        assertThat(call(team.owner(), HttpMethod.DELETE, self, null).code()).isEqualTo("LAST_OWNER");
        assertThat(call(team.owner(), HttpMethod.POST, "/teams/" + team.id() + ":leave", null)
                        .code())
                .isEqualTo("LAST_OWNER");
        assertThat(roleIn(team.owner(), team.id())).isEqualTo("OWNER");
    }

    @Test
    void withASecondOwnerTheFirstMayDemoteOrLeave() {
        Team team = newTeam();
        TestUser second = users.create(true);
        seeder.member(team.id(), second.id(), "OWNER");
        assertThat(call(
                                team.owner(),
                                HttpMethod.PATCH,
                                "/teams/" + team.id() + "/members/" + second.id(),
                                "{\"role\":\"MEMBER\"}")
                        .status())
                .isEqualTo(200);
        seeder.member(team.id(), second.id(), "OWNER");
        assertThat(call(team.owner(), HttpMethod.POST, "/teams/" + team.id() + ":leave", null)
                        .status())
                .isEqualTo(204);
        assertThat(roleIn(second, team.id())).isEqualTo("OWNER");
    }

    @Test
    void ownerRemovesAMemberWhoThenLosesAccess() {
        Team team = newTeam();
        TestUser member = users.create(true);
        seeder.member(team.id(), member.id(), "MEMBER");
        String list = "/teams/" + team.id() + "/expenses";
        assertThat(call(member, HttpMethod.GET, list, null).status()).isEqualTo(200);
        assertThat(call(team.owner(), HttpMethod.DELETE, "/teams/" + team.id() + "/members/" + member.id(), null)
                        .status())
                .isEqualTo(204);
        assertThat(call(member, HttpMethod.GET, list, null).status()).isEqualTo(404);
    }

    @Test
    void aMemberCanLeave() {
        Team team = newTeam();
        TestUser member = users.create(true);
        seeder.member(team.id(), member.id(), "MEMBER");
        assertThat(call(member, HttpMethod.POST, "/teams/" + team.id() + ":leave", null)
                        .status())
                .isEqualTo(204);
        assertThat(roleIn(member, team.id())).isNull();
        assertThat(call(member, HttpMethod.POST, "/teams/" + team.id() + ":leave", null)
                        .status())
                .isEqualTo(404);
    }

    @Test
    void demotionTakesEffectOnTheNextRequestWithTheSameToken() {
        Team team = newTeam();
        TestUser approver = users.create(true);
        seeder.member(team.id(), approver.id(), "APPROVER");
        String path = "/teams/" + team.id() + "/expenses";
        assertThat(call(approver, HttpMethod.GET, path, null).status()).isEqualTo(200);
        call(
                team.owner(),
                HttpMethod.PATCH,
                "/teams/" + team.id() + "/members/" + approver.id(),
                "{\"role\":\"MEMBER\"}");
        // Roles are read from the database on every call, not from the token.
        assertThat(roleIn(approver, team.id())).isEqualTo("MEMBER");
    }

    // --- GET /me (decision D5) -------------------------------------------------------------------

    @Test
    void meListsRolesPerTeamAndPendingInvitationsForAVerifiedEmail() {
        Team team = newTeam();
        TestUser invitee = users.create(true);
        Resp inv = invitation(team, invitee.email(), "APPROVER");
        Resp me = call(invitee, HttpMethod.GET, "/me", null);
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body().get("email").asString()).isEqualTo(invitee.email());
        assertThat(me.body().get("verified").asBoolean()).isTrue();
        assertThat(me.body().get("teams")).isEmpty();
        assertThat(me.body().get("invitations")).hasSize(1);
        JsonNode pending = me.body().get("invitations").get(0);
        assertThat(pending.get("id").asString()).isEqualTo(inv.id());
        assertThat(pending.get("teamName").asString()).isEqualTo("Acme");
        assertThat(pending.get("role").asString()).isEqualTo("APPROVER");

        accept(invitee, inv.id());
        Resp after = call(invitee, HttpMethod.GET, "/me", null);
        assertThat(after.body().get("invitations")).isEmpty();
        assertThat(after.body().get("teams").get(0).get("role").asString()).isEqualTo("APPROVER");
    }

    @Test
    void meHidesInvitationsFromAnUnverifiedEmail() {
        Team team = newTeam();
        TestUser unverified = users.create(false);
        invitation(team, unverified.email(), "MEMBER");
        Resp me = call(unverified, HttpMethod.GET, "/me", null);
        assertThat(me.body().get("verified").asBoolean()).isFalse();
        assertThat(me.body().get("invitations")).isEmpty();
    }

    @Test
    void meOmitsExpiredAndRevokedInvitations() {
        Team team = newTeam();
        TestUser invitee = users.create(true);
        Resp inv = invitation(team, invitee.email(), "MEMBER");
        call(team.owner(), HttpMethod.DELETE, "/teams/" + team.id() + "/invitations/" + inv.id(), null);
        assertThat(call(invitee, HttpMethod.GET, "/me", null).body().get("invitations"))
                .isEmpty();

        invitation(team, invitee.email(), "MEMBER");
        clock.advance(Duration.ofDays(8));
        assertThat(call(fresh(invitee), HttpMethod.GET, "/me", null).body().get("invitations"))
                .isEmpty();
    }

    @Test
    void meNeedsAToken() {
        assertThat(call(null, HttpMethod.GET, "/me", null).status()).isEqualTo(401);
    }

    // --- edge case: a removed claimant (decision in the plan, M6) ----------------------------------

    @Test
    void removedClaimantCannotWithdrawOrReopenButApproversStillDecide() {
        Team team = newTeam();
        TestUser claimant = users.create(true);
        TestUser approver = users.create(true);
        seeder.member(team.id(), claimant.id(), "MEMBER");
        seeder.member(team.id(), approver.id(), "APPROVER");
        // FakeReceiptChecks (test profile) reports one accepted receipt, so submit works.
        String body = "{\"money\":{\"amount\":\"10.00\",\"currency\":\"EUR\"},\"category\":\"MEALS\","
                + "\"incurredOn\":\"" + LocalDate.now() + "\",\"merchant\":\"Cafe\"}";
        String base = "/teams/" + team.id() + "/expenses";
        Resp created = call(claimant, HttpMethod.POST, base, body);
        assertThat(created.status()).isEqualTo(201);
        String expense = base + "/" + created.id();
        assertThat(call(claimant, HttpMethod.POST, expense + ":submit", null).status())
                .isEqualTo(200);

        call(team.owner(), HttpMethod.DELETE, "/teams/" + team.id() + "/members/" + claimant.id(), null);

        // The claimant has left the team: no more withdrawing, reopening or reading.
        assertThat(call(claimant, HttpMethod.POST, expense + ":withdraw", null).status())
                .isEqualTo(404);
        assertThat(call(claimant, HttpMethod.GET, expense, null).status()).isEqualTo(404);
        // Approvers can still decide on the expense, and it keeps its claimant.
        Resp approved = call(approver, HttpMethod.POST, expense + ":approve", null);
        assertThat(approved.status()).isEqualTo(200);
        assertThat(approved.body().get("claimantId").asString())
                .isEqualTo(claimant.id().toString());
    }
}
