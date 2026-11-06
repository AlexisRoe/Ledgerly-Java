package com.ledgerly;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Inserts expense rows straight into Postgres, for tests that need many rows quickly. */
public final class ExpenseSeeder {

    private final JdbcClient jdbc;

    public ExpenseSeeder(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(
            UUID team,
            UUID claimant,
            String amount,
            String currency,
            String category,
            String status,
            LocalDate incurredOn,
            Instant createdAt) {
        UUID id = com.ledgerly.shared.id.UuidV7.generate();
        jdbc.sql("""
                        insert into expenses (id, team_id, claimant_id, amount, currency, category, incurred_on,
                                              merchant, status, version, created_at, updated_at)
                        values (:id, :team, :claimant, :amount, :currency, :category, :incurredOn,
                                'Seed', :status, 0, :createdAt, :createdAt)
                        """)
                .param("id", id)
                .param("team", team)
                .param("claimant", claimant)
                .param("amount", new BigDecimal(amount))
                .param("currency", currency)
                .param("category", category)
                .param("status", status)
                .param("incurredOn", java.sql.Date.valueOf(incurredOn))
                .param("createdAt", Timestamp.from(createdAt))
                .update();
        return id;
    }
}
