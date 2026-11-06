package com.ledgerly.imports.internal.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Ledgerly's own tables about imports; Spring Batch keeps its own metadata separately. */
@Repository
public class ImportJobs {

    /**
     * One row of {@code import_jobs}. {@code status}: QUEUED, RUNNING, COMPLETED, FAILED; {@code storageKey} is the uploaded CSV.
     */
    public record Job(
            UUID id,
            UUID teamId,
            UUID userId,
            String status,
            String originalName,
            String storageKey,
            int totalRows,
            int importedRows,
            Instant createdAt) {}

    /** A rejected line: its number in the file, a stable code and a message. */
    public record RowError(int line, String code, String message) {}

    private final JdbcClient jdbc;

    ImportJobs(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Serialises concurrent uploads of one user in one team until the transaction ends, so "count today's imports,
     * then insert" cannot be raced past the daily limit.
     */
    public void lockUserInTeam(UUID teamId, UUID userId) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "import:" + teamId + ":" + userId)
                .query()
                .singleRow();
    }

    /** How many imports the user started in the team since {@code since} (daily limit). */
    public int countSince(UUID teamId, UUID userId, Instant since) {
        return jdbc.sql("""
                        select count(*) from import_jobs
                        where team_id = :team and user_id = :user and created_at >= :since
                        """)
                .param("team", teamId)
                .param("user", userId)
                .param("since", Timestamp.from(since))
                .query(Integer.class)
                .single();
    }

    /** Stores a new import. */
    public void insert(Job job) {
        jdbc.sql("""
                        insert into import_jobs (id, team_id, user_id, status, original_name, storage_key,
                                                 total_rows, imported_rows, created_at)
                        values (:id, :team, :user, :status, :name, :key, :total, 0, :created)
                        """)
                .param("id", job.id())
                .param("team", job.teamId())
                .param("user", job.userId())
                .param("status", job.status())
                .param("name", job.originalName())
                .param("key", job.storageKey())
                .param("total", job.totalRows())
                .param("created", Timestamp.from(job.createdAt()))
                .update();
    }

    /** The import, or empty. */
    public Optional<Job> find(UUID id) {
        return jdbc.sql("""
                        select id, team_id, user_id, status, original_name, storage_key, total_rows, imported_rows,
                               created_at
                        from import_jobs where id = :id
                        """)
                .param("id", id)
                .query((rs, n) -> new Job(
                        rs.getObject("id", UUID.class),
                        rs.getObject("team_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("status"),
                        rs.getString("original_name"),
                        rs.getString("storage_key"),
                        rs.getInt("total_rows"),
                        rs.getInt("imported_rows"),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    /** Sets RUNNING and the start time. */
    public void markRunning(UUID id, Instant now) {
        jdbc.sql(
                        "update import_jobs set status = 'RUNNING', started_at = coalesce(started_at, :now), finished_at = null where id = :id")
                .param("id", id)
                .param("now", Timestamp.from(now))
                .update();
    }

    /** Sets the final status ({@code COMPLETED} or {@code FAILED}) and the finish time. */
    public void markFinished(UUID id, String status, Instant now) {
        jdbc.sql("update import_jobs set status = :status, finished_at = :now where id = :id")
                .param("id", id)
                .param("status", status)
                .param("now", Timestamp.from(now))
                .update();
    }

    /** Called inside the chunk's own transaction, so a chunk that rolls back is not counted. */
    public void addImported(UUID id, int rows) {
        jdbc.sql("update import_jobs set imported_rows = imported_rows + :rows where id = :id")
                .param("id", id)
                .param("rows", rows)
                .update();
    }

    /** Idempotent: a restarted import may report the same line again. */
    public void addError(UUID id, int line, String code, String message) {
        jdbc.sql("""
                        insert into import_row_errors (import_id, line, code, message)
                        values (:id, :line, :code, :message) on conflict do nothing
                        """)
                .param("id", id)
                .param("line", line)
                .param("code", code)
                .param("message", message.length() > 500 ? message.substring(0, 500) : message)
                .update();
    }

    /** How many lines were rejected in total. */
    public int errorCount(UUID id) {
        return jdbc.sql("select count(*) from import_row_errors where import_id = :id")
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    /** The first {@code limit} rejected lines, by line number. */
    public List<RowError> errors(UUID id, int limit) {
        return jdbc.sql(
                        "select line, code, message from import_row_errors where import_id = :id order by line limit :limit")
                .param("id", id)
                .param("limit", limit)
                .query((rs, n) -> new RowError(rs.getInt("line"), rs.getString("code"), rs.getString("message")))
                .list();
    }
}
