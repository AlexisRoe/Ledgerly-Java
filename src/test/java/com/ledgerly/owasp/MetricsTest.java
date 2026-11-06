package com.ledgerly.owasp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ledgerly.receipts.internal.application.FakeMalwareScanner;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

/** The business metrics the plan asks for, and that they are scraped from the management port only. */
class MetricsTest extends OwaspTestBase {

    @Autowired
    MeterRegistry registry;

    double counter(String name, String... tags) {
        var search = registry.find(name);
        for (int i = 0; i < tags.length; i += 2) search = search.tag(tags[i], tags[i + 1]);
        var c = search.counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void successfulTransitionsAreCountedByAction() {
        Team team = newTeam();
        double submitBefore = counter("ledgerly.expenses.transitions", "action", "submit");
        double approveBefore = counter("ledgerly.expenses.transitions", "action", "approve");
        UUID id = expense(team, team.member(), "DRAFT");
        receipts.accepted(team.id(), id);
        String base = team.path() + "/expenses/" + id;
        assertThat(send(HttpMethod.POST, base + ":submit", team.member(), null).status())
                .isEqualTo(200);
        assertThat(send(HttpMethod.POST, base + ":approve", team.approver(), null)
                        .status())
                .isEqualTo(200);
        // A refused transition is not counted.
        assertThat(send(HttpMethod.POST, base + ":approve", team.approver(), null)
                        .status())
                .isEqualTo(409);

        assertThat(counter("ledgerly.expenses.transitions", "action", "submit")).isEqualTo(submitBefore + 1);
        assertThat(counter("ledgerly.expenses.transitions", "action", "approve"))
                .isEqualTo(approveBefore + 1);
    }

    @Test
    void quarantinedReceiptsAreCounted() {
        Team team = newTeam();
        UUID id = expense(team, team.member(), "DRAFT");
        double before = counter("ledgerly.receipts.quarantined");
        byte[] infected = ("%PDF-1.4\n" + FakeMalwareScanner.EICAR).getBytes(StandardCharsets.ISO_8859_1);
        upload(team.path() + "/expenses/" + id + "/receipts", team.member(), "r.pdf", infected, "application/pdf");
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() ->
                        assertThat(counter("ledgerly.receipts.quarantined")).isEqualTo(before + 1));
    }

    @Test
    void skippedImportRowsAreCounted() {
        Team team = newTeam();
        double before = counter("ledgerly.imports.rows.skipped");
        String csv =
                "date,merchant,amount,currency,category\n2026-01-01,Ok,1.00,EUR,MEALS\n2026-01-02,Bad,abc,EUR,MEALS\n2026-01-03,Worse,1.00,ZZZ,MEALS\n";
        Resp queued = upload(
                team.path() + "/imports", team.member(), "e.csv", csv.getBytes(StandardCharsets.UTF_8), "text/csv");
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() ->
                        assertThat(counter("ledgerly.imports.rows.skipped")).isEqualTo(before + 2));
        assertThat(get(team.path() + "/imports/" + queued.id(), team.member())
                        .json()
                        .get("skippedRows")
                        .asInt())
                .isEqualTo(2);
    }

    @Test
    void prometheusExposesThemOnTheManagementPortOnly() throws Exception {
        Team team = newTeam();
        UUID id = expense(team, team.member(), "DRAFT");
        receipts.accepted(team.id(), id);
        send(HttpMethod.POST, team.path() + "/expenses/" + id + ":submit", team.member(), null);

        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + "/actuator/prometheus"))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("ledgerly_expenses_transitions_total{action=\"submit\"")
                .contains("jvm_memory_used_bytes")
                .contains("http_server_requests");
        // Metric tags hold no ids or names: the series count must stay small.
        assertThat(response.body())
                .doesNotContain(team.member().email())
                .doesNotContain(team.id().toString());

        assertThat(get("/actuator/prometheus", team.owner()).status()).isEqualTo(404);
    }
}
