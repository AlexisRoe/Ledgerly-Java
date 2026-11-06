package com.ledgerly.receipts.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerly.ExpenseSeeder;
import com.ledgerly.TeamSeeder;
import com.ledgerly.TestUsers;
import com.ledgerly.TestUsers.TestUser;
import com.ledgerly.TestcontainersConfiguration;
import com.ledgerly.identity.internal.application.AccessTokenIssuer;
import com.ledgerly.identity.internal.application.CapturedVerificationTokens;
import com.ledgerly.shared.storage.FileSystemObjectStorage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The M7 weakness, and its cure. Before M9 a receipt whose scan could not run stayed PENDING_SCAN for ever,
 * because the event that triggered the scan was lost. Now the event stays in the publication registry until its
 * listener succeeds, and delivering it again finishes the job.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@Import(TestcontainersConfiguration.class)
class EventRegistryRecoveryTest {

    static final byte[] PDF = "%PDF-1.4\n1 0 obj << >> endobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AccessTokenIssuer issuer;

    @Autowired
    FakeMalwareScanner scanner;

    @Autowired
    IncompleteEventPublications incomplete;

    @Autowired
    ScanReceipt scanReceipt;

    @Autowired
    CapturedVerificationTokens mailbox;

    @Autowired
    FileSystemObjectStorage storage;

    RestTestClient client;
    UUID team;
    TestUser claimant;
    UUID expense;

    @BeforeEach
    void setUp() {
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
        team = UUID.randomUUID();
        claimant = new TestUsers(jdbc, issuer).create(true);
        new TeamSeeder(jdbc).member(team, claimant.id(), "MEMBER");
        expense = new ExpenseSeeder(jdbc)
                .insert(team, claimant.id(), "5.00", "EUR", "MEALS", "DRAFT", LocalDate.now(), Instant.now());
    }

    @AfterEach
    void restoreScanner() {
        scanner.unavailable = false;
    }

    String upload() {
        var builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(PDF)).filename("r.pdf").contentType(MediaType.APPLICATION_PDF);
        var result = client.post()
                .uri("/api/v1/teams/" + team + "/expenses/" + expense + "/receipts")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .headers(h -> h.add("Authorization", claimant.bearer()))
                .body(builder.build())
                .exchange()
                .returnResult(String.class);
        assertThat(result.getStatus().value()).isEqualTo(202);
        return JsonMapper.builder()
                .build()
                .readTree(result.getResponseBody())
                .get("id")
                .asString();
    }

    String status(String receiptId) {
        return jdbc.sql("select scan_status from receipts where id = :id")
                .param("id", UUID.fromString(receiptId))
                .query(String.class)
                .single();
    }

    int openScanPublications(String receiptId) {
        return jdbc.sql("""
                        select count(*) from event_publication
                        where completion_date is null and event_type like '%ReceiptUploaded'
                          and serialized_event like :id
                        """)
                .param("id", "%" + receiptId + "%")
                .query(Integer.class)
                .single();
    }

    @Test
    void aScanThatCouldNotRunIsKeptAndFinishedWhenDeliveredAgain() {
        scanner.unavailable = true; // the scanner is down at the moment of the upload
        String id = upload();

        // The scan fails; the receipt waits, and the event that should have scanned it is still on record.
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(openScanPublications(id)).isEqualTo(1));
        assertThat(status(id)).isEqualTo("PENDING_SCAN");

        // The scanner is back. Delivering the incomplete events again (what start-up and the scheduled job do).
        scanner.unavailable = false;
        incomplete.resubmitIncompletePublications(p -> p.getEvent() instanceof ReceiptUploaded u
                && u.receiptId().toString().equals(id));

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(status(id)).isEqualTo("ACCEPTED"));
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(openScanPublications(id)).isZero());
    }

    @Test
    void aSuccessfulScanLeavesNoOpenPublication() {
        String id = upload();
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(status(id)).isEqualTo("ACCEPTED"));
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(openScanPublications(id)).isZero());
    }

    @Test
    void deliveringAScanEventAgainAfterTheScanIsHarmless() {
        String id = upload();
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(status(id)).isEqualTo("ACCEPTED"));
        // Scanning an already accepted receipt does nothing.
        scanReceipt.scan(UUID.fromString(id));
        assertThat(status(id)).isEqualTo("ACCEPTED");
    }

    // --- secrets must not end up in the registry
    // ------------------------------------------------------------------------

    @Test
    void theVerificationTokenNeverReachesTheEventRegistry() {
        String email = "registry-" + UUID.randomUUID() + "@example.com";
        var result = client.post()
                .uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"email\":\"" + email + "\",\"password\":\"S3cret-Passw0rd-xyz\"}")
                .exchange()
                .returnResult(String.class);
        assertThat(result.getStatus().value()).isEqualTo(202);
        UUID userId = jdbc.sql("select id from users where email = :e")
                .param("e", email)
                .query(UUID.class)
                .single();
        String token = mailbox.forUser(userId);
        assertThat(token).isNotBlank();

        // The registration event is recorded (without the token); the internal token event is not recorded at all.
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(
                                jdbc.sql("select count(*) from event_publication where serialized_event like :u")
                                        .param("u", "%" + userId + "%")
                                        .query(Integer.class)
                                        .single())
                        .isGreaterThanOrEqualTo(1));
        assertThat(jdbc.sql("select count(*) from event_publication where serialized_event like :t")
                        .param("t", "%" + token + "%")
                        .query(Integer.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("select count(*) from event_publication where event_type like '%VerificationLinkIssued'")
                        .query(Integer.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("select count(*) from audit_events where cast(details as text) like :t")
                        .param("t", "%" + token + "%")
                        .query(Integer.class)
                        .single())
                .isZero();
    }
}
