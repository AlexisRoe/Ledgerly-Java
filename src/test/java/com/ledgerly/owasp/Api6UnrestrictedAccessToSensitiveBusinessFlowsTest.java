package com.ledgerly.owasp;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerly.TestUsers.TestUser;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * API6: Unrestricted access to sensitive business flows. Controls: no self-dealing, invitations never reveal which
 * emails are registered, imports capped per day. Proof: self-approval and self-reimbursement are 409; inviting a
 * registered and an unknown email give identical responses.
 */
class Api6UnrestrictedAccessToSensitiveBusinessFlowsTest extends OwaspTestBase {

    @Test
    void anApproverCannotApproveOrRejectTheirOwnExpense() {
        Team team = newTeam();
        UUID mine = expense(team, team.approver(), "SUBMITTED");
        Resp approve = send(HttpMethod.POST, team.path() + "/expenses/" + mine + ":approve", team.approver(), null);
        assertThat(approve.status()).isEqualTo(409);
        assertThat(approve.code()).isEqualTo("EXPENSE_SELF_DEALING");
        assertThat(send(
                                HttpMethod.POST,
                                team.path() + "/expenses/" + mine + ":reject",
                                team.approver(),
                                "{\"reason\":\"x\"}")
                        .status())
                .isEqualTo(409);
    }

    @Test
    void anOwnerCannotReimburseTheirOwnExpense() {
        Team team = newTeam();
        UUID mine = expense(team, team.owner(), "APPROVED");
        Resp r = send(HttpMethod.POST, team.path() + "/expenses/" + mine + ":reimburse", team.owner(), null);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("EXPENSE_SELF_DEALING");
    }

    @Test
    void somebodyElseCanStillDecideSoTheRuleIsNotJustABrokenWorkflow() {
        Team team = newTeam();
        UUID theirs = expense(team, team.approver(), "SUBMITTED");
        assertThat(send(HttpMethod.POST, team.path() + "/expenses/" + theirs + ":approve", team.owner(), null)
                        .status())
                .isEqualTo(200);
    }

    @Test
    void invitingARegisteredAndAnUnknownEmailLooksTheSame() {
        Team team = newTeam();
        TestUser registered = users.create(true);
        Resp known = send(
                HttpMethod.POST,
                team.path() + "/invitations",
                team.owner(),
                "{\"email\":\"" + registered.email() + "\",\"role\":\"MEMBER\"}");
        Resp unknown = send(
                HttpMethod.POST,
                team.path() + "/invitations",
                team.owner(),
                "{\"email\":\"nobody-" + UUID.randomUUID() + "@example.com\",\"role\":\"MEMBER\"}");
        assertThat(known.status()).isEqualTo(unknown.status());
        assertThat(known.json().propertyNames()).isEqualTo(unknown.json().propertyNames());
        assertThat(known.json().get("status")).isEqualTo(unknown.json().get("status"));
        assertThat(known.json().get("role")).isEqualTo(unknown.json().get("role"));
    }

    @Test
    void importsAreCappedAtFivePerDay() {
        Team team = newTeam();
        byte[] csv = "date,merchant,amount,currency,category\n2026-01-01,Cafe,1.00,EUR,MEALS\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (int i = 0; i < 5; i++) {
            assertThat(upload(team.path() + "/imports", team.colleague(), "e.csv", csv, "text/csv")
                            .status())
                    .isEqualTo(202);
        }
        assertThat(upload(team.path() + "/imports", team.colleague(), "e.csv", csv, "text/csv")
                        .status())
                .isEqualTo(429);
    }
}
