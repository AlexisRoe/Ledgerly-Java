package com.ledgerly.audit.internal.application;

import com.ledgerly.audit.internal.infrastructure.AuditTrail;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Turns what a listener knows about an event into one audit row. */
@Component
class AuditRecorder {

    private final AuditTrail trail;
    private final JsonMapper json;

    AuditRecorder(AuditTrail trail, JsonMapper json) {
        this.trail = trail;
        this.json = json;
    }

    /**
     * @param details ids and field names only. Never a name, email, amount or free text: the trail is permanent
     *     (ADR 0001).
     */
    void record(
            UUID eventId,
            String eventType,
            @Nullable UUID teamId,
            @Nullable UUID actorId,
            @Nullable String subjectType,
            @Nullable UUID subjectId,
            Map<String, ?> details,
            Instant occurredAt) {
        trail.append(new AuditTrail.Entry(
                eventId,
                teamId,
                actorId,
                eventType,
                subjectType,
                subjectId,
                json.writeValueAsString(new LinkedHashMap<>(details)),
                occurredAt));
    }
}
