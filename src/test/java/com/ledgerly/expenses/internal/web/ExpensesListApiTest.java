package com.ledgerly.expenses.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerly.ExpenseSeeder;
import com.ledgerly.TeamSeeder;
import com.ledgerly.TestcontainersConfiguration;
import com.ledgerly.identity.internal.application.AccessTokenIssuer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@Import(TestcontainersConfiguration.class)
class ExpensesListApiTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Autowired
    AccessTokenIssuer tokens;

    @Autowired
    JdbcClient jdbc;

    RestTestClient client;
    ExpenseSeeder seeder;
    UUID team;
    UUID alice; // MEMBER
    UUID bob; // MEMBER
    UUID approver;
    final Instant t0 = Instant.parse("2026-02-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        client = RestTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
        seeder = new ExpenseSeeder(jdbc);
        team = UUID.randomUUID();
        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
        approver = UUID.randomUUID();
    }

    record Resp(int status, JsonNode body) {
        List<String> ids() {
            List<String> ids = new ArrayList<>();
            body.get("content").forEach(n -> ids.add(n.get("id").asString()));
            return ids;
        }
    }

    Resp list(UUID user, String role, String query) {
        if (role != null) {
            new TeamSeeder(jdbc).member(team, user, role);
        }
        var result = client.get()
                .uri("/api/v1/teams/" + team + "/expenses" + (query.isEmpty() ? "" : "?" + query))
                .headers(h -> {
                    h.add("Authorization", "Bearer " + tokens.issue(user));
                })
                .exchange()
                .returnResult(String.class);
        return new Resp(result.getStatus().value(), JSON.readTree(result.getResponseBody()));
    }

    UUID seed(UUID claimant, String amount, String currency, String category, String status, String date, int offset) {
        return seeder.insert(
                team, claimant, amount, currency, category, status, LocalDate.parse(date), t0.plusSeconds(offset));
    }

    // --- paging ----------------------------------------------------------------------------

    @Test
    void pagingThrough250ExpensesVisitsEachOnce() {
        Set<UUID> expected = new HashSet<>();
        for (int i = 0; i < 250; i++) {
            expected.add(seed(alice, "1.00", "EUR", "MEALS", "DRAFT", "2026-01-15", i));
        }
        List<String> seen = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            Resp r = list(approver, "APPROVER", "size=100&page=" + page);
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.body().get("page").get("totalElements").asLong()).isEqualTo(250);
            assertThat(r.body().get("page").get("totalPages").asInt()).isEqualTo(3);
            assertThat(r.body().get("page").get("size").asInt()).isEqualTo(100);
            assertThat(r.body().get("page").get("number").asInt()).isEqualTo(page);
            seen.addAll(r.ids());
        }
        assertThat(seen).hasSize(250).doesNotHaveDuplicates();
        assertThat(seen)
                .containsExactlyInAnyOrderElementsOf(
                        expected.stream().map(UUID::toString).toList());
    }

    @Test
    void pageBeyondTheEndIsEmptyNotAnError() {
        seed(alice, "1.00", "EUR", "MEALS", "DRAFT", "2026-01-15", 0);
        Resp r = list(approver, "APPROVER", "page=5");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.ids()).isEmpty();
    }

    @Test
    void sizeAbove100IsRejectedNotClamped_API4() {
        Resp r = list(approver, "APPROVER", "size=101");
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("code").asString()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void zeroNegativeAndNonNumericPagingParametersAreRejected() {
        assertThat(list(approver, "APPROVER", "size=0").status()).isEqualTo(400);
        assertThat(list(approver, "APPROVER", "page=-1").status()).isEqualTo(400);
        assertThat(list(approver, "APPROVER", "size=abc").status()).isEqualTo(400);
    }

    // --- sorting ---------------------------------------------------------------------------

    @Test
    void defaultSortIsIncurredOnDescending() {
        UUID old = seed(alice, "1.00", "EUR", "MEALS", "DRAFT", "2026-01-01", 0);
        UUID recent = seed(alice, "1.00", "EUR", "MEALS", "DRAFT", "2026-01-20", 1);
        assertThat(list(approver, "APPROVER", "").ids()).containsExactly(recent.toString(), old.toString());
    }

    @Test
    void sortAscendingAndByCreatedAt() {
        UUID first = seed(alice, "1.00", "EUR", "MEALS", "DRAFT", "2026-01-20", 0);
        UUID second = seed(alice, "1.00", "EUR", "MEALS", "DRAFT", "2026-01-01", 1);
        assertThat(list(approver, "APPROVER", "sort=incurredOn,asc").ids())
                .containsExactly(second.toString(), first.toString());
        assertThat(list(approver, "APPROVER", "sort=createdAt,desc").ids())
                .containsExactly(second.toString(), first.toString());
    }

    @Test
    void sortByAmountNeedsACurrencyFilter() {
        seed(alice, "5.00", "EUR", "MEALS", "DRAFT", "2026-01-01", 0);
        Resp r = list(approver, "APPROVER", "sort=amount,asc");
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("errors").get(0).get("field").asString()).isEqualTo("sort");
    }

    @Test
    void sortByAmountWithCurrencyOrdersNumerically() {
        UUID ten = seed(alice, "10.00", "EUR", "MEALS", "DRAFT", "2026-01-01", 0);
        UUID nine = seed(alice, "9.00", "EUR", "MEALS", "DRAFT", "2026-01-02", 1);
        UUID hundred = seed(alice, "100.00", "EUR", "MEALS", "DRAFT", "2026-01-03", 2);
        seed(alice, "1.00", "USD", "MEALS", "DRAFT", "2026-01-04", 3);
        assertThat(list(approver, "APPROVER", "sort=amount,asc&currency=EUR").ids())
                .containsExactly(nine.toString(), ten.toString(), hundred.toString());
    }

    @Test
    void sortInjectionIsRejected() {
        for (String sort :
                List.of("password", "claimantId", "id.value,asc", "incurredOn,sideways", "money.amount", "a,b,c")) {
            assertThat(list(approver, "APPROVER", "sort=" + sort).status())
                    .as(sort)
                    .isEqualTo(400);
        }
    }

    // --- filters ---------------------------------------------------------------------------

    @Test
    void filtersCombine() {
        UUID target = seed(bob, "20.00", "USD", "TRAVEL", "SUBMITTED", "2026-01-10", 0);
        seed(bob, "20.00", "USD", "TRAVEL", "DRAFT", "2026-01-10", 1);
        seed(bob, "20.00", "USD", "MEALS", "SUBMITTED", "2026-01-10", 2);
        seed(bob, "20.00", "EUR", "TRAVEL", "SUBMITTED", "2026-01-10", 3);
        seed(alice, "20.00", "USD", "TRAVEL", "SUBMITTED", "2026-01-10", 4);
        seed(bob, "20.00", "USD", "TRAVEL", "SUBMITTED", "2025-12-31", 5);
        Resp r = list(
                approver,
                "APPROVER",
                "status=SUBMITTED&category=TRAVEL&currency=USD&claimantId=" + bob
                        + "&incurredFrom=2026-01-01&incurredTo=2026-01-31");
        assertThat(r.ids()).containsExactly(target.toString());
    }

    @Test
    void dateRangeIsInclusive() {
        UUID a = seed(alice, "1.00", "EUR", "MEALS", "DRAFT", "2026-01-01", 0);
        UUID b = seed(alice, "1.00", "EUR", "MEALS", "DRAFT", "2026-01-31", 1);
        seed(alice, "1.00", "EUR", "MEALS", "DRAFT", "2026-02-01", 2);
        assertThat(list(approver, "APPROVER", "incurredFrom=2026-01-01&incurredTo=2026-01-31&sort=incurredOn,asc")
                        .ids())
                .containsExactly(a.toString(), b.toString());
    }

    @Test
    void badFilterValuesAreRejected() {
        assertThat(list(approver, "APPROVER", "status=BOGUS").status()).isEqualTo(400);
        assertThat(list(approver, "APPROVER", "currency=ZZZ").status()).isEqualTo(400);
        assertThat(list(approver, "APPROVER", "currency=eur").status()).isEqualTo(400);
        assertThat(list(approver, "APPROVER", "incurredFrom=2026-02-01&incurredTo=2026-01-01")
                        .status())
                .isEqualTo(400);
        assertThat(list(approver, "APPROVER", "incurredFrom=yesterday").status())
                .isEqualTo(400);
    }

    // --- visibility (API1) -----------------------------------------------------------------

    @Test
    void memberSeesOnlyOwnEvenWhenFilteringForSomeoneElse() {
        UUID mine = seed(alice, "1.00", "EUR", "MEALS", "DRAFT", "2026-01-01", 0);
        seed(bob, "1.00", "EUR", "MEALS", "DRAFT", "2026-01-01", 1);
        assertThat(list(alice, "MEMBER", "").ids()).containsExactly(mine.toString());
        assertThat(list(alice, "MEMBER", "claimantId=" + bob).ids()).containsExactly(mine.toString());
        assertThat(list(alice, "MEMBER", "")
                        .body()
                        .get("page")
                        .get("totalElements")
                        .asLong())
                .isEqualTo(1);
    }

    @Test
    void approverAndOwnerSeeEveryone() {
        seed(alice, "1.00", "EUR", "MEALS", "DRAFT", "2026-01-01", 0);
        seed(bob, "1.00", "EUR", "MEALS", "DRAFT", "2026-01-01", 1);
        assertThat(list(approver, "APPROVER", "").ids()).hasSize(2);
        assertThat(list(approver, "OWNER", "").ids()).hasSize(2);
    }

    @Test
    void nonTeammateGets404() {
        seed(alice, "1.00", "EUR", "MEALS", "DRAFT", "2026-01-01", 0);
        assertThat(list(UUID.randomUUID(), null, "").status()).isEqualTo(404);
    }

    @Test
    void anotherTeamsExpensesAreNotListed() {
        UUID mine = seed(alice, "1.00", "EUR", "MEALS", "DRAFT", "2026-01-01", 0);
        seeder.insert(UUID.randomUUID(), alice, "1.00", "EUR", "MEALS", "DRAFT", LocalDate.of(2026, 1, 1), t0);
        assertThat(list(approver, "APPROVER", "").ids()).containsExactly(mine.toString());
    }
}
