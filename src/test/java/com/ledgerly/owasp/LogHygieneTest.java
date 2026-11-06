package com.ledgerly.owasp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerly.TestUsers.TestUser;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

/**
 * Runs the flows that handle secrets and personal data (log in, refresh, upload, import, invite) with the log output
 * captured, and checks that none of it was written down: no email, password, token, cookie, file name or file
 * content. The audit trail is where "who did what" lives; the log must not become a second copy of the data.
 */
@ExtendWith(OutputCaptureExtension.class)
class LogHygieneTest extends OwaspTestBase {

    static final String PASSWORD = "Hunter2-Is-A-Terrible-Passw0rd";
    static final String WRONG_PASSWORD = "Another-Wrong-Passw0rd-1";

    @Test
    void noSecretOrPersonalDataReachesTheLogs(CapturedOutput output) {
        String email = "log-hygiene-" + UUID.randomUUID() + "@private-domain.example";
        String inviteeEmail = "invitee-" + UUID.randomUUID() + "@private-domain.example";
        String receiptMarker = "RECEIPT-CONTENT-MARKER-" + UUID.randomUUID();
        String receiptName = "secret-receipt-name-" + UUID.randomUUID() + ".pdf";
        String merchantMarker = "MerchantMarker" + UUID.randomUUID().toString().substring(0, 8);
        String noteMarker = "NoteMarker" + UUID.randomUUID().toString().substring(0, 8);

        // --- accounts: register, verify, log in (right and wrong), refresh (and replay), log out
        assertThat(send(HttpMethod.POST, "/api/v1/auth/register", null, creds(email, PASSWORD))
                        .status())
                .isEqualTo(202);
        UUID userId = jdbc.sql("select id from users where email = :e")
                .param("e", email)
                .query(UUID.class)
                .single();
        String verificationToken = mailbox.forUser(userId);
        assertThat(send(HttpMethod.POST, "/api/v1/auth/verify", null, "{\"token\":\"" + verificationToken + "\"}")
                        .status())
                .isEqualTo(204);
        send(HttpMethod.POST, "/api/v1/auth/login", null, creds(email, WRONG_PASSWORD));
        Resp login = send(HttpMethod.POST, "/api/v1/auth/login", null, creds(email, PASSWORD));
        assertThat(login.status()).isEqualTo(200);
        String accessToken = login.json().get("accessToken").asString();
        String refreshCookie = cookie(login);
        Resp refreshed = send(
                HttpMethod.POST,
                "/api/v1/auth/refresh",
                null,
                null,
                "X-Requested-With",
                "ledgerly",
                "Cookie",
                "refresh_token=" + refreshCookie);
        String refreshedCookie = cookie(refreshed);
        String refreshedAccess = refreshed.json().get("accessToken").asString();
        send(
                HttpMethod.POST,
                "/api/v1/auth/refresh",
                null,
                null,
                "X-Requested-With",
                "ledgerly",
                "Cookie",
                "refresh_token=" + refreshCookie); // replay: logs a warning
        send(
                HttpMethod.POST,
                "/api/v1/auth/logout",
                null,
                null,
                "X-Requested-With",
                "ledgerly",
                "Cookie",
                "refresh_token=" + refreshedCookie);

        // --- work as a team owner: invitation, expense, receipt upload, batch, import, export
        Team team = newTeam();
        TestUser owner = team.owner();
        send(
                HttpMethod.POST,
                team.path() + "/invitations",
                owner,
                "{\"email\":\"" + inviteeEmail + "\",\"role\":\"MEMBER\"}");
        Resp created = send(HttpMethod.POST, team.path() + "/expenses", owner, expenseJson("12.50", merchantMarker));
        String expensePath = team.path() + "/expenses/" + created.id();
        upload(
                expensePath + "/receipts",
                owner,
                receiptName,
                ("%PDF-1.4\n" + receiptMarker + "\n%%EOF\n").getBytes(StandardCharsets.UTF_8),
                "application/pdf");
        send(
                HttpMethod.POST,
                team.path() + "/expenses:batch",
                owner,
                "{\"items\":[" + expenseJson("1.00", merchantMarker + "b") + "]}");
        String csv = "date,merchant,amount,currency,category,note\n2026-01-02," + merchantMarker + "i,5.00,EUR,MEALS,"
                + noteMarker + "\n2026-01-03,Bad row " + merchantMarker + ",abc,EUR,MEALS,\n";
        Resp imported = upload(
                team.path() + "/imports",
                owner,
                "import-" + receiptMarker + ".csv",
                csv.getBytes(StandardCharsets.UTF_8),
                "text/csv");
        await().atMost(Duration.ofSeconds(30))
                .until(() -> get(team.path() + "/imports/" + imported.id(), owner)
                        .json()
                        .get("status")
                        .asString()
                        .equals("COMPLETED"));
        get(team.path() + "/expenses.csv", owner);
        // an authorization failure and a server error, which are logged too
        get(team.path() + "/expenses/" + UUID.randomUUID(), team.colleague());
        // let the asynchronous listeners (scan, audit) write their log lines as well
        await().atMost(Duration.ofSeconds(20))
                .until(() -> jdbc.sql("select count(*) from audit_events where team_id = :t")
                                .param("t", team.id())
                                .query(Integer.class)
                                .single()
                        >= 5);

        String logs = output.getAll();
        assertThat(logs).as("the logs of the flows above are not empty").isNotBlank();
        String[] secrets = {
            email,
            inviteeEmail,
            "@private-domain.example",
            PASSWORD,
            WRONG_PASSWORD,
            verificationToken,
            accessToken,
            refreshedAccess,
            refreshCookie,
            refreshedCookie,
            owner.token(),
            "Bearer ",
            receiptMarker,
            receiptName,
            merchantMarker,
            noteMarker,
            "password_hash",
            "argon2id"
        };
        for (String secret : secrets) {
            assertThat(logs).as("the logs must not contain '%s'", secret).doesNotContain(secret);
        }
        // What is allowed: ids. The user id and team id are on the request lines.
        assertThat(logs).contains(team.id().toString());
    }

    private static String creds(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private static String cookie(Resp r) {
        String header = r.headers().getFirst(HttpHeaders.SET_COOKIE);
        return header.substring(header.indexOf('=') + 1, header.indexOf(';'));
    }
}
