package com.ledgerly.imports.events;

import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import java.time.Instant;
import java.util.UUID;

/** An import job finished. {@code failed} means it stopped early and can be restarted. */
public record ImportCompleted(
        TeamId teamId,
        UserId actorId,
        UUID importId,
        int importedRows,
        int skippedRows,
        boolean failed,
        Instant occurredAt) {}
