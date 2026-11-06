package com.ledgerly;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Inserts receipt rows directly, so expense tests can submit without going through an upload. */
public final class ReceiptSeeder {

    private final JdbcClient jdbc;

    public ReceiptSeeder(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID accepted(UUID teamId, UUID expenseId) {
        return insert(teamId, expenseId, "ACCEPTED");
    }

    public UUID insert(UUID teamId, UUID expenseId, String scanStatus) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into receipts (id, team_id, expense_id, storage_key, original_name, detected_type,
                                              size_bytes, sha256, scan_status, possible_duplicate, uploaded_by, created_at)
                        values (:id, :team, :expense, :key, 'seed.pdf', 'application/pdf', 10, :sha, :status,
                                false, :by, now())
                        """)
                .param("id", id)
                .param("team", teamId)
                .param("expense", expenseId)
                .param("key", "receipts/" + id)
                .param("sha", id.toString().replace("-", "") + id.toString().replace("-", ""))
                .param("status", scanStatus)
                .param("by", UUID.randomUUID())
                .update();
        return id;
    }
}
