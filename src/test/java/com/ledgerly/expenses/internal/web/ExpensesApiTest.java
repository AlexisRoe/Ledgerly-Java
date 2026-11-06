package com.ledgerly.expenses.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerly.TestcontainersConfiguration;
import com.ledgerly.expenses.FakeReceiptChecks;
import com.ledgerly.expenses.ReceiptSummary;
import com.ledgerly.identity.internal.application.AccessTokenIssuer;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@Import(TestcontainersConfiguration.class)
class ExpensesApiTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Autowired
    AccessTokenIssuer tokens;

    @Autowired
    FakeReceiptChecks receipts;

    RestTestClient client;
    UUID team;
    UUID claimant;
    UUID approver;
    UUID owner;

    @BeforeEach
    void setUp() {
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
        team = UUID.randomUUID();
        claimant = UUID.randomUUID();
        approver = UUID.randomUUID();
        owner = UUID.randomUUID();
        receipts.next = new ReceiptSummary(1, 0);
    }

    record Resp(int status, org.springframework.http.HttpHeaders headers, JsonNode body) {
        String etag() {
            return headers.getETag();
        }

        String id() {
            return body.get("id").asString();
        }

        String code() {
            return body.get("code").asString();
        }
    }

    Resp call(HttpMethod method, String path, UUID user, String role, String body, String... headerPairs) {
        var spec = client.method(method).uri("/api/v1" + path).headers(h -> {
            if (user != null) h.add("Authorization", "Bearer " + tokens.issue(user));
            if (role != null) h.add("X-Debug-Role", role);
            for (int i = 0; i < headerPairs.length; i += 2) h.add(headerPairs[i], headerPairs[i + 1]);
            if (body != null) h.add("Content-Type", "application/json");
        });
        var exchange = (body == null ? spec : spec.body(body)).exchange();
        var result = exchange.returnResult(String.class);
        String text = result.getResponseBody();
        JsonNode node = text == null || text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text);
        return new Resp(result.getStatus().value(), result.getResponseHeaders(), node);
    }

    String expenses() {
        return "/teams/" + team + "/expenses";
    }

    static String body(String amount, String currency, String date, String merchant) {
        return """
                {"money":{"amount":"%s","currency":"%s"},"category":"MEALS","incurredOn":"%s","merchant":"%s"}""".formatted(amount, currency, date, merchant);
    }

    String validBody() {
        return body("12.50", "EUR", LocalDate.now().toString(), "Cafe");
    }

    Resp createDraft() {
        Resp r = call(HttpMethod.POST, expenses(), claimant, "MEMBER", validBody());
        assertThat(r.status()).isEqualTo(201);
        return r;
    }

    Resp submitted() {
        Resp d = createDraft();
        Resp s = call(HttpMethod.POST, expenses() + "/" + d.id() + ":submit", claimant, "MEMBER", null);
        assertThat(s.status()).isEqualTo(200);
        return s;
    }

    // --- happy paths -------------------------------------------------------------------------

    @Test
    void createReturns201WithLocationAndEtag() {
        Resp r = createDraft();
        assertThat(r.headers().getLocation().getPath()).isEqualTo("/api/v1" + expenses() + "/" + r.id());
        assertThat(r.etag()).isEqualTo("\"0\"");
        assertThat(r.body().get("status").asString()).isEqualTo("DRAFT");
        assertThat(r.body().get("money").get("amount").asString()).isEqualTo("12.50");
        assertThat(r.body().get("claimantId").asString()).isEqualTo(claimant.toString());
    }

    @Test
    void getReturnsTheExpenseWithEtag() {
        Resp created = createDraft();
        Resp r = call(HttpMethod.GET, expenses() + "/" + created.id(), claimant, "MEMBER", null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.etag()).isEqualTo(created.etag());
    }

    @Test
    void patchWithIfMatchUpdatesAndBumpsEtag() {
        Resp created = createDraft();
        Resp r = call(
                HttpMethod.PATCH,
                expenses() + "/" + created.id(),
                claimant,
                "MEMBER",
                "{\"merchant\":\"Bakery\",\"note\":\"rolls\"}",
                "If-Match",
                created.etag());
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("merchant").asString()).isEqualTo("Bakery");
        assertThat(r.body().get("note").asString()).isEqualTo("rolls");
        assertThat(r.etag()).isEqualTo("\"1\"");
    }

    @Test
    void deleteDraftReturns204ThenGone() {
        Resp created = createDraft();
        Resp d = call(
                HttpMethod.DELETE,
                expenses() + "/" + created.id(),
                claimant,
                "MEMBER",
                null,
                "If-Match",
                created.etag());
        assertThat(d.status()).isEqualTo(204);
        assertThat(call(HttpMethod.GET, expenses() + "/" + created.id(), claimant, "MEMBER", null)
                        .status())
                .isEqualTo(404);
    }

    @Test
    void fullWorkflowDraftToReimbursed() {
        Resp s = submitted();
        String base = expenses() + "/" + s.id();
        assertThat(call(HttpMethod.POST, base + ":approve", approver, "APPROVER", null)
                        .body()
                        .get("status")
                        .asString())
                .isEqualTo("APPROVED");
        assertThat(call(HttpMethod.POST, base + ":reimburse", owner, "OWNER", null)
                        .body()
                        .get("status")
                        .asString())
                .isEqualTo("REIMBURSED");
    }

    @Test
    void rejectReopenWithdrawCycle() {
        Resp s = submitted();
        String base = expenses() + "/" + s.id();
        Resp rejected = call(HttpMethod.POST, base + ":reject", approver, "APPROVER", "{\"reason\":\"blurry\"}");
        assertThat(rejected.body().get("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.body().get("rejectionReasons").get(0).asString()).isEqualTo("blurry");
        assertThat(call(HttpMethod.POST, base + ":reopen", claimant, "MEMBER", null)
                        .body()
                        .get("status")
                        .asString())
                .isEqualTo("DRAFT");
        call(HttpMethod.POST, base + ":submit", claimant, "MEMBER", null);
        assertThat(call(HttpMethod.POST, base + ":withdraw", claimant, "MEMBER", null)
                        .body()
                        .get("status")
                        .asString())
                .isEqualTo("DRAFT");
    }

    // --- abuse tests ---------------------------------------------------------------------------

    @Test
    void patchCannotSetStatusOrClaimant_API3() {
        Resp created = createDraft();
        Resp r = call(
                HttpMethod.PATCH,
                expenses() + "/" + created.id(),
                claimant,
                "MEMBER",
                "{\"status\":\"APPROVED\"}",
                "If-Match",
                created.etag());
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("MALFORMED_REQUEST");
        Resp r2 = call(
                HttpMethod.PATCH,
                expenses() + "/" + created.id(),
                claimant,
                "MEMBER",
                "{\"claimantId\":\"" + UUID.randomUUID() + "\"}",
                "If-Match",
                created.etag());
        assertThat(r2.status()).isEqualTo(400);
    }

    @Test
    void memberCallingApproveGets403_API5() {
        Resp s = submitted();
        Resp r = call(HttpMethod.POST, expenses() + "/" + s.id() + ":approve", claimant, "MEMBER", null);
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.code()).isEqualTo("FORBIDDEN");
    }

    @Test
    void approvingYourOwnExpenseGets409() {
        Resp d = call(HttpMethod.POST, expenses(), approver, "APPROVER", validBody());
        call(HttpMethod.POST, expenses() + "/" + d.id() + ":submit", approver, "APPROVER", null);
        Resp r = call(HttpMethod.POST, expenses() + "/" + d.id() + ":approve", approver, "APPROVER", null);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("EXPENSE_SELF_DEALING");
    }

    @Test
    void illegalTransitionGets409() {
        Resp d = createDraft();
        Resp r = call(HttpMethod.POST, expenses() + "/" + d.id() + ":approve", approver, "APPROVER", null);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("EXPENSE_INVALID_TRANSITION");
        assertThat(r.body().get("type").asString()).isEqualTo("https://ledgerly.dev/problems/invalid-transition");
    }

    @Test
    void tooManyDecimalsGets400WithFieldError() {
        Resp r = call(
                HttpMethod.POST,
                expenses(),
                claimant,
                "MEMBER",
                body("12.345", "EUR", LocalDate.now().toString(), "x"));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("errors").get(0).get("field").asString()).isEqualTo("money");
    }

    @Test
    void futureDateGets400() {
        Resp r = call(
                HttpMethod.POST,
                expenses(),
                claimant,
                "MEMBER",
                body("1.00", "EUR", LocalDate.now().plusDays(2).toString(), "x"));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("errors").get(0).get("field").asString()).isEqualTo("incurredOn");
    }

    @Test
    void beanValidationErrorsAreListedPerField() {
        Resp r = call(HttpMethod.POST, expenses(), claimant, "MEMBER", "{\"category\":\"MEALS\",\"merchant\":\"\"}");
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(r.body().get("errors").size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void malformedJsonGets400Problem() {
        Resp r = call(HttpMethod.POST, expenses(), claimant, "MEMBER", "{not json");
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    void missingIfMatchGets428AndStaleGets412() {
        Resp created = createDraft();
        String path = expenses() + "/" + created.id();
        assertThat(call(HttpMethod.PATCH, path, claimant, "MEMBER", "{\"merchant\":\"A\"}")
                        .status())
                .isEqualTo(428);
        assertThat(call(HttpMethod.DELETE, path, claimant, "MEMBER", null).status())
                .isEqualTo(428);
        Resp stale = call(HttpMethod.PATCH, path, claimant, "MEMBER", "{\"merchant\":\"A\"}", "If-Match", "\"99\"");
        assertThat(stale.status()).isEqualTo(412);
        assertThat(stale.code()).isEqualTo("PRECONDITION_FAILED");
        assertThat(call(HttpMethod.DELETE, path, claimant, "MEMBER", null, "If-Match", "garbage")
                        .status())
                .isEqualTo(412);
    }

    @Test
    void secondPatchWithOldEtagGets412() {
        Resp created = createDraft();
        String path = expenses() + "/" + created.id();
        call(HttpMethod.PATCH, path, claimant, "MEMBER", "{\"merchant\":\"A\"}", "If-Match", created.etag());
        Resp again =
                call(HttpMethod.PATCH, path, claimant, "MEMBER", "{\"merchant\":\"B\"}", "If-Match", created.etag());
        assertThat(again.status()).isEqualTo(412);
    }

    @Test
    void editingOrDeletingANonDraftGets409() {
        Resp s = submitted();
        String path = expenses() + "/" + s.id();
        Resp edit = call(HttpMethod.PATCH, path, claimant, "MEMBER", "{\"merchant\":\"A\"}", "If-Match", s.etag());
        assertThat(edit.status()).isEqualTo(409);
        assertThat(edit.code()).isEqualTo("EXPENSE_NOT_DRAFT");
        assertThat(call(HttpMethod.DELETE, path, claimant, "MEMBER", null, "If-Match", s.etag())
                        .status())
                .isEqualTo(409);
    }

    @Test
    void anotherMemberCannotSeeOrTouchMyExpense_API1() {
        Resp d = createDraft();
        UUID other = UUID.randomUUID();
        String path = expenses() + "/" + d.id();
        assertThat(call(HttpMethod.GET, path, other, "MEMBER", null).status()).isEqualTo(404);
        assertThat(call(HttpMethod.PATCH, path, other, "MEMBER", "{\"merchant\":\"x\"}", "If-Match", d.etag())
                        .status())
                .isEqualTo(404);
        assertThat(call(HttpMethod.POST, path + ":submit", other, "MEMBER", null)
                        .status())
                .isEqualTo(404);
    }

    @Test
    void nonTeammateGets404EvenWithAValidId_API1() {
        Resp d = createDraft();
        assertThat(call(HttpMethod.GET, expenses() + "/" + d.id(), UUID.randomUUID(), null, null)
                        .status())
                .isEqualTo(404);
        assertThat(call(HttpMethod.POST, expenses(), UUID.randomUUID(), null, validBody())
                        .status())
                .isEqualTo(404);
    }

    @Test
    void expenseOfTeamAIsNotFoundThroughTeamB_API1() {
        Resp d = createDraft();
        String otherTeamPath = "/teams/" + UUID.randomUUID() + "/expenses/" + d.id();
        assertThat(call(HttpMethod.GET, otherTeamPath, approver, "OWNER", null).status())
                .isEqualTo(404);
    }

    @Test
    void approverCanSeeAnotherMembersExpense() {
        Resp d = createDraft();
        assertThat(call(HttpMethod.GET, expenses() + "/" + d.id(), approver, "APPROVER", null)
                        .status())
                .isEqualTo(200);
    }

    @Test
    void missingTokenGets401() {
        Resp r = call(HttpMethod.GET, expenses() + "/" + UUID.randomUUID(), null, "MEMBER", null);
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.code()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void submitNeedsAcceptedReceipt_D3() {
        Resp d = createDraft();
        receipts.next = new ReceiptSummary(0, 0);
        Resp r = call(HttpMethod.POST, expenses() + "/" + d.id() + ":submit", claimant, "MEMBER", null);
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("EXPENSE_RULE_VIOLATED");
    }

    @Test
    void rejectWithoutReasonGets400() {
        Resp s = submitted();
        Resp r =
                call(HttpMethod.POST, expenses() + "/" + s.id() + ":reject", approver, "APPROVER", "{\"reason\":\"\"}");
        assertThat(r.status()).isEqualTo(400);
    }

    @Test
    void unknownPathIsAProblemToo() {
        Resp r = call(HttpMethod.GET, "/nope", claimant, "MEMBER", null);
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.code()).isEqualTo("REQUEST_REJECTED");
    }

    // --- idempotency -------------------------------------------------------------------------

    @Test
    void sameKeySameBodyReplaysTheOriginal() {
        Resp first =
                call(HttpMethod.POST, expenses(), claimant, "MEMBER", validBody(), "Idempotency-Key", "key-12345678");
        Resp second =
                call(HttpMethod.POST, expenses(), claimant, "MEMBER", validBody(), "Idempotency-Key", "key-12345678");
        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.id()).isEqualTo(first.id());
    }

    @Test
    void sameKeyDifferentBodyGets422() {
        call(HttpMethod.POST, expenses(), claimant, "MEMBER", validBody(), "Idempotency-Key", "key-abcdefgh");
        Resp second = call(
                HttpMethod.POST,
                expenses(),
                claimant,
                "MEMBER",
                body("99.00", "EUR", LocalDate.now().toString(), "Other"),
                "Idempotency-Key",
                "key-abcdefgh");
        assertThat(second.status()).isEqualTo(422);
        assertThat(second.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void sameKeyFromAnotherUserIsIndependent() {
        Resp a = call(HttpMethod.POST, expenses(), claimant, "MEMBER", validBody(), "Idempotency-Key", "shared-key-1");
        Resp b = call(
                HttpMethod.POST,
                expenses(),
                UUID.randomUUID(),
                "MEMBER",
                validBody(),
                "Idempotency-Key",
                "shared-key-1");
        assertThat(b.status()).isEqualTo(201);
        assertThat(b.id()).isNotEqualTo(a.id());
    }

    @Test
    void withoutKeyEveryPostCreatesANewExpense() {
        assertThat(createDraft().id()).isNotEqualTo(createDraft().id());
    }
}
