package com.ledgerly.expenses.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerly.ExpenseSeeder;
import com.ledgerly.TeamSeeder;
import com.ledgerly.TestUsers;
import com.ledgerly.TestUsers.TestUser;
import com.ledgerly.TestcontainersConfiguration;
import com.ledgerly.identity.internal.application.AccessTokenIssuer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
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

/** POST /expenses:batch (D4: all writing goes through ExpensesFacade) and GET /expenses.csv (D7). */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "ledgerly.limits.max-export-rows=1200"})
@Import(TestcontainersConfiguration.class)
class BatchAndExportApiTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AccessTokenIssuer issuer;

    RestTestClient client;
    TestUsers users;
    ExpenseSeeder seeder;
    UUID team;
    TestUser member;
    TestUser other;
    TestUser approver;
    TestUser outsider;

    @BeforeEach
    void setUp() {
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
        users = new TestUsers(jdbc, issuer);
        seeder = new ExpenseSeeder(jdbc);
        team = UUID.randomUUID();
        member = users.create(true);
        other = users.create(true);
        approver = users.create(true);
        outsider = users.create(true);
        var teams = new TeamSeeder(jdbc);
        teams.member(team, member.id(), "MEMBER");
        teams.member(team, other.id(), "MEMBER");
        teams.member(team, approver.id(), "APPROVER");
    }

    record Resp(int status, HttpHeaders headers, String text) {
        JsonNode json() {
            return text == null || text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text);
        }
    }

    Resp call(TestUser user, HttpMethod method, String path, String body) {
        var spec = client.method(method).uri(path).headers(h -> {
            if (user != null) h.add("Authorization", user.bearer());
            if (body != null) h.add("Content-Type", "application/json");
        });
        var result = (body == null ? spec : spec.body(body)).exchange().returnResult(String.class);
        return new Resp(result.getStatus().value(), result.getResponseHeaders(), result.getResponseBody());
    }

    static String item(String amount, String currency, String date, String merchant) {
        return "{\"money\":{\"amount\":\"" + amount + "\",\"currency\":\"" + currency
                + "\"},\"category\":\"MEALS\",\"incurredOn\":\"" + date + "\",\"merchant\":\"" + merchant + "\"}";
    }

    static String items(String... items) {
        return "{\"items\":[" + String.join(",", items) + "]}";
    }

    String good(int n) {
        return item("1.00", "EUR", LocalDate.now().toString(), "Merchant " + n);
    }

    String batchPath(boolean atomic) {
        return "/api/v1/teams/" + team + "/expenses:batch" + (atomic ? "?atomic=true" : "");
    }

    int expenseCount() {
        return jdbc.sql("select count(*) from expenses where team_id = :t")
                .param("t", team)
                .query(Integer.class)
                .single();
    }

    // --- batch: partial success (default) --------------------------------------------------------------------

    @Test
    void aFullBatchOfHundredCreatesHundredDraftsForTheCaller() {
        String[] hundred = new String[100];
        for (int i = 0; i < 100; i++) hundred[i] = good(i);
        Resp r = call(member, HttpMethod.POST, batchPath(false), items(hundred));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("created").asInt()).isEqualTo(100);
        assertThat(r.json().get("rejected").asInt()).isZero();
        var ids = new HashSet<String>();
        for (JsonNode result : r.json().get("results")) {
            assertThat(result.get("status").asString()).isEqualTo("CREATED");
            ids.add(result.get("id").asString());
        }
        assertThat(ids).hasSize(100);
        assertThat(jdbc.sql(
                                "select count(*) from expenses where team_id = :t and status = 'DRAFT' and claimant_id = :c")
                        .param("t", team)
                        .param("c", member.id())
                        .query(Integer.class)
                        .single())
                .isEqualTo(100);
    }

    @Test
    void moreThanHundredItemsGets400AndCreatesNothing() {
        String[] tooMany = new String[101];
        for (int i = 0; i < 101; i++) tooMany[i] = good(i);
        Resp r = call(member, HttpMethod.POST, batchPath(false), items(tooMany));
        assertThat(r.status()).isEqualTo(400);
        assertThat(expenseCount()).isZero();
    }

    @Test
    void anEmptyBatchGets400() {
        assertThat(call(member, HttpMethod.POST, batchPath(false), "{\"items\":[]}")
                        .status())
                .isEqualTo(400);
    }

    @Test
    void invalidItemsAreRejectedIndividuallyAndTheRestAreCreated() {
        Resp r = call(
                member,
                HttpMethod.POST,
                batchPath(false),
                items(
                        good(0),
                        item("12.345", "EUR", LocalDate.now().toString(), "too many decimals"),
                        good(2),
                        item("1.00", "EUR", LocalDate.now().plusDays(3).toString(), "future"),
                        item("1.00", "EUR", LocalDate.now().toString(), "   "),
                        item("abc", "EUR", LocalDate.now().toString(), "not a number"),
                        item("1.00", "ZZZ", LocalDate.now().toString(), "no such currency"),
                        good(7)));
        assertThat(r.status()).isEqualTo(200);
        JsonNode body = r.json();
        assertThat(body.get("created").asInt()).isEqualTo(3);
        assertThat(body.get("rejected").asInt()).isEqualTo(5);
        List<String> statuses = new ArrayList<>();
        body.get("results").forEach(x -> statuses.add(x.get("status").asString()));
        assertThat(statuses)
                .containsExactly(
                        "CREATED", "REJECTED", "CREATED", "REJECTED", "REJECTED", "REJECTED", "REJECTED", "CREATED");
        JsonNode results = body.get("results");
        for (int i = 0; i < results.size(); i++) {
            assertThat(results.get(i).get("index").asInt()).isEqualTo(i);
        }
        assertThat(results.get(1).get("problem").get("field").asString()).isEqualTo("money");
        assertThat(results.get(3).get("problem").get("field").asString()).isEqualTo("incurredOn");
        assertThat(results.get(4).get("problem").get("field").asString()).isEqualTo("merchant");
        assertThat(results.get(5).get("problem").get("field").asString()).isEqualTo("money");
        assertThat(results.get(6).get("problem").get("field").asString()).isEqualTo("money");
        assertThat(results.get(1).path("id").isNull()
                        || results.get(1).path("id").isMissingNode())
                .isTrue();
        assertThat(expenseCount()).isEqualTo(3);
    }

    // --- batch: atomic ----------------------------------------------------------------------------------------

    @Test
    void anAtomicBatchWithOneBadItemCreatesNothing() {
        Resp r = call(
                member,
                HttpMethod.POST,
                batchPath(true),
                items(
                        good(0),
                        good(1),
                        item("1.00", "EUR", LocalDate.now().plusDays(1).toString(), "future"),
                        good(3)));
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.json().get("code").asString()).isEqualTo("BATCH_REJECTED");
        assertThat(r.json().get("errors").size()).isEqualTo(1);
        assertThat(r.json().get("errors").get(0).get("field").asString()).isEqualTo("items[2].incurredOn");
        assertThat(expenseCount()).isZero();
    }

    @Test
    void anAtomicBatchListsEveryProblem() {
        Resp r = call(
                member,
                HttpMethod.POST,
                batchPath(true),
                items(
                        item("x", "EUR", LocalDate.now().toString(), "a"),
                        good(1),
                        item("1.00", "EUR", LocalDate.now().toString(), " ")));
        assertThat(r.status()).isEqualTo(422);
        List<String> fields = new ArrayList<>();
        r.json().get("errors").forEach(e -> fields.add(e.get("field").asString()));
        assertThat(fields).containsExactly("items[0].money", "items[2].merchant");
        assertThat(expenseCount()).isZero();
    }

    @Test
    void anAtomicBatchOfValidItemsCreatesAll() {
        Resp r = call(member, HttpMethod.POST, batchPath(true), items(good(0), good(1), good(2)));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("created").asInt()).isEqualTo(3);
        assertThat(expenseCount()).isEqualTo(3);
    }

    // --- batch: abuse -------------------------------------------------------------------------------------------

    @Test
    void anItemCannotSetStatusOrClaimant_API3() {
        String sneaky = "{\"money\":{\"amount\":\"1.00\",\"currency\":\"EUR\"},\"category\":\"MEALS\",\"incurredOn\":\""
                + LocalDate.now() + "\",\"merchant\":\"x\",\"status\":\"APPROVED\",\"claimantId\":\"" + other.id()
                + "\"}";
        Resp r = call(member, HttpMethod.POST, batchPath(false), items(sneaky));
        assertThat(r.status()).isEqualTo(400);
        assertThat(expenseCount()).isZero();
    }

    @Test
    void aNonTeammateGets404AndAnonymousGets401() {
        assertThat(call(outsider, HttpMethod.POST, batchPath(false), items(good(0)))
                        .status())
                .isEqualTo(404);
        assertThat(call(null, HttpMethod.POST, batchPath(false), items(good(0))).status())
                .isEqualTo(401);
        assertThat(expenseCount()).isZero();
    }

    @Test
    void aHugeJsonBodyIsRefusedWith413BeforeItIsParsed_API4() {
        String padding = "x".repeat(2 * 1024 * 1024);
        Resp r = call(
                member,
                HttpMethod.POST,
                batchPath(false),
                items(item("1.00", "EUR", LocalDate.now().toString(), padding)));
        assertThat(r.status()).isEqualTo(413);
        assertThat(r.json().get("code").asString()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(expenseCount()).isZero();
    }

    @Test
    void theBodyLimitAppliesBeforeAuthenticationToo() {
        String padding = "x".repeat(2 * 1024 * 1024);
        Resp r = call(null, HttpMethod.POST, batchPath(false), "{\"pad\":\"" + padding + "\"}");
        assertThat(r.status()).isEqualTo(413);
    }

    // --- export -----------------------------------------------------------------------------------------------------

    UUID seed(UUID claimant, String merchant, String status, String currency, int offset) {
        UUID id = seeder.insert(
                team,
                claimant,
                "10.00",
                currency,
                "MEALS",
                status,
                LocalDate.of(2026, 1, 1).plusDays(offset % 300),
                Instant.now());
        jdbc.sql("update expenses set merchant = :m where id = :id")
                .param("m", merchant)
                .param("id", id)
                .update();
        return id;
    }

    Resp export(TestUser user, String query) {
        return call(user, HttpMethod.GET, "/api/v1/teams/" + team + "/expenses.csv" + query, null);
    }

    static List<String> lines(String csv) {
        return List.of(csv.split("\r\n"));
    }

    @Test
    void exportReturnsAnAttachmentWithHeaderAndRows() {
        seed(member.id(), "Cafe Central", "DRAFT", "EUR", 1);
        Resp r = export(approver, "");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.headers().getContentType().toString()).startsWith("text/csv");
        assertThat(r.headers().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment")
                .contains("expenses.csv");
        assertThat(r.headers().getFirst("X-Total-Count")).isEqualTo("1");
        assertThat(r.headers().getFirst("X-Truncated")).isEqualTo("false");
        List<String> lines = lines(r.text());
        assertThat(lines.getFirst())
                .isEqualTo("id,incurredOn,merchant,amount,currency,category,status,claimantId,note");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(1)).contains("Cafe Central").contains(",10.00,EUR,MEALS,DRAFT," + member.id());
    }

    @Test
    void formulaCellsAreNeutralisedAndSpecialCharactersAreQuoted_API10() {
        seed(member.id(), "=HYPERLINK(\"http://evil.test\",\"click\")", "DRAFT", "EUR", 1);
        seed(member.id(), "+1+1", "DRAFT", "EUR", 2);
        seed(member.id(), "-2+3", "DRAFT", "EUR", 3);
        seed(member.id(), "@SUM(A1)", "DRAFT", "EUR", 4);
        seed(member.id(), "\tTabbed", "DRAFT", "EUR", 5);
        seed(member.id(), "Smith, Jones & Co", "DRAFT", "EUR", 6);
        String csv = export(approver, "").text();
        // No data line may start a cell with a formula character.
        assertThat(csv).contains("'=HYPERLINK(\"\"http://evil.test\"\",\"\"click\"\")");
        assertThat(csv)
                .contains(",'+1+1,")
                .contains(",'-2+3,")
                .contains(",'@SUM(A1),")
                .contains(",'\tTabbed,");
        assertThat(csv).contains("\"Smith, Jones & Co\"");
        for (String line : lines(csv).subList(1, lines(csv).size())) {
            for (String dangerous : List.of(",=", ",+", ",-", ",@", ",\t")) {
                assertThat(line).as(line).doesNotContain(dangerous);
            }
        }
    }

    @Test
    void aMemberExportsOnlyTheirOwnExpenses_API1() {
        seed(member.id(), "mine", "DRAFT", "EUR", 1);
        seed(other.id(), "theirs", "DRAFT", "EUR", 2);
        String mine = export(member, "?claimantId=" + other.id()).text();
        assertThat(mine).contains("mine").doesNotContain("theirs");
        assertThat(export(approver, "").text()).contains("mine").contains("theirs");
    }

    @Test
    void exportUsesTheSameFiltersAsTheList() {
        seed(member.id(), "draft-eur", "DRAFT", "EUR", 1);
        seed(member.id(), "sub-eur", "SUBMITTED", "EUR", 2);
        seed(member.id(), "sub-usd", "SUBMITTED", "USD", 3);
        String csv = export(approver, "?status=SUBMITTED&currency=EUR").text();
        assertThat(csv).contains("sub-eur").doesNotContain("draft-eur").doesNotContain("sub-usd");
    }

    @Test
    void exportStreamsManyPagesWithoutRepeatsOrGaps() {
        Instant now = Instant.now();
        var expected = new HashSet<UUID>();
        for (int i = 0; i < 1150; i++) {
            expected.add(seeder.insert(
                    team,
                    member.id(),
                    "1.00",
                    "EUR",
                    "MEALS",
                    "DRAFT",
                    LocalDate.of(2026, 1, 1).plusDays(i % 200),
                    now));
        }
        Resp r = export(approver, "");
        assertThat(r.headers().getFirst("X-Total-Count")).isEqualTo("1150");
        assertThat(r.headers().getFirst("X-Truncated")).isEqualTo("false");
        List<String> lines = lines(r.text());
        assertThat(lines).hasSize(1151);
        var seen = new HashSet<UUID>();
        lines.subList(1, lines.size()).forEach(l -> seen.add(UUID.fromString(l.substring(0, 36))));
        assertThat(seen).isEqualTo(expected);
    }

    @Test
    void exportIsCappedAndSaysSo() {
        Instant now = Instant.now();
        for (int i = 0; i < 1300; i++) {
            seeder.insert(team, member.id(), "1.00", "EUR", "MEALS", "DRAFT", LocalDate.of(2026, 1, 1), now);
        }
        Resp r = export(approver, "");
        assertThat(r.headers().getFirst("X-Total-Count")).isEqualTo("1300");
        assertThat(r.headers().getFirst("X-Truncated")).isEqualTo("true");
        assertThat(lines(r.text())).hasSize(1201); // header + the 1,200 cap configured for this test
    }

    @Test
    void exportOfNothingIsJustTheHeader() {
        assertThat(lines(export(approver, "").text())).hasSize(1);
    }

    @Test
    void exportAuthorizationAndValidation() {
        assertThat(export(outsider, "").status()).isEqualTo(404);
        assertThat(export(null, "").status()).isEqualTo(401);
        assertThat(export(approver, "?currency=ZZZ").status()).isEqualTo(400);
        assertThat(export(approver, "?currency=eur").status()).isEqualTo(400);
        assertThat(export(approver, "?status=BOGUS").status()).isEqualTo(400);
        assertThat(export(approver, "?incurredFrom=2026-02-01&incurredTo=2026-01-01")
                        .status())
                .isEqualTo(400);
    }
}
