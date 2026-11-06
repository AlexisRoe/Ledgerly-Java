package com.ledgerly.receipts.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerly.ExpenseSeeder;
import com.ledgerly.TeamSeeder;
import com.ledgerly.TestUsers;
import com.ledgerly.TestUsers.TestUser;
import com.ledgerly.TestcontainersConfiguration;
import com.ledgerly.identity.internal.application.AccessTokenIssuer;
import com.ledgerly.receipts.internal.application.FakeMalwareScanner;
import com.ledgerly.receipts.internal.application.FileSystemReceiptStorage;
import com.ledgerly.receipts.internal.application.ScanReceipt;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
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
@Import(TestcontainersConfiguration.class)
class ReceiptsApiTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    static final byte[] PDF =
            "%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0};
    static final byte[] EXE = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 4, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 0, 0};

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AccessTokenIssuer issuer;

    @Autowired
    FileSystemReceiptStorage storage;

    @Autowired
    FakeMalwareScanner scanner;

    @Autowired
    ScanReceipt scanReceipt;

    RestTestClient client;
    TestUsers users;
    ExpenseSeeder expenses;
    UUID team;
    TestUser claimant;
    TestUser colleague; // another MEMBER
    TestUser approver;
    TestUser outsider;

    @BeforeEach
    void setUp() {
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
        users = new TestUsers(jdbc, issuer);
        expenses = new ExpenseSeeder(jdbc);
        team = UUID.randomUUID();
        claimant = users.create(true);
        colleague = users.create(true);
        approver = users.create(true);
        outsider = users.create(true);
        var teams = new TeamSeeder(jdbc);
        teams.member(team, claimant.id(), "MEMBER");
        teams.member(team, colleague.id(), "MEMBER");
        teams.member(team, approver.id(), "APPROVER");
    }

    @AfterEach
    void restoreScanner() {
        scanner.unavailable = false;
    }

    record Resp(int status, HttpHeaders headers, JsonNode body) {
        String id() {
            return body.get("id").asString();
        }

        String code() {
            return body.get("code").asString();
        }
    }

    UUID expense(String status) {
        return expenses.insert(team, claimant.id(), "10.00", "EUR", "MEALS", status, LocalDate.now(), Instant.now());
    }

    String base(UUID expense) {
        return "/api/v1/teams/" + team + "/expenses/" + expense + "/receipts";
    }

    Resp upload(TestUser user, UUID expense, String filename, byte[] bytes, String declaredType) {
        return uploadTo(user, base(expense), filename, bytes, declaredType);
    }

    Resp uploadTo(TestUser user, String path, String filename, byte[] bytes, String declaredType) {
        var builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(bytes))
                .filename(filename)
                .contentType(MediaType.parseMediaType(declaredType));
        var spec = client.post()
                .uri(path)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .headers(h -> {
                    if (user != null) h.add("Authorization", user.bearer());
                });
        var result = spec.body(builder.build()).exchange().returnResult(String.class);
        return toResp(result.getStatus().value(), result.getResponseHeaders(), result.getResponseBody());
    }

    Resp get(TestUser user, String path) {
        return call(user, HttpMethod.GET, path);
    }

    Resp call(TestUser user, HttpMethod method, String path) {
        var result = client.method(method)
                .uri(path)
                .headers(h -> {
                    if (user != null) h.add("Authorization", user.bearer());
                })
                .exchange()
                .returnResult(String.class);
        return toResp(result.getStatus().value(), result.getResponseHeaders(), result.getResponseBody());
    }

    static Resp toResp(int status, HttpHeaders headers, String text) {
        return new Resp(
                status, headers, text == null || text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text));
    }

    String statusOf(UUID expense, String receiptId) {
        Resp list = get(claimant, base(expense));
        for (JsonNode r : list.body()) {
            if (r.get("id").asString().equals(receiptId)) return r.get("status").asString();
        }
        return "GONE";
    }

    void awaitStatus(UUID expense, String receiptId, String expected) {
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(statusOf(expense, receiptId)).isEqualTo(expected));
    }

    String uploaded(UUID expense) {
        Resp r = upload(claimant, expense, "lunch.pdf", PDF, "application/pdf");
        assertThat(r.status()).isEqualTo(202);
        return r.id();
    }

    // --- happy path ---------------------------------------------------------------------------

    @Test
    void uploadIsAcceptedThenScannedAndBecomesDownloadable() {
        UUID e = expense("DRAFT");
        Resp r = upload(claimant, e, "lunch receipt.pdf", PDF, "application/pdf");
        assertThat(r.status()).isEqualTo(202);
        assertThat(r.body().get("status").asString()).isEqualTo("PENDING_SCAN");
        assertThat(r.body().get("detectedType").asString()).isEqualTo("application/pdf");
        assertThat(r.body().get("sizeBytes").asLong()).isEqualTo(PDF.length);
        assertThat(r.body().get("possibleDuplicate").asBoolean()).isFalse();
        assertThat(r.headers().getLocation().getPath()).isEqualTo(base(e));

        awaitStatus(e, r.id(), "ACCEPTED");

        Resp link = get(claimant, base(e) + "/" + r.id() + "/download");
        assertThat(link.status()).isEqualTo(200);
        assertThat(link.body().get("url").asString()).startsWith("https://storage.test/receipts/");
        Instant expiresAt = Instant.parse(link.body().get("expiresAt").asString());
        assertThat(expiresAt).isBefore(Instant.now().plus(Duration.ofMinutes(5)).plusSeconds(5));
        assertThat(link.headers().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @Test
    void jpegAndPngAreAcceptedToo() {
        UUID e = expense("DRAFT");
        assertThat(upload(claimant, e, "a.jpg", JPEG, "image/jpeg")
                        .body()
                        .get("detectedType")
                        .asString())
                .isEqualTo("image/jpeg");
        assertThat(upload(claimant, e, "b.png", PNG, "image/png")
                        .body()
                        .get("detectedType")
                        .asString())
                .isEqualTo("image/png");
    }

    @Test
    void theTypeComesFromTheBytesNotFromTheDeclaredContentType() {
        UUID e = expense("DRAFT");
        Resp r = upload(claimant, e, "photo.png", PDF, "image/png");
        assertThat(r.status()).isEqualTo(202);
        assertThat(r.body().get("detectedType").asString()).isEqualTo("application/pdf");
    }

    // --- abuse: file content (API4, API8) -------------------------------------------------------

    @Test
    void anExeRenamedToPdfGets415() throws Exception {
        UUID e = expense("DRAFT");
        Resp r = upload(claimant, e, "invoice.pdf", EXE, "application/pdf");
        assertThat(r.status()).isEqualTo(415);
        assertThat(r.code()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        assertThat(get(claimant, base(e)).body()).isEmpty();
        assertThat(storage.objectCount()).isEqualTo(storedBefore());
    }

    private long storedBefore() throws Exception {
        return storage.objectCount();
    }

    @Test
    void plainTextAndHtmlAreRejectedWhateverTheyClaim() {
        UUID e = expense("DRAFT");
        assertThat(upload(claimant, e, "a.pdf", "hello".getBytes(StandardCharsets.UTF_8), "application/pdf")
                        .status())
                .isEqualTo(415);
        assertThat(upload(
                                claimant,
                                e,
                                "a.png",
                                "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8),
                                "image/png")
                        .status())
                .isEqualTo(415);
    }

    @Test
    void anEmptyFileGets400() {
        UUID e = expense("DRAFT");
        assertThat(upload(claimant, e, "empty.pdf", new byte[0], "application/pdf")
                        .status())
                .isEqualTo(400);
    }

    @Test
    void tenMegabytesPlusOneByteGets413() {
        UUID e = expense("DRAFT");
        byte[] big = Arrays.copyOf(PDF, 10 * 1024 * 1024 + 1);
        Resp r = upload(claimant, e, "big.pdf", big, "application/pdf");
        assertThat(r.status()).isEqualTo(413);
        assertThat(r.code()).isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    void exactlyTenMegabytesIsAccepted() {
        UUID e = expense("DRAFT");
        byte[] max = Arrays.copyOf(PDF, 10 * 1024 * 1024);
        assertThat(upload(claimant, e, "max.pdf", max, "application/pdf").status())
                .isEqualTo(202);
    }

    @Test
    void aMissingFilePartGets400() {
        UUID e = expense("DRAFT");
        var builder = new MultipartBodyBuilder();
        builder.part("other", "x");
        var result = client.post()
                .uri(base(e))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .headers(h -> h.add("Authorization", claimant.bearer()))
                .body(builder.build())
                .exchange()
                .returnResult(String.class);
        assertThat(result.getStatus().value()).isEqualTo(400);
    }

    // --- malware ---------------------------------------------------------------------------------

    @Test
    void anEicarFileIsQuarantinedAndNeverServed() throws Exception {
        UUID e = expense("DRAFT");
        byte[] eicarPdf = ("%PDF-1.4\n" + FakeMalwareScanner.EICAR).getBytes(StandardCharsets.ISO_8859_1);
        Resp r = upload(claimant, e, "invoice.pdf", eicarPdf, "application/pdf");
        assertThat(r.status()).isEqualTo(202);
        awaitStatus(e, r.id(), "QUARANTINED");

        assertThat(get(claimant, base(e) + "/" + r.id() + "/download").status()).isEqualTo(404);
        assertThat(get(approver, base(e) + "/" + r.id() + "/download").status()).isEqualTo(404);
        String key = jdbc.sql("select storage_key from receipts where id = :id")
                .param("id", UUID.fromString(r.id()))
                .query(String.class)
                .single();
        assertThat(key).startsWith("quarantine/");
        assertThat(storage.exists(key)).isTrue();
        assertThat(storage.presigned).noneMatch(p -> p.key().equals(key));
    }

    @Test
    void aReceiptThatIsStillPendingCannotBeDownloaded() {
        scanner.unavailable = true;
        UUID e = expense("DRAFT");
        String id = uploaded(e);
        assertThat(get(claimant, base(e) + "/" + id + "/download").status()).isEqualTo(404);
        assertThat(statusOf(e, id)).isEqualTo("PENDING_SCAN");
    }

    // --- the link to the submit rule (decision D3) -----------------------------------------------------

    @Test
    void submitNeedsAnAcceptedReceiptAndNoPendingOne() {
        scanner.unavailable = true;
        UUID e = expense("DRAFT");
        String submit = "/api/v1/teams/" + team + "/expenses/" + e + ":submit";

        // No receipt at all.
        assertThat(call(claimant, HttpMethod.POST, submit).status()).isEqualTo(422);

        // Uploaded but the scanner is down: still pending, still refused.
        String id = uploaded(e);
        Resp pending = call(claimant, HttpMethod.POST, submit);
        assertThat(pending.status()).isEqualTo(422);
        assertThat(pending.code()).isEqualTo("EXPENSE_RULE_VIOLATED");

        // The scanner comes back; a retry accepts it, and now submitting works.
        scanner.unavailable = false;
        scanReceipt.scan(UUID.fromString(id));
        assertThat(statusOf(e, id)).isEqualTo("ACCEPTED");
        Resp ok = call(claimant, HttpMethod.POST, submit);
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().get("status").asString()).isEqualTo("SUBMITTED");
    }

    @Test
    void aQuarantinedReceiptDoesNotCountTowardsSubmitting() {
        UUID e = expense("DRAFT");
        byte[] eicarPdf = ("%PDF-1.4\n" + FakeMalwareScanner.EICAR).getBytes(StandardCharsets.ISO_8859_1);
        Resp r = upload(claimant, e, "invoice.pdf", eicarPdf, "application/pdf");
        awaitStatus(e, r.id(), "QUARANTINED");
        assertThat(call(claimant, HttpMethod.POST, "/api/v1/teams/" + team + "/expenses/" + e + ":submit")
                        .status())
                .isEqualTo(422);
    }

    // --- names and storage keys (path traversal) -----------------------------------------------------

    @Test
    void aTraversalFileNameIsOnlyMetadata() {
        UUID e = expense("DRAFT");
        Resp r = upload(claimant, e, "../../etc/passwd", PDF, "application/pdf");
        assertThat(r.status()).isEqualTo(202);
        assertThat(r.body().get("originalName").asString()).isEqualTo("../../etc/passwd");
        String key = jdbc.sql("select storage_key from receipts where id = :id")
                .param("id", UUID.fromString(r.id()))
                .query(String.class)
                .single();
        assertThat(key)
                .matches("receipts/[0-9a-f-]{36}")
                .doesNotContain("passwd")
                .doesNotContain("..");
        awaitStatus(e, r.id(), "ACCEPTED");

        get(claimant, base(e) + "/" + r.id() + "/download");
        var link = storage.presigned.getLast();
        assertThat(link.downloadName()).isEqualTo("passwd.pdf");
        assertThat(link.ttl()).isLessThanOrEqualTo(Duration.ofMinutes(5));
        assertThat(link.contentType()).isEqualTo("application/pdf");
    }

    @Test
    void controlCharactersInTheFileNameAreDropped() {
        UUID e = expense("DRAFT");
        Resp r = upload(claimant, e, "nice\tname.pdf", PDF, "application/pdf");
        assertThat(r.body().get("originalName").asString()).isEqualTo("nicename.pdf");
    }

    // --- duplicates ------------------------------------------------------------------------------

    @Test
    void theSameFileTwiceOnOneExpenseGets409() {
        UUID e = expense("DRAFT");
        uploaded(e);
        Resp again = upload(claimant, e, "again.pdf", PDF, "application/pdf");
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("RECEIPT_DUPLICATE");
        assertThat(get(claimant, base(e)).body()).hasSize(1);
    }

    @Test
    void theSameFileOnASiblingExpenseIsFlaggedNotBlocked() {
        UUID first = expense("DRAFT");
        UUID second = expense("DRAFT");
        uploaded(first);
        Resp r = upload(claimant, second, "same.pdf", PDF, "application/pdf");
        assertThat(r.status()).isEqualTo(202);
        assertThat(r.body().get("possibleDuplicate").asBoolean()).isTrue();
    }

    @Test
    void identicalFilesInAnotherTeamAreNotFlagged() {
        UUID mine = expense("DRAFT");
        uploaded(mine);
        // Another team, another claimant, the same bytes.
        UUID otherTeam = UUID.randomUUID();
        TestUser otherClaimant = users.create(true);
        new TeamSeeder(jdbc).member(otherTeam, otherClaimant.id(), "MEMBER");
        UUID theirs = expenses.insert(
                otherTeam, otherClaimant.id(), "5.00", "EUR", "MEALS", "DRAFT", LocalDate.now(), Instant.now());
        Resp r = uploadTo(
                otherClaimant,
                "/api/v1/teams/" + otherTeam + "/expenses/" + theirs + "/receipts",
                "x.pdf",
                PDF,
                "application/pdf");
        assertThat(r.status()).isEqualTo(202);
        assertThat(r.body().get("possibleDuplicate").asBoolean()).isFalse();
    }

    @Test
    void twoSimultaneousIdenticalUploadsLeaveExactlyOne() throws Exception {
        UUID e = expense("DRAFT");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var gate = new java.util.concurrent.CyclicBarrier(2);
            java.util.concurrent.Callable<Integer> task = () -> {
                gate.await();
                return upload(claimant, e, "x.pdf", PDF, "application/pdf").status();
            };
            var a = pool.submit(task);
            var b = pool.submit(task);
            var statuses = java.util.List.of(a.get(), b.get());
            assertThat(statuses).containsExactlyInAnyOrder(202, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(get(claimant, base(e)).body()).hasSize(1);
    }

    // --- authorization (API1, API5) ---------------------------------------------------------------------

    @Test
    void uploadingToASubmittedExpenseGets409() {
        UUID e = expense("SUBMITTED");
        Resp r = upload(claimant, e, "late.pdf", PDF, "application/pdf");
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("EXPENSE_NOT_DRAFT");
    }

    @Test
    void onlyTheClaimantMayUpload() {
        UUID e = expense("DRAFT");
        assertThat(upload(approver, e, "a.pdf", PDF, "application/pdf").status())
                .isEqualTo(403);
        assertThat(upload(colleague, e, "a.pdf", PDF, "application/pdf").status())
                .isEqualTo(404);
        assertThat(upload(outsider, e, "a.pdf", PDF, "application/pdf").status())
                .isEqualTo(404);
        assertThat(upload(null, e, "a.pdf", PDF, "application/pdf").status()).isEqualTo(401);
    }

    @Test
    void aTeamBReceiptIdGets404FromTeamA_API1() {
        UUID e = expense("DRAFT");
        String id = uploaded(e);
        awaitStatus(e, id, "ACCEPTED");

        UUID otherTeam = UUID.randomUUID();
        TestUser spy = users.create(true);
        new TeamSeeder(jdbc).member(otherTeam, spy.id(), "OWNER");
        // Through the other team's path, with the right expense and receipt ids.
        String path = "/api/v1/teams/" + otherTeam + "/expenses/" + e + "/receipts/" + id + "/download";
        assertThat(get(spy, path).status()).isEqualTo(404);
        assertThat(get(spy, "/api/v1/teams/" + otherTeam + "/expenses/" + e + "/receipts")
                        .status())
                .isEqualTo(404);
    }

    @Test
    void aReceiptIdFromAnotherExpenseOfTheSameTeamGets404() {
        UUID first = expense("DRAFT");
        UUID second = expense("DRAFT");
        String id = uploaded(first);
        awaitStatus(first, id, "ACCEPTED");
        assertThat(get(claimant, base(second) + "/" + id + "/download").status())
                .isEqualTo(404);
        assertThat(call(claimant, HttpMethod.DELETE, base(second) + "/" + id).status())
                .isEqualTo(404);
    }

    @Test
    void receiptsAreVisibleLikeTheirExpense() {
        UUID e = expense("DRAFT");
        String id = uploaded(e);
        awaitStatus(e, id, "ACCEPTED");
        assertThat(get(approver, base(e)).status()).isEqualTo(200);
        assertThat(get(approver, base(e) + "/" + id + "/download").status()).isEqualTo(200);
        assertThat(get(colleague, base(e)).status()).isEqualTo(404);
        assertThat(get(colleague, base(e) + "/" + id + "/download").status()).isEqualTo(404);
        assertThat(get(outsider, base(e)).status()).isEqualTo(404);
        assertThat(get(null, base(e)).status()).isEqualTo(401);
    }

    // --- deleting ----------------------------------------------------------------------------------------

    @Test
    void theClaimantCanDeleteAReceiptOfADraftAndItsObjectIsRemoved() {
        UUID e = expense("DRAFT");
        String id = uploaded(e);
        String key = jdbc.sql("select storage_key from receipts where id = :id")
                .param("id", UUID.fromString(id))
                .query(String.class)
                .single();
        assertThat(call(claimant, HttpMethod.DELETE, base(e) + "/" + id).status())
                .isEqualTo(204);
        assertThat(get(claimant, base(e)).body()).isEmpty();
        assertThat(storage.exists(key)).isFalse();
        assertThat(call(claimant, HttpMethod.DELETE, base(e) + "/" + id).status())
                .isEqualTo(404);
    }

    @Test
    void receiptsCannotBeDeletedOnceTheExpenseIsSubmitted() {
        UUID e = expense("DRAFT");
        String id = uploaded(e);
        awaitStatus(e, id, "ACCEPTED");
        jdbc.sql("update expenses set status = 'SUBMITTED' where id = :id")
                .param("id", e)
                .update();
        Resp r = call(claimant, HttpMethod.DELETE, base(e) + "/" + id);
        assertThat(r.status()).isEqualTo(409);
        assertThat(call(approver, HttpMethod.DELETE, base(e) + "/" + id).status())
                .isEqualTo(403);
    }

    @Test
    void deletingTheExpenseRemovesItsReceiptsAndFiles() {
        UUID e = expense("DRAFT");
        String id = uploaded(e);
        String key = jdbc.sql("select storage_key from receipts where id = :id")
                .param("id", UUID.fromString(id))
                .query(String.class)
                .single();
        Resp del = call(claimant, HttpMethod.DELETE, "/api/v1/teams/" + team + "/expenses/" + e);
        // DELETE needs If-Match, so go through the API properly with the current ETag:
        assertThat(del.status()).isEqualTo(428);
        var etag = client.get()
                .uri("/api/v1/teams/" + team + "/expenses/" + e)
                .headers(h -> h.add("Authorization", claimant.bearer()))
                .exchange()
                .returnResult(String.class)
                .getResponseHeaders()
                .getETag();
        var deleted = client.delete()
                .uri("/api/v1/teams/" + team + "/expenses/" + e)
                .headers(h -> {
                    h.add("Authorization", claimant.bearer());
                    h.add("If-Match", etag);
                })
                .exchange()
                .returnResult(String.class);
        assertThat(deleted.getStatus().value()).isEqualTo(204);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(jdbc.sql("select count(*) from receipts where expense_id = :e")
                            .param("e", e)
                            .query(Integer.class)
                            .single())
                    .isZero();
            assertThat(storage.exists(key)).isFalse();
        });
    }
}
