package com.ledgerly.owasp;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerly.TestUsers.TestUser;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * API1: Broken object level authorization. Control: team-scoped queries, a membership check in every use case, and
 * MEMBERs limited to their own expenses. Proof: a user of team A gets 404 for team B's expense and receipt, and a
 * MEMBER gets 404 for a teammate's expense.
 */
class Api1BrokenObjectLevelAuthorizationTest extends OwaspTestBase {

    @Test
    void aUserOfTeamAGets404ForTeamBsExpenseAndReceipt() {
        Team a = newTeam();
        Team b = newTeam();
        UUID expenseOfB = expense(b, b.member(), "DRAFT");
        UUID receiptOfB = receipts.accepted(b.id(), expenseOfB);

        // Through team B's own path: A's owner is not a teammate there.
        assertThat(get(b.path() + "/expenses/" + expenseOfB, a.owner()).status())
                .isEqualTo(404);
        assertThat(get(b.path() + "/expenses/" + expenseOfB + "/receipts/" + receiptOfB + "/download", a.owner())
                        .status())
                .isEqualTo(404);
        // Through team A's path with B's ids: the query is scoped to team A, so B's rows do not exist there.
        assertThat(get(a.path() + "/expenses/" + expenseOfB, a.owner()).status())
                .isEqualTo(404);
        assertThat(get(a.path() + "/expenses/" + expenseOfB + "/receipts/" + receiptOfB + "/download", a.owner())
                        .status())
                .isEqualTo(404);
    }

    @Test
    void aMemberGets404ForATeammatesExpense() {
        Team team = newTeam();
        UUID expense = expense(team, team.member(), "DRAFT");
        assertThat(get(team.path() + "/expenses/" + expense, team.colleague()).status())
                .isEqualTo(404);
        assertThat(get(team.path() + "/expenses/" + expense, team.member()).status())
                .isEqualTo(200);
        assertThat(get(team.path() + "/expenses/" + expense, team.approver()).status())
                .isEqualTo(200);
    }

    @Test
    void theListNeverShowsAnotherTeamsOrAnotherMembersExpenses() {
        Team a = newTeam();
        Team b = newTeam();
        UUID mine = expense(a, a.member(), "DRAFT");
        expense(a, a.colleague(), "DRAFT");
        expense(b, b.member(), "DRAFT");
        var items = get(a.path() + "/expenses?claimantId=" + a.colleague().id(), a.member())
                .json()
                .get("content");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("id").asString()).isEqualTo(mine.toString());
    }

    @Test
    void receiptsFollowTheirExpenseNotJustTheirOwnId() {
        Team team = newTeam();
        UUID first = expense(team, team.member(), "DRAFT");
        UUID second = expense(team, team.member(), "DRAFT");
        UUID receipt = receipts.accepted(team.id(), first);
        assertThat(get(team.path() + "/expenses/" + second + "/receipts/" + receipt + "/download", team.member())
                        .status())
                .isEqualTo(404);
        assertThat(get(team.path() + "/expenses/" + first + "/receipts/" + receipt + "/download", team.colleague())
                        .status())
                .isEqualTo(404);
    }

    @Test
    void theAuditTrailAndImportsOfOtherTeamsAreNotReachable() {
        Team a = newTeam();
        Team b = newTeam();
        assertThat(get(b.path() + "/audit-events", a.owner()).status()).isEqualTo(404);
        assertThat(get(b.path() + "/imports/" + UUID.randomUUID(), a.owner()).status())
                .isEqualTo(404);
    }

    @Test
    void anOutsiderLearnsNothingAboutWhichTeamsExist() {
        TestUser outsider = users.create(true);
        String real = get(newTeam().path() + "/expenses", outsider).text();
        String imaginary = get("/api/v1/teams/" + UUID.randomUUID() + "/expenses", outsider)
                .text();
        assertThat(JSON.readTree(real).get("code"))
                .isEqualTo(JSON.readTree(imaginary).get("code"));
    }
}
