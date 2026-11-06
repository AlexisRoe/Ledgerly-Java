package com.ledgerly;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Creates teams and memberships straight in Postgres, so expense tests need no HTTP set-up. */
public final class TeamSeeder {

    private final JdbcClient jdbc;

    public TeamSeeder(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Idempotent: creates the team if needed and sets the user's role in it. */
    public void member(UUID teamId, UUID userId, String role) {
        jdbc.sql("insert into teams (id, name, created_at) values (:id, 'Test team', now()) on conflict do nothing")
                .param("id", teamId)
                .update();
        jdbc.sql("""
                        insert into memberships (team_id, user_id, role, joined_at) values (:t, :u, :r, now())
                        on conflict (team_id, user_id) do update set role = excluded.role
                        """).param("t", teamId).param("u", userId).param("r", role).update();
    }

    public void remove(UUID teamId, UUID userId) {
        jdbc.sql("delete from memberships where team_id = :t and user_id = :u")
                .param("t", teamId)
                .param("u", userId)
                .update();
    }
}
