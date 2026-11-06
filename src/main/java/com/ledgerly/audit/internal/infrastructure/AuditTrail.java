package com.ledgerly.audit.internal.infrastructure;

import com.ledgerly.shared.id.UuidV7;
import com.ledgerly.shared.paging.Cursor;
import com.ledgerly.shared.paging.KeysetPaging;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The audit trail table. Deliberately has no method that updates or deletes a row: the only ways in are
 * {@link #append} and the reads, and the database refuses the rest as well (V8__audit.sql).
 */
@Repository
public class AuditTrail {

    /** What is stored for one event: ids and field names only; {@code detailsJson} is a JSON object. */
    public record Entry(
            UUID eventId,
            @Nullable UUID teamId,
            @Nullable UUID actorId,
            String eventType,
            @Nullable String subjectType,
            @Nullable UUID subjectId,
            String detailsJson,
            Instant occurredAt) {}

    /** A stored row as read back. {@code createdAt} is when it was recorded, {@code occurredAt} when it happened. */
    public record Row(
            UUID id,
            @Nullable UUID teamId,
            @Nullable UUID actorId,
            String eventType,
            @Nullable String subjectType,
            @Nullable UUID subjectId,
            String detailsJson,
            Instant occurredAt,
            Instant createdAt) {}

    private static final RowMapper<Row> ROW = (rs, n) -> new Row(
            rs.getObject("id", UUID.class),
            rs.getObject("team_id", UUID.class),
            rs.getObject("actor_id", UUID.class),
            rs.getString("event_type"),
            rs.getString("subject_type"),
            rs.getObject("subject_id", UUID.class),
            rs.getString("details"),
            rs.getTimestamp("occurred_at").toInstant(),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcClient jdbc;
    private final Clock clock;

    AuditTrail(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Idempotent on the event id: recording an event that is already recorded does nothing. */
    public void append(Entry entry) {
        jdbc.sql("""
                        insert into audit_events (id, event_id, team_id, actor_id, event_type, subject_type, subject_id,
                                                  details, occurred_at, created_at)
                        values (:id, :eventId, :teamId, :actorId, :eventType, :subjectType, :subjectId,
                                cast(:details as jsonb), :occurredAt, :createdAt)
                        on conflict (event_id) do nothing
                        """)
                .param("id", UuidV7.generate())
                .param("eventId", entry.eventId())
                .param("teamId", entry.teamId())
                .param("actorId", entry.actorId())
                .param("eventType", entry.eventType())
                .param("subjectType", entry.subjectType())
                .param("subjectId", entry.subjectId())
                .param("details", entry.detailsJson())
                .param("occurredAt", Timestamp.from(entry.occurredAt()))
                .param("createdAt", Timestamp.from(clock.instant()))
                .update();
    }

    /** One team's trail, newest first. Rows without a team are never returned here. */
    public KeysetPaging.Page<Row> page(UUID teamId, @Nullable Cursor after, int limit) {
        return KeysetPaging.fetch(
                jdbc, "audit_events", teamId, after, limit, ROW, row -> new Cursor(row.createdAt(), row.id()));
    }
}
