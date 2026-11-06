package com.ledgerly;

import com.ledgerly.identity.internal.application.AccessTokenIssuer;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Inserts users directly (no Argon2 hashing, no HTTP) and mints access tokens for them. */
public final class TestUsers {

    public record TestUser(UUID id, String email, String token) {
        public String bearer() {
            return "Bearer " + token;
        }
    }

    private final JdbcClient jdbc;
    private final AccessTokenIssuer issuer;

    public TestUsers(JdbcClient jdbc, AccessTokenIssuer issuer) {
        this.jdbc = jdbc;
        this.issuer = issuer;
    }

    public TestUser create(boolean verified) {
        UUID id = UUID.randomUUID();
        String email = "user-" + id + "@example.com";
        jdbc.sql("""
                        insert into users (id, email, password_hash, verified, created_at)
                        values (:id, :email, 'not-a-real-hash', :verified, now())
                        """)
                .param("id", id)
                .param("email", email)
                .param("verified", verified)
                .update();
        return new TestUser(id, email, issuer.issue(id));
    }
}
