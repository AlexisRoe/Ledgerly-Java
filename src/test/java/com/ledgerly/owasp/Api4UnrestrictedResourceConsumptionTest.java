package com.ledgerly.owasp;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerly.TestUsers.TestUser;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

/**
 * API4: Unrestricted resource consumption. Controls: page size 100, batch 100, upload 10 MB, JSON body 1 MB, import
 * rows and imports per day, and Bucket4j limits per user. Proof: a 101-item batch is a 400; a burst gets 429 with
 * Retry-After.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "ledgerly.limits.requests-per-minute=30",
            "ledgerly.limits.auth-requests-per-minute=12"
        })
@org.springframework.context.annotation.Import(com.ledgerly.TestcontainersConfiguration.class)
class Api4UnrestrictedResourceConsumptionTest extends OwaspTestBase {

    @Test
    void aHundredAndOneItemBatchIsA400() {
        Team team = newTeam();
        String[] items = new String[101];
        Arrays.fill(items, expenseJson("1.00", "x"));
        assertThat(send(
                                HttpMethod.POST,
                                team.path() + "/expenses:batch",
                                team.member(),
                                "{\"items\":[" + String.join(",", items) + "]}")
                        .status())
                .isEqualTo(400);
    }

    @Test
    void aPageOfMoreThanHundredIsRefusedNotClamped() {
        Team team = newTeam();
        assertThat(get(team.path() + "/expenses?size=101", team.member()).status())
                .isEqualTo(400);
        assertThat(get(team.path() + "/audit-events?limit=101", team.owner()).status())
                .isEqualTo(400);
    }

    @Test
    void uploadsOverTenMegabytesAreA413() {
        Team team = newTeam();
        UUID expense = expense(team, team.member(), "DRAFT");
        byte[] big = Arrays.copyOf(PDF, 10 * 1024 * 1024 + 1);
        assertThat(upload(
                                team.path() + "/expenses/" + expense + "/receipts",
                                team.member(),
                                "big.pdf",
                                big,
                                "application/pdf")
                        .status())
                .isEqualTo(413);
    }

    @Test
    void jsonBodiesOverOneMegabyteAreA413BeforeTheyAreParsed() {
        Team team = newTeam();
        String huge = "{\"pad\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}";
        assertThat(send(HttpMethod.POST, team.path() + "/expenses", team.member(), huge)
                        .status())
                .isEqualTo(413);
    }

    @Test
    void aSixthImportOfTheDayIsA429() {
        Team team = newTeam();
        byte[] csv = "date,merchant,amount,currency,category\n2026-01-01,Cafe,1.00,EUR,MEALS\n"
                .getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 5; i++) {
            assertThat(upload(team.path() + "/imports", team.member(), "e.csv", csv, "text/csv")
                            .status())
                    .isEqualTo(202);
        }
        Resp sixth = upload(team.path() + "/imports", team.member(), "e.csv", csv, "text/csv");
        assertThat(sixth.status()).isEqualTo(429);
        assertThat(sixth.headers().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
    }

    @Test
    void aBurstOfRequestsFromOneUserGets429WithRetryAfterWhileOthersAreUnaffected() {
        TestUser busy = users.create(true);
        TestUser calm = users.create(true);
        int refused = 0;
        Resp last = null;
        for (int i = 0; i < 40; i++) {
            Resp r = get("/api/v1/me", busy);
            if (r.status() == 429) {
                refused++;
                last = r;
            }
        }
        assertThat(refused).isEqualTo(10); // 30 allowed per minute, the other 10 refused
        assertThat(Long.parseLong(last.headers().getFirst(HttpHeaders.RETRY_AFTER)))
                .isBetween(1L, 60L);
        assertThat(last.code()).isEqualTo("RATE_LIMITED");

        assertThat(get("/api/v1/me", calm).status()).isEqualTo(200);

        clock.advance(Duration.ofMinutes(1));
        assertThat(get("/api/v1/me", users.create(true)).status()).isEqualTo(200);
        TestUser busyAgain = new TestUser(busy.id(), busy.email(), issuer.issue(busy.id()));
        assertThat(get("/api/v1/me", busyAgain).status()).isEqualTo(200);
    }

    @Test
    void theEndpointsThatNeedNoTokenAreLimitedByClientAddress() {
        int refused = 0;
        Resp last = null;
        for (int i = 0; i < 20; i++) {
            Resp r = send(
                    HttpMethod.POST,
                    "/api/v1/auth/login",
                    null,
                    "{\"email\":\"nobody@example.com\",\"password\":\"wrong-password-123\"}");
            if (r.status() == 429) {
                refused++;
                last = r;
            }
        }
        assertThat(refused).isGreaterThanOrEqualTo(8); // 12 allowed per minute from this address
        assertThat(last.headers().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
        clock.advance(Duration.ofMinutes(2));
        assertThat(send(
                                HttpMethod.POST,
                                "/api/v1/auth/login",
                                null,
                                "{\"email\":\"nobody@example.com\",\"password\":\"wrong-password-123\"}")
                        .status())
                .isEqualTo(401);
    }
}
