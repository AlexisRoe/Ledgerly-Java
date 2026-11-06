package com.ledgerly.owasp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerly.receipts.internal.application.FakeMalwareScanner;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

/**
 * API10: Unsafe consumption of APIs. Controls: CSV files and ClamAV replies are treated as untrusted input; cells
 * that start with {@code =}, {@code +}, {@code -}, {@code @} are neutralised on export. Proof: the formula-injection
 * row is escaped. (The strict matching of ClamAV replies is tested in {@code ClamAvScannerTest}, next to the code.)
 */
class Api10UnsafeConsumptionOfApisTest extends OwaspTestBase {

    @Autowired
    FakeMalwareScanner scanner;

    @AfterEach
    void restoreScanner() {
        scanner.unavailable = false;
    }

    @Test
    void aFormulaInTheDataIsEscapedInTheExport() {
        Team team = newTeam();
        UUID id = expense(team, team.member(), "DRAFT");
        jdbc.sql("update expenses set merchant = :m, note = :n where id = :id")
                .param("m", "=HYPERLINK(\"http://evil.test\",\"click me\")")
                .param("n", "@SUM(1+1)")
                .param("id", id)
                .update();
        String csv = get(team.path() + "/expenses.csv", team.approver()).text();
        assertThat(csv)
                .contains("\"'=HYPERLINK(\"\"http://evil.test\"\",\"\"click me\"\")\"")
                .contains(",'@SUM(1+1)");
        assertThat(csv.lines().skip(1)).noneMatch(line -> line.contains(",=") || line.contains(",@"));
    }

    @Test
    void aFormulaThatEntersThroughAnImportIsStoredAsTypedAndEscapedOnTheWayOut() {
        Team team = newTeam();
        String csv =
                "date,merchant,amount,currency,category,note\n" + "2026-01-02,=cmd|' /C calc'!A0,5.00,EUR,MEALS,+1+1\n";
        Resp queued = upload(
                team.path() + "/imports", team.member(), "e.csv", csv.getBytes(StandardCharsets.UTF_8), "text/csv");
        assertThat(queued.status()).isEqualTo(202);
        await().atMost(Duration.ofSeconds(30))
                .until(() -> get(team.path() + "/imports/" + queued.id(), team.member())
                        .json()
                        .get("status")
                        .asString()
                        .equals("COMPLETED"));

        // Stored as typed (nothing is rewritten on the way in) ...
        assertThat(jdbc.sql("select merchant from expenses where team_id = :t")
                        .param("t", team.id())
                        .query(String.class)
                        .single())
                .isEqualTo("=cmd|' /C calc'!A0");
        // ... and neutralised when it leaves.
        String exported = get(team.path() + "/expenses.csv", team.member()).text();
        assertThat(exported).contains("'=cmd|' /C calc'!A0").contains(",'+1+1");
    }

    @Test
    void aFileIsNeverAcceptedUntilTheScannerHasClearlySaidSo() {
        Team team = newTeam();
        UUID expense = expense(team, team.member(), "DRAFT");
        scanner.unavailable = true; // the scanner gives no usable answer
        String path = team.path() + "/expenses/" + expense + "/receipts";
        String id = upload(path, team.member(), "r.pdf", PDF, "application/pdf").id();
        assertThat(get(path, team.member()).json().get(0).get("status").asString())
                .isEqualTo("PENDING_SCAN");
        assertThat(get(path + "/" + id + "/download", team.member()).status()).isEqualTo(404);
    }

    @Test
    void anInfectedFileIsQuarantinedAndNeverServed() {
        Team team = newTeam();
        UUID expense = expense(team, team.member(), "DRAFT");
        byte[] infected = ("%PDF-1.4\n" + FakeMalwareScanner.EICAR).getBytes(StandardCharsets.ISO_8859_1);
        String path = team.path() + "/expenses/" + expense + "/receipts";
        String id = upload(path, team.member(), "r.pdf", infected, "application/pdf")
                .id();
        await().atMost(Duration.ofSeconds(20))
                .until(() -> get(path, team.member())
                        .json()
                        .get(0)
                        .get("status")
                        .asString()
                        .equals("QUARANTINED"));
        assertThat(get(path + "/" + id + "/download", team.member()).status()).isEqualTo(404);
        assertThat(get(path + "/" + id + "/download", team.approver()).status()).isEqualTo(404);
    }

    @Test
    void theClaimedFileTypeAndNameAreNeverTrusted() {
        Team team = newTeam();
        UUID expense = expense(team, team.member(), "DRAFT");
        String path = team.path() + "/expenses/" + expense + "/receipts";
        byte[] exe = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 4, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 0, 0};
        assertThat(upload(path, team.member(), "invoice.pdf", exe, "application/pdf")
                        .status())
                .isEqualTo(415);
        assertThat(upload(
                                path,
                                team.member(),
                                "x.png",
                                "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8),
                                "image/png")
                        .status())
                .isEqualTo(415);
    }

    @Test
    void aCsvThatIsReallyBinaryIsRefused() {
        Team team = newTeam();
        byte[] binary = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 4, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 0, 0};
        assertThat(upload(team.path() + "/imports", team.member(), "e.csv", binary, "text/csv")
                        .status())
                .isEqualTo(415);
        assertThat(send(HttpMethod.GET, team.path() + "/expenses.csv", team.member(), null)
                        .status())
                .isEqualTo(200);
    }
}
