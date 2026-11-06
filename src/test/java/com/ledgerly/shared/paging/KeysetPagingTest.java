package com.ledgerly.shared.paging;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerly.ExpenseSeeder;
import com.ledgerly.TestcontainersConfiguration;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** D8: the cursor codec and keyset helper, proven against the real expenses table. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class KeysetPagingTest {

    record Row(UUID id, Instant createdAt) {}

    static final RowMapper<Row> MAPPER = (rs, n) -> new Row(
            rs.getObject("id", UUID.class), rs.getTimestamp("created_at").toInstant());

    @Autowired
    JdbcClient jdbc;

    ExpenseSeeder seeder;
    UUID team;
    final UUID claimant = UUID.randomUUID();
    final Instant start = Instant.parse("2026-01-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        seeder = new ExpenseSeeder(jdbc);
        team = UUID.randomUUID();
    }

    Set<UUID> seed(int count) {
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < count; i++) {
            // Pairs of rows share a timestamp, so the id tie-breaker is exercised.
            Instant createdAt = start.plusSeconds(i / 2);
            ids.add(seeder.insert(
                    team, claimant, "1.00", "EUR", "MEALS", "DRAFT", LocalDate.of(2026, 1, 1), createdAt));
        }
        return ids;
    }

    KeysetPaging.Page<Row> page(Cursor after, int limit) {
        return KeysetPaging.fetch(jdbc, "expenses", team, after, limit, MAPPER, r -> new Cursor(r.createdAt(), r.id()));
    }

    @Test
    void pagesThroughEveryRowExactlyOnceNewestFirst() {
        Set<UUID> expected = seed(250);
        List<Row> seen = new ArrayList<>();
        Cursor cursor = null;
        int pages = 0;
        do {
            var p = page(cursor, 100);
            seen.addAll(p.items());
            cursor = p.next();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).hasSize(250);
        assertThat(seen.stream().map(Row::id)).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
        assertThat(seen.stream().map(Row::createdAt)).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void lastPageHasNoCursor() {
        seed(5);
        var p = page(null, 5);
        assertThat(p.items()).hasSize(5);
        assertThat(p.next()).isNull();
    }

    @Test
    void emptyTeamGivesEmptyPage() {
        var p = page(null, 10);
        assertThat(p.items()).isEmpty();
        assertThat(p.next()).isNull();
    }

    @Test
    void rowsInsertedWhilePagingDoNotCauseDuplicatesOrLoss() {
        Set<UUID> original = seed(250);
        var first = page(null, 100);
        // New rows arrive (newer than everything) after the client fetched page one.
        for (int i = 0; i < 20; i++) {
            seeder.insert(
                    team,
                    claimant,
                    "1.00",
                    "EUR",
                    "MEALS",
                    "DRAFT",
                    LocalDate.of(2026, 1, 1),
                    start.plusSeconds(10_000 + i));
        }
        List<Row> seen = new ArrayList<>(first.items());
        Cursor cursor = first.next();
        while (cursor != null) {
            var p = page(cursor, 100);
            seen.addAll(p.items());
            cursor = p.next();
        }
        assertThat(seen.stream().map(Row::id)).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(original);
    }

    @Test
    void otherTeamsRowsAreNeverReturned() {
        seed(3);
        UUID other = UUID.randomUUID();
        seeder.insert(other, claimant, "1.00", "EUR", "MEALS", "DRAFT", LocalDate.of(2026, 1, 1), start);
        assertThat(page(null, 50).items()).hasSize(3);
    }
}
