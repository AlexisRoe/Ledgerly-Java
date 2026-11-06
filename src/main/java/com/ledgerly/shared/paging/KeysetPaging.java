package com.ledgerly.shared.paging;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Keyset ("seek") paging for tables that only grow: newest first, ordered by {@code (created_at, id)}.
 * Unlike offset paging it stays fast on a big table and does not skip or repeat rows when new rows arrive.
 */
public final class KeysetPaging {

    private KeysetPaging() {}

    /** {@code next} is the cursor of the page after this one, null on the last page. */
    public record Page<T>(List<T> items, @Nullable Cursor next) {}

    /**
     * @param table a table with {@code team_id}, {@code created_at} and {@code id} columns; a developer
     *     constant, never user input
     * @param limit the page size; one more row is read to learn whether another page exists
     */
    public static <T> Page<T> fetch(
            JdbcClient jdbc,
            String table,
            UUID teamId,
            @Nullable Cursor after,
            int limit,
            RowMapper<T> mapper,
            Function<T, Cursor> cursorOf) {
        String sql = "select * from " + table + " where team_id = :team"
                + (after == null ? "" : " and (created_at, id) < (:ts, :id)")
                + " order by created_at desc, id desc limit :limit";
        var statement = jdbc.sql(sql).param("team", teamId).param("limit", limit + 1);
        if (after != null) {
            statement = statement.param("ts", Timestamp.from(after.timestamp())).param("id", after.id());
        }
        List<T> rows = new ArrayList<>(statement.query(mapper).list());
        if (rows.size() <= limit) {
            return new Page<>(rows, null);
        }
        rows.remove(rows.size() - 1);
        return new Page<>(rows, cursorOf.apply(rows.get(rows.size() - 1)));
    }
}
