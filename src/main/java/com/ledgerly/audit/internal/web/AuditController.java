package com.ledgerly.audit.internal.web;

import com.ledgerly.audit.internal.application.GetAuditEvents;
import com.ledgerly.audit.internal.infrastructure.AuditTrail;
import com.ledgerly.shared.api.AuditApi;
import com.ledgerly.shared.api.model.AuditEvent;
import com.ledgerly.shared.api.model.AuditEventPage;
import com.ledgerly.shared.id.TeamId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@code GET /api/v1/teams/{teamId}/audit-events}: the team's trail for its OWNERs (contract: api/openapi.yaml). */
@RestController
@RequestMapping("/api/v1")
class AuditController implements AuditApi {

    private final GetAuditEvents get;
    private final JsonMapper json;

    AuditController(GetAuditEvents get, JsonMapper json) {
        this.get = get;
        this.json = json;
    }

    /**
     * OWNER only: not a teammate is 404, a teammate with another role is 403. The check is by bean name, so audit
     * needs no code dependency on the teams module.
     */
    @Override
    @PreAuthorize("@teamAuthorization.anyOf(#teamId, 'OWNER')")
    public ResponseEntity<AuditEventPage> listAuditEvents(UUID teamId, Integer limit, @Nullable String after) {
        var page = get.execute(new TeamId(teamId), limit, after);
        var items = page.items().stream().map(this::toResponse).toList();
        return ResponseEntity.ok(new AuditEventPage(items).nextCursor(page.nextCursor()));
    }

    /** Maps a stored row to the API shape; optional fields (actor, subject) are left out when the row has none. */
    private AuditEvent toResponse(AuditTrail.Row row) {
        Map<String, Object> details = json.readValue(row.detailsJson(), new TypeReference<Map<String, Object>>() {});
        var event = new AuditEvent(row.id(), row.eventType(), row.occurredAt().atOffset(ZoneOffset.UTC), details);
        if (row.actorId() != null) event.actorId(row.actorId());
        if (row.subjectType() != null) event.subjectType(row.subjectType());
        if (row.subjectId() != null) event.subjectId(row.subjectId());
        return event;
    }
}
