package com.ledgerly.owasp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * API5: Broken function level authorization. Control: {@code @PreAuthorize} with team role checks on use cases.
 * Proof: a MEMBER calling {@code :approve} gets 403.
 */
class Api5BrokenFunctionLevelAuthorizationTest extends OwaspTestBase {

    @Test
    void aMemberCallingApproveGets403() {
        Team team = newTeam();
        UUID submittedByMember = expense(team, team.member(), "SUBMITTED");
        // The member claimed it, so they can see it; being a member is what stops them deciding.
        Resp r =
                send(HttpMethod.POST, team.path() + "/expenses/" + submittedByMember + ":approve", team.member(), null);
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.code()).isEqualTo("FORBIDDEN");
    }

    @Test
    void anApproverCannotReimburseAndAMemberCannotReject() {
        Team team = newTeam();
        UUID approved = expense(team, team.member(), "APPROVED");
        UUID submitted = expense(team, team.member(), "SUBMITTED");
        assertThat(send(HttpMethod.POST, team.path() + "/expenses/" + approved + ":reimburse", team.approver(), null)
                        .status())
                .isEqualTo(403);
        assertThat(send(
                                HttpMethod.POST,
                                team.path() + "/expenses/" + submitted + ":reject",
                                team.member(),
                                "{\"reason\":\"no\"}")
                        .status())
                .isEqualTo(403);
    }

    @Test
    void teamAdministrationIsForOwnersOnly() {
        Team team = newTeam();
        String invite = "{\"email\":\"someone@example.com\",\"role\":\"MEMBER\"}";
        for (var user : java.util.List.of(team.approver(), team.member())) {
            assertThat(send(HttpMethod.POST, team.path() + "/invitations", user, invite)
                            .status())
                    .isEqualTo(403);
            assertThat(send(
                                    HttpMethod.PATCH,
                                    team.path() + "/members/" + team.colleague().id(),
                                    user,
                                    "{\"role\":\"OWNER\"}")
                            .status())
                    .isEqualTo(403);
            assertThat(send(
                                    HttpMethod.DELETE,
                                    team.path() + "/members/" + team.colleague().id(),
                                    user,
                                    null)
                            .status())
                    .isEqualTo(403);
            assertThat(get(team.path() + "/audit-events", user).status()).isEqualTo(403);
        }
        assertThat(send(HttpMethod.POST, team.path() + "/invitations", team.owner(), invite)
                        .status())
                .isEqualTo(201);
    }

    @Test
    void nobodyCanActAsAnotherRoleThroughHeadersOrTokens() {
        Team team = newTeam();
        UUID submitted = expense(team, team.colleague(), "SUBMITTED");
        // Old debug headers and role claims mean nothing any more: the role is read from the database.
        Resp r = send(
                HttpMethod.POST,
                team.path() + "/expenses/" + submitted + ":approve",
                team.member(),
                null,
                "X-Debug-Role",
                "OWNER",
                "X-Debug-User-Id",
                team.owner().id().toString());
        assertThat(r.status()).isEqualTo(404); // a member does not even see a colleague's expense
    }

    @Test
    void aDemotionTakesEffectOnTheNextRequestWithTheSameToken() {
        Team team = newTeam();
        UUID submitted = expense(team, team.colleague(), "SUBMITTED");
        String path = team.path() + "/expenses/" + submitted;
        assertThat(get(path, team.approver()).status()).isEqualTo(200);
        send(HttpMethod.PATCH, team.path() + "/members/" + team.approver().id(), team.owner(), "{\"role\":\"MEMBER\"}");
        assertThat(get(path, team.approver()).status()).isEqualTo(404);
    }
}
