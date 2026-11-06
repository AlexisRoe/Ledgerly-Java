package com.ledgerly.owasp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * API3: Broken object property level authorization. Control: separate request and response records; {@code status}
 * and {@code claimantId} are not writable. Proof: PATCH with {@code status: APPROVED} is rejected.
 */
class Api3BrokenObjectPropertyLevelAuthorizationTest extends OwaspTestBase {

    @Test
    void patchWithStatusIsRejectedAndNothingChanges() {
        Team team = newTeam();
        Resp created = send(HttpMethod.POST, team.path() + "/expenses", team.member(), expenseJson("10.00", "Cafe"));
        String etag = created.headers().getETag();
        Resp patch = send(
                HttpMethod.PATCH,
                team.path() + "/expenses/" + created.id(),
                team.member(),
                "{\"status\":\"APPROVED\"}",
                "If-Match",
                etag);
        assertThat(patch.status()).isEqualTo(400);
        assertThat(get(team.path() + "/expenses/" + created.id(), team.member())
                        .json()
                        .get("status")
                        .asString())
                .isEqualTo("DRAFT");
    }

    @Test
    void claimantIdCannotBeSetOnCreateEditOrBatch() {
        Team team = newTeam();
        String sneaky = "\"claimantId\":\"" + team.colleague().id() + "\"";
        String create = expenseJson("10.00", "x");
        assertThat(send(
                                HttpMethod.POST,
                                team.path() + "/expenses",
                                team.member(),
                                create.substring(0, create.length() - 1) + "," + sneaky + "}")
                        .status())
                .isEqualTo(400);

        Resp ok = send(HttpMethod.POST, team.path() + "/expenses", team.member(), expenseJson("10.00", "ok"));
        assertThat(send(
                                HttpMethod.PATCH,
                                team.path() + "/expenses/" + ok.id(),
                                team.member(),
                                "{" + sneaky + "}",
                                "If-Match",
                                ok.headers().getETag())
                        .status())
                .isEqualTo(400);

        String item = expenseJson("10.00", "x");
        Resp batch = send(
                HttpMethod.POST,
                team.path() + "/expenses:batch",
                team.member(),
                "{\"items\":[" + item.substring(0, item.length() - 1) + "," + sneaky + "}]}");
        assertThat(batch.status()).isEqualTo(400);
        assertThat(get(team.path() + "/expenses/" + ok.id(), team.member())
                        .json()
                        .get("claimantId")
                        .asString())
                .isEqualTo(team.member().id().toString());
    }

    @Test
    void roleAndIdentityFieldsCannotBeSetThroughTheTeamEndpoints() {
        Team team = newTeam();
        assertThat(send(
                                HttpMethod.POST,
                                "/api/v1/teams",
                                team.member(),
                                "{\"name\":\"X\",\"owner\":\""
                                        + team.colleague().id() + "\"}")
                        .status())
                .isEqualTo(400);
        assertThat(send(
                                HttpMethod.PATCH,
                                team.path() + "/members/" + team.colleague().id(),
                                team.owner(),
                                "{\"role\":\"MEMBER\",\"teamId\":\"" + UUID.randomUUID() + "\"}")
                        .status())
                .isEqualTo(400);
    }

    @Test
    void responsesShowOnlyTheDocumentedFields() {
        Team team = newTeam();
        UUID id = expense(team, team.member(), "DRAFT");
        Set<String> fields = new TreeSet<>();
        get(team.path() + "/expenses/" + id, team.member())
                .json()
                .propertyNames()
                .forEach(fields::add);
        // Not the version, the audit columns, or anything else from the database row.
        assertThat(fields)
                .isSubsetOf(
                        "id",
                        "teamId",
                        "claimantId",
                        "money",
                        "category",
                        "incurredOn",
                        "merchant",
                        "note",
                        "status",
                        "rejectionReasons");
        assertThat(fields).doesNotContain("version", "createdAt", "updatedAt");

        Set<String> me = new TreeSet<>();
        get("/api/v1/me", team.member()).json().propertyNames().forEach(me::add);
        assertThat(me).containsExactlyInAnyOrder("id", "email", "verified", "teams", "invitations");
    }
}
