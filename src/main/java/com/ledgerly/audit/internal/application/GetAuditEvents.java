package com.ledgerly.audit.internal.application;

import com.ledgerly.audit.internal.infrastructure.AuditTrail;
import com.ledgerly.shared.config.LedgerlyLimits;
import com.ledgerly.shared.error.InvalidRequest;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.paging.Cursor;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads one team's audit trail, newest first, a page at a time. */
@Service
public class GetAuditEvents {

    /** One page of the trail. {@code nextCursor} is opaque and null on the last page. */
    public record Page(List<AuditTrail.Row> items, @Nullable String nextCursor) {}

    private final AuditTrail trail;
    private final LedgerlyLimits limits;

    GetAuditEvents(AuditTrail trail, LedgerlyLimits limits) {
        this.trail = trail;
        this.limits = limits;
    }

    /**
     * Who may call this (OWNERs) is decided before this runs; here it is only paging.
     *
     * @param limit page size, 1 up to the configured maximum, otherwise {@link InvalidRequest}
     * @param after the {@code nextCursor} of the previous page, or null/blank for the first page
     */
    @Transactional(readOnly = true)
    public Page execute(TeamId teamId, int limit, @Nullable String after) {
        if (limit < 1 || limit > limits.maxPageSize()) {
            throw new InvalidRequest("limit", "limit must be between 1 and " + limits.maxPageSize());
        }
        Cursor cursor = after == null || after.isBlank() ? null : Cursor.decode(after);
        var page = trail.page(teamId.value(), cursor, limit);
        return new Page(page.items(), page.next() == null ? null : page.next().encode());
    }
}
