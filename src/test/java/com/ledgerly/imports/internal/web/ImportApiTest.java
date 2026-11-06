package com.ledgerly.imports.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerly.MutableClock;
import com.ledgerly.TeamSeeder;
import com.ledgerly.TestUsers;
import com.ledgerly.TestUsers.TestUser;
import com.ledgerly.TestcontainersConfiguration;
import com.ledgerly.expenses.ExpensesFacade;
import com.ledgerly.expenses.NewExpense;
import com.ledgerly.expenses.ValidationProblem;
import com.ledgerly.identity.internal.application.AccessTokenIssuer;
import com.ledgerly.imports.internal.infrastructure.ImportRunner;
import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@Import({TestcontainersConfiguration.class, ImportApiTest.Flaky.class})
class ImportApiTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String HEADER = "date,merchant,amount,currency,category,note";

    /** Wraps the real facade so a test can make one {@code createDrafts} call fail, like a database outage. */
    static class FlakyFacade implements ExpensesFacade {
        final ExpensesFacade real;
        final AtomicInteger calls = new AtomicInteger();
        volatile int failOnCall;

        FlakyFacade(ExpensesFacade real) {
            this.real = real;
        }

        @Override
        public void requireCanView(TeamId t, ExpenseId e, UserId u) {
            real.requireCanView(t, e, u);
        }

        @Override
        public void requireCanChangeReceipts(TeamId t, ExpenseId e, UserId u) {
            real.requireCanChangeReceipts(t, e, u);
        }

        @Override
        public Optional<ValidationProblem> validate(NewExpense expense) {
            return real.validate(expense);
        }

        @Override
        public List<ExpenseId> createDrafts(TeamId team, UserId claimant, List<NewExpense> expenses) {
            if (calls.incrementAndGet() == failOnCall) {
                throw new IllegalStateException("simulated outage");
            }
            return real.createDrafts(team, claimant, expenses);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Flaky {
        @Bean
        @Primary
        FlakyFacade flakyFacade(@Qualifier("expensesFacadeImpl") ExpensesFacade real) {
            return new FlakyFacade(real);
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AccessTokenIssuer issuer;

    @Autowired
    MutableClock clock;

    @Autowired
    FlakyFacade flaky;

    @Autowired
    ImportRunner runner;

    @Autowired
    CapturedImportEvents events;

    @Autowired
    org.springframework.context.ApplicationEventPublisher publisher;

    @Autowired
    org.springframework.transaction.support.TransactionTemplate transactions;

    RestTestClient client;
    TestUsers users;
    UUID team;
    TestUser importer;
    TestUser owner;
    TestUser other;
    TestUser approver;
    TestUser outsider;

    @BeforeEach
    void setUp() {
        // The JDK client does not retry. Apache HttpClient (on the classpath through Testcontainers) honours
        // "Retry-After" on a 429 and would quietly wait for hours before repeating the request.
        client = RestTestClient.bindToServer(new org.springframework.http.client.JdkClientHttpRequestFactory())
                .baseUrl("http://localhost:" + port)
                .build();
        users = new TestUsers(jdbc, issuer);
        team = UUID.randomUUID();
        importer = users.create(true);
        owner = users.create(true);
        other = users.create(true);
        approver = users.create(true);
        outsider = users.create(true);
        var teams = new TeamSeeder(jdbc);
        teams.member(team, importer.id(), "MEMBER");
        teams.member(team, owner.id(), "OWNER");
        teams.member(team, other.id(), "MEMBER");
        teams.member(team, approver.id(), "APPROVER");
        flaky.calls.set(0);
        flaky.failOnCall = 0;
    }

    @AfterEach
    void reset() {
        clock.reset();
        flaky.failOnCall = 0;
    }

    record Resp(int status, HttpHeaders headers, JsonNode body) {
        String id() {
            return body.get("id").asString();
        }
    }

    Resp upload(TestUser user, UUID forTeam, byte[] bytes) {
        var builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(bytes))
                .filename("expenses.csv")
                .contentType(MediaType.parseMediaType("text/csv"));
        var result = client.post()
                .uri("/api/v1/teams/" + forTeam + "/imports")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .headers(h -> {
                    if (user != null) h.add("Authorization", user.bearer());
                })
                .body(builder.build())
                .exchange()
                .returnResult(String.class);
        return resp(result.getStatus().value(), result.getResponseHeaders(), result.getResponseBody());
    }

    Resp upload(TestUser user, String csv) {
        return upload(user, team, csv.getBytes(StandardCharsets.UTF_8));
    }

    Resp get(TestUser user, UUID forTeam, String importId) {
        var result = client.method(HttpMethod.GET)
                .uri("/api/v1/teams/" + forTeam + "/imports/" + importId)
                .headers(h -> {
                    if (user != null) h.add("Authorization", user.bearer());
                })
                .exchange()
                .returnResult(String.class);
        return resp(result.getStatus().value(), result.getResponseHeaders(), result.getResponseBody());
    }

    static Resp resp(int status, HttpHeaders headers, String text) {
        return new Resp(
                status, headers, text == null || text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text));
    }

    Resp awaitFinished(TestUser user, String importId) {
        return await().atMost(Duration.ofSeconds(90))
                .pollInterval(Duration.ofMillis(100))
                .until(
                        () -> get(user, team, importId),
                        r -> r.status() == 200
                                && Set.of("COMPLETED", "FAILED")
                                        .contains(r.body().get("status").asString()));
    }

    static String csv(String... rows) {
        return HEADER + "\n" + String.join("\n", rows) + "\n";
    }

    static String goodRow(int n) {
        return "2026-01-" + String.format("%02d", 1 + n % 28) + ",Merchant " + n + ",12.50,EUR,MEALS,";
    }

    int expenseCount() {
        return jdbc.sql("select count(*) from expenses where team_id = :t")
                .param("t", team)
                .query(Integer.class)
                .single();
    }

    // --- the big one -------------------------------------------------------------------------------------------

    @Test
    void aTenThousandRowImportWithThirtySevenBadRowsReportsExactlyThoseLines() {
        String[] bad = {
            "2026-01-05,Cafe,abc,EUR,MEALS,",
            "2026-01-05,Cafe,1.234,EUR,MEALS,",
            "2026-13-45,Cafe,5.00,EUR,MEALS,",
            "2999-01-01,Cafe,5.00,EUR,MEALS,",
            "2026-01-05,Cafe,5.00,ZZZ,MEALS,",
            "2026-01-05,Cafe,5.00,EUR,FOOD,",
            "2026-01-05,,5.00,EUR,MEALS,",
            "2026-01-05,only,three",
            "2026-01-05,Cafe,5.00,EUR,MEALS,note,extra",
            "2026-01-05," + "m".repeat(201) + ",5.00,EUR,MEALS,",
            "2026-01-05,Cafe,-5.00,EUR,MEALS,",
            "2026-01-05,Cafe,0,EUR,MEALS,",
        };
        var expectedLines = new TreeSet<Integer>();
        var csv = new StringBuilder(HEADER).append('\n');
        int badCount = 0;
        for (int i = 1; i <= 10_000; i++) {
            // Every 270th row from the first is bad: 37 rows in all, cycling through the kinds of defect.
            if ((i - 1) % 270 == 0 && badCount < 37) {
                csv.append(bad[badCount % bad.length]).append('\n');
                expectedLines.add(i + 1); // the header is line 1
                badCount++;
            } else {
                csv.append(goodRow(i)).append('\n');
            }
        }
        assertThat(badCount).isEqualTo(37);

        Resp queued = upload(importer, csv.toString());
        assertThat(queued.status()).isEqualTo(202);
        assertThat(queued.headers().getLocation().getPath())
                .isEqualTo("/api/v1/teams/" + team + "/imports/" + queued.id());
        assertThat(queued.body().get("totalRows").asInt()).isEqualTo(10_000);

        Resp done = awaitFinished(importer, queued.id());
        assertThat(done.body().get("status").asString()).isEqualTo("COMPLETED");
        assertThat(done.body().get("totalRows").asInt()).isEqualTo(10_000);
        assertThat(done.body().get("importedRows").asInt()).isEqualTo(9_963);
        assertThat(done.body().get("skippedRows").asInt()).isEqualTo(37);
        assertThat(done.body().get("errorsTruncated").asBoolean()).isFalse();

        var reported = new TreeSet<Integer>();
        done.body().get("errors").forEach(e -> reported.add(e.get("line").asInt()));
        assertThat(reported).isEqualTo(expectedLines);

        assertThat(expenseCount()).isEqualTo(9_963);
        assertThat(jdbc.sql(
                                "select count(*) from expenses where team_id = :t and status = 'DRAFT' and claimant_id = :c")
                        .param("t", team)
                        .param("c", importer.id())
                        .query(Integer.class)
                        .single())
                .isEqualTo(9_963);
        assertThat(events.completed)
                .anyMatch(e -> e.importId().toString().equals(queued.id()) && e.importedRows() == 9_963 && !e.failed());
    }

    @Test
    void errorsNameTheKindOfProblemAndTheLine() {
        Resp queued = upload(
                importer,
                csv(goodRow(1), "2026-01-05,Cafe,abc,EUR,MEALS,", "2026-13-45,Cafe,5.00,EUR,MEALS,", goodRow(4)));
        Resp done = awaitFinished(importer, queued.id());
        assertThat(done.body().get("importedRows").asInt()).isEqualTo(2);
        var errors = done.body().get("errors");
        assertThat(errors.get(0).get("line").asInt()).isEqualTo(3);
        assertThat(errors.get(0).get("code").asString()).isEqualTo("AMOUNT_INVALID");
        assertThat(errors.get(1).get("line").asInt()).isEqualTo(4);
        assertThat(errors.get(1).get("code").asString()).isEqualTo("DATE_INVALID");
    }

    @Test
    void quotesBlankLinesCrlfAndABomAreHandled() {
        String content = "﻿" + HEADER + "\r\n"
                + "2026-01-02,\"Smith, Jones & Co\",20.00,EUR,OFFICE,\"a note, with comma\"\r\n"
                + "\r\n"
                + "2026-01-03,=cmd|' /C calc'!A0,5.00,eur,travel,\r\n";
        Resp queued = upload(importer, content);
        assertThat(queued.status()).isEqualTo(202);
        assertThat(queued.body().get("totalRows").asInt()).isEqualTo(2); // the blank line is not a row
        Resp done = awaitFinished(importer, queued.id());
        assertThat(done.body().get("importedRows").asInt()).isEqualTo(2);
        assertThat(jdbc.sql("select merchant from expenses where team_id = :t order by merchant")
                        .param("t", team)
                        .query(String.class)
                        .list())
                // Stored as plain text; the export neutralises it (API10).
                .containsExactly("=cmd|' /C calc'!A0", "Smith, Jones & Co");
        assertThat(jdbc.sql("select note from expenses where team_id = :t and merchant like 'Smith%'")
                        .param("t", team)
                        .query(String.class)
                        .single())
                .isEqualTo("a note, with comma");
    }

    @Test
    void aLineWithAnUnbalancedQuoteIsReportedAndTheRestStillImports() {
        Resp queued = upload(importer, csv(goodRow(1), "2026-01-05,\"unterminated,5.00,EUR,MEALS,", goodRow(3)));
        Resp done = awaitFinished(importer, queued.id());
        assertThat(done.body().get("status").asString()).isEqualTo("COMPLETED");
        assertThat(done.body().get("importedRows").asInt()).isEqualTo(2);
        assertThat(done.body().get("errors").get(0).get("line").asInt()).isEqualTo(3);
    }

    // --- limits (API4, API6) -----------------------------------------------------------------------------------------

    @Test
    void moreThanTenThousandRowsGets400AndNothingIsQueued() {
        var csv = new StringBuilder(HEADER).append('\n');
        for (int i = 1; i <= 10_001; i++) csv.append(goodRow(i)).append('\n');
        Resp r = upload(importer, csv.toString());
        assertThat(r.status()).isEqualTo(400);
        assertThat(jdbc.sql("select count(*) from import_jobs where team_id = :t")
                        .param("t", team)
                        .query(Integer.class)
                        .single())
                .isZero();
        assertThat(expenseCount()).isZero();
    }

    @Test
    void exactlyTenThousandRowsIsAccepted() {
        var csv = new StringBuilder(HEADER).append('\n');
        for (int i = 1; i <= 10_000; i++) csv.append(goodRow(i)).append('\n');
        assertThat(upload(importer, csv.toString()).status()).isEqualTo(202);
    }

    @Test
    void aSixthImportOfTheDayGets429WithRetryAfter_API6() {
        for (int i = 0; i < 5; i++) {
            assertThat(upload(importer, csv(goodRow(i))).status()).isEqualTo(202);
        }
        Resp sixth = upload(importer, csv(goodRow(9)));
        assertThat(sixth.status()).isEqualTo(429);
        assertThat(sixth.body().get("code").asString()).isEqualTo("RATE_LIMITED");
        long retryAfter = Long.parseLong(sixth.headers().getFirst(HttpHeaders.RETRY_AFTER));
        assertThat(retryAfter).isBetween(1L, 86_400L);
        assertThat(jdbc.sql("select count(*) from import_jobs where team_id = :t")
                        .param("t", team)
                        .query(Integer.class)
                        .single())
                .isEqualTo(5);
    }

    @Test
    void theLimitIsPerUserAndPerTeamAndResetsTomorrow() {
        for (int i = 0; i < 5; i++) upload(importer, csv(goodRow(i)));
        assertThat(upload(importer, csv(goodRow(9))).status()).isEqualTo(429);

        // Another user of the same team has their own allowance.
        assertThat(upload(other, csv(goodRow(1))).status()).isEqualTo(202);

        // The same user in another team too.
        UUID secondTeam = UUID.randomUUID();
        new TeamSeeder(jdbc).member(secondTeam, importer.id(), "MEMBER");
        assertThat(upload(importer, secondTeam, csv(goodRow(1)).getBytes(StandardCharsets.UTF_8))
                        .status())
                .isEqualTo(202);

        // "Today" comes from the Clock: a day later the allowance is back.
        clock.advance(Duration.ofHours(25));
        TestUser tomorrow = new TestUser(importer.id(), importer.email(), issuer.issue(importer.id()));
        assertThat(upload(tomorrow, csv(goodRow(1))).status()).isEqualTo(202);
    }

    @Test
    void eightSimultaneousUploadsLetExactlyFiveThrough() throws Exception {
        var gate = new CyclicBarrier(8);
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<java.util.concurrent.Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                int n = i;
                Callable<Integer> task = () -> {
                    gate.await();
                    return upload(importer, csv(goodRow(n))).status();
                };
                results.add(pool.submit(task));
            }
            List<Integer> statuses = new ArrayList<>();
            for (var f : results) statuses.add(f.get());
            assertThat(statuses.stream().filter(s -> s == 202).count()).isEqualTo(5);
            assertThat(statuses.stream().filter(s -> s == 429).count()).isEqualTo(3);
        }
    }

    @Test
    void rejectedUploadsDoNotUseUpTheDailyAllowance() {
        for (int i = 0; i < 6; i++) {
            assertThat(upload(importer, "not,the,right,header\n1,2,3,4\n").status())
                    .isEqualTo(400);
        }
        assertThat(upload(importer, csv(goodRow(1))).status()).isEqualTo(202);
    }

    // --- bad files
    // -----------------------------------------------------------------------------------------------------

    @Test
    void wrongHeaderEmptyAndHeaderOnlyFilesGet400() {
        assertThat(upload(importer, "a,b,c\n1,2,3\n").status()).isEqualTo(400);
        assertThat(upload(importer, "").status()).isEqualTo(400);
        assertThat(upload(importer, HEADER + "\n").status()).isEqualTo(400);
        assertThat(upload(importer, "date,merchant,amount,currency\n2026-01-01,x,1,EUR\n")
                        .status())
                .isEqualTo(400);
    }

    @Test
    void theNoteColumnIsOptional() {
        Resp r = upload(importer, "date,merchant,amount,currency,category\n2026-01-02,Cafe,9.90,EUR,MEALS\n");
        assertThat(r.status()).isEqualTo(202);
        assertThat(awaitFinished(importer, r.id()).body().get("importedRows").asInt())
                .isEqualTo(1);
    }

    @Test
    void binaryFilesGet415() {
        byte[] exe = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 4, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 0, 0};
        assertThat(upload(importer, team, exe).status()).isEqualTo(415);
        byte[] notUtf8 = (HEADER + "\n2026-01-01,Café,1.00,EUR,MEALS,\n").getBytes(StandardCharsets.ISO_8859_1);
        assertThat(upload(importer, team, notUtf8).status()).isEqualTo(415);
    }

    @Test
    void aFileOverTenMegabytesGets413() {
        byte[] big = new byte[10 * 1024 * 1024 + 1];
        Arrays.fill(big, (byte) 'a');
        assertThat(upload(importer, team, big).status()).isEqualTo(413);
    }

    // --- authorization (API1)
    // ---------------------------------------------------------------------------------------------

    @Test
    void onlyTeammatesCanImportAndAnonymousCannot() {
        assertThat(upload(outsider, csv(goodRow(1))).status()).isEqualTo(404);
        assertThat(upload(null, csv(goodRow(1))).status()).isEqualTo(401);
    }

    @Test
    void anImportIsVisibleToItsStarterAndToOwnersOnly() {
        Resp queued = upload(importer, csv(goodRow(1)));
        awaitFinished(importer, queued.id());
        assertThat(get(importer, team, queued.id()).status()).isEqualTo(200);
        assertThat(get(owner, team, queued.id()).status()).isEqualTo(200);
        assertThat(get(other, team, queued.id()).status()).isEqualTo(404);
        assertThat(get(approver, team, queued.id()).status()).isEqualTo(404);
        assertThat(get(outsider, team, queued.id()).status()).isEqualTo(404);
        assertThat(get(null, team, queued.id()).status()).isEqualTo(401);
        assertThat(get(importer, team, UUID.randomUUID().toString()).status()).isEqualTo(404);
    }

    @Test
    void anImportIsNotReachableThroughAnotherTeam() {
        Resp queued = upload(importer, csv(goodRow(1)));
        UUID otherTeam = UUID.randomUUID();
        TestUser spy = users.create(true);
        new TeamSeeder(jdbc).member(otherTeam, spy.id(), "OWNER");
        assertThat(get(spy, otherTeam, queued.id()).status()).isEqualTo(404);
    }

    // --- restart
    // ------------------------------------------------------------------------------------------------------------

    @Test
    void aFailedImportRestartsFromTheLastCommittedChunkWithoutDuplicates() {
        String[] rows = new String[450];
        for (int i = 0; i < 450; i++) rows[i] = goodRow(i);
        flaky.failOnCall = 3; // the third chunk (rows 201-300) fails once

        Resp queued = upload(importer, csv(rows));
        Resp failed = awaitFinished(importer, queued.id());
        assertThat(failed.body().get("status").asString()).isEqualTo("FAILED");
        // Chunks one and two were committed; the failed third one was rolled back as a whole.
        assertThat(failed.body().get("importedRows").asInt()).isEqualTo(200);
        assertThat(expenseCount()).isEqualTo(200);
        assertThat(events.completed).anyMatch(e -> e.importId().toString().equals(queued.id()) && e.failed());

        flaky.failOnCall = 0;
        assertThat(runner.restart(UUID.fromString(queued.id()))).isTrue();
        Resp done = awaitFinished(importer, queued.id());
        assertThat(done.body().get("status").asString()).isEqualTo("COMPLETED");
        assertThat(done.body().get("importedRows").asInt()).isEqualTo(450);
        assertThat(expenseCount()).isEqualTo(450);
        // No expense was written twice.
        assertThat(jdbc.sql("select count(distinct merchant) from expenses where team_id = :t")
                        .param("t", team)
                        .query(Integer.class)
                        .single())
                .isEqualTo(450);
    }

    @Test
    void aCompletedImportCannotBeRestarted() {
        Resp queued = upload(importer, csv(goodRow(1)));
        awaitFinished(importer, queued.id());
        assertThat(runner.restart(UUID.fromString(queued.id()))).isFalse();
        assertThat(runner.restart(UUID.randomUUID())).isFalse();
    }

    @Test
    void errorsAlreadyReportedBeforeAFailureAreNotDuplicatedOnRestart() {
        String[] rows = new String[250];
        for (int i = 0; i < 250; i++) rows[i] = goodRow(i);
        rows[5] = "2026-01-05,Cafe,abc,EUR,MEALS,"; // line 7, in the first chunk
        flaky.failOnCall = 2;
        Resp queued = upload(importer, csv(rows));
        assertThat(awaitFinished(importer, queued.id()).body().get("status").asString())
                .isEqualTo("FAILED");
        flaky.failOnCall = 0;
        runner.restart(UUID.fromString(queued.id()));
        Resp done = awaitFinished(importer, queued.id());
        assertThat(done.body().get("status").asString()).isEqualTo("COMPLETED");
        assertThat(done.body().get("skippedRows").asInt()).isEqualTo(1);
        assertThat(done.body().get("importedRows").asInt()).isEqualTo(249);
        assertThat(expenseCount()).isEqualTo(249);
        Set<Integer> lines = new HashSet<>();
        done.body().get("errors").forEach(e -> lines.add(e.get("line").asInt()));
        assertThat(lines).containsExactly(7);
    }

    // --- the event registry (M9)
    // ------------------------------------------------------------------------------------------

    @Test
    void deliveringTheQueuedEventAgainDoesNotRunACompletedImportTwice() {
        Resp queued = upload(importer, csv(goodRow(1), goodRow(2), goodRow(3)));
        Resp done = awaitFinished(importer, queued.id());
        assertThat(done.body().get("importedRows").asInt()).isEqualTo(3);

        // What the registry does after a restart for an event it still has on record.
        transactions.executeWithoutResult(status -> publisher.publishEvent(
                new com.ledgerly.imports.internal.application.ImportQueued(UUID.fromString(queued.id()))));

        // Give the (asynchronous) listener time to run, then check nothing was imported again.
        await().pollDelay(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(expenseCount()).isEqualTo(3);
            assertThat(get(importer, team, queued.id()).body().get("status").asString())
                    .isEqualTo("COMPLETED");
        });
    }
}
