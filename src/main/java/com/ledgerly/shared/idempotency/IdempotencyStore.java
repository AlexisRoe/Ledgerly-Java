package com.ledgerly.shared.idempotency;

import com.ledgerly.shared.id.UserId;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Remembers which resource an {@code Idempotency-Key} produced. Replays return the resource as it is now,
 * not a byte-for-byte copy of the first response.
 */
@Component
public class IdempotencyStore {

    public static final Duration TTL = Duration.ofHours(24);

    /** What was answered the first time: a hash of the request, the status and the id of the created resource. */
    public record Entry(String requestHash, int responseStatus, UUID resourceId) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    /** @param clock decides which keys have expired */
    IdempotencyStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The unexpired entry for this user and key, or empty. Keys are per user. */
    public Optional<Entry> find(UserId user, String key) {
        return jdbc.sql("""
                        select request_hash, response_status, resource_id from idempotency_keys
                        where user_id = :user and idem_key = :key and expires_at > :now
                        """)
                .param("user", user.value())
                .param("key", key)
                .param("now", Timestamp.from(clock.instant()))
                .query((rs, n) -> new Entry(
                        rs.getString("request_hash"),
                        rs.getInt("response_status"),
                        rs.getObject("resource_id", UUID.class)))
                .optional();
    }

    /** @throws DuplicateKeyException when another request stored the same key first */
    public void save(UserId user, String key, Entry entry) {
        Instant now = clock.instant();
        jdbc.sql("delete from idempotency_keys where user_id = :user and idem_key = :key and expires_at <= :now")
                .param("user", user.value())
                .param("key", key)
                .param("now", Timestamp.from(now))
                .update();
        jdbc.sql("""
                        insert into idempotency_keys
                          (user_id, idem_key, request_hash, response_status, resource_id, created_at, expires_at)
                        values (:user, :key, :hash, :status, :resource, :created, :expires)
                        """)
                .param("user", user.value())
                .param("key", key)
                .param("hash", entry.requestHash())
                .param("status", entry.responseStatus())
                .param("resource", entry.resourceId())
                .param("created", Timestamp.from(now))
                .param("expires", Timestamp.from(now.plus(TTL)))
                .update();
    }
}
