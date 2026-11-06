package com.ledgerly.imports.events;

import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.id.UuidV7;
import java.time.Instant;
import java.util.UUID;

/** An import job finished. {@code failed} means it stopped early and can be restarted. Counts only, no row data. */
public record ImportCompleted(
        UUID eventId,
        TeamId teamId,
        UserId actorId,
        UUID importId,
        int importedRows,
        int skippedRows,
        boolean failed,
        Instant occurredAt) {

    public ImportCompleted(
            TeamId teamId,
            UserId actorId,
            UUID importId,
            int importedRows,
            int skippedRows,
            boolean failed,
            Instant occurredAt) {
        this(UuidV7.generate(), teamId, actorId, importId, importedRows, skippedRows, failed, occurredAt);
    }
}
