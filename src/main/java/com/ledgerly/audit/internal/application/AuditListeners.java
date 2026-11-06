package com.ledgerly.audit.internal.application;

import com.ledgerly.expenses.events.ExpenseEvent;
import com.ledgerly.identity.events.RefreshTokenReuseDetected;
import com.ledgerly.identity.events.UserRegistered;
import com.ledgerly.identity.events.UserVerified;
import com.ledgerly.imports.events.ImportCompleted;
import com.ledgerly.receipts.events.ReceiptEvent;
import com.ledgerly.teams.events.TeamEvent;
import java.util.Map;
import java.util.UUID;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * One listener per event type. Each one runs after the publisher's transaction commits, in its own transaction, and
 * the event stays in the publication registry until it succeeds, so an action is never missing from the trail
 * because of a crash or a failed insert. Recording is idempotent on the event id, so delivering an event twice
 * adds one row.
 *
 * <p>Listeners only use the ids and field names the events carry. Events of accounts have no team.
 */
@Component
class AuditListeners {

    private final AuditRecorder audit;

    AuditListeners(AuditRecorder audit) {
        this.audit = audit;
    }

    // --- identity: no team ----------------------------------------------------------------------------------

    @ApplicationModuleListener
    void on(UserRegistered e) {
        audit.record(
                e.eventId(),
                "USER_REGISTERED",
                null,
                e.actorId().value(),
                "USER",
                e.actorId().value(),
                Map.of(),
                e.occurredAt());
    }

    @ApplicationModuleListener
    void on(UserVerified e) {
        audit.record(
                e.eventId(),
                "USER_VERIFIED",
                null,
                e.actorId().value(),
                "USER",
                e.actorId().value(),
                Map.of(),
                e.occurredAt());
    }

    @ApplicationModuleListener
    void on(RefreshTokenReuseDetected e) {
        audit.record(
                e.eventId(),
                "REFRESH_TOKEN_REUSE_DETECTED",
                null,
                e.actorId().value(),
                "USER",
                e.actorId().value(),
                Map.of("familyId", e.familyId()),
                e.occurredAt());
    }

    // --- teams ---------------------------------------------------------------------------------------------------

    @ApplicationModuleListener
    void on(TeamEvent.TeamCreated e) {
        team(e, "TEAM_CREATED", "TEAM", e.teamId().value(), Map.of());
    }

    @ApplicationModuleListener
    void on(TeamEvent.MemberInvited e) {
        team(e, "MEMBER_INVITED", "INVITATION", e.invitationId(), Map.of("role", e.role()));
    }

    @ApplicationModuleListener
    void on(TeamEvent.InvitationRevoked e) {
        team(e, "INVITATION_REVOKED", "INVITATION", e.invitationId(), Map.of());
    }

    @ApplicationModuleListener
    void on(TeamEvent.InvitationAccepted e) {
        team(e, "INVITATION_ACCEPTED", "INVITATION", e.invitationId(), Map.of());
    }

    @ApplicationModuleListener
    void on(TeamEvent.InvitationDeclined e) {
        team(e, "INVITATION_DECLINED", "INVITATION", e.invitationId(), Map.of());
    }

    @ApplicationModuleListener
    void on(TeamEvent.MemberRoleChanged e) {
        team(
                e,
                "MEMBER_ROLE_CHANGED",
                "MEMBER",
                e.memberId().value(),
                Map.of("oldRole", e.oldRole(), "newRole", e.newRole()));
    }

    @ApplicationModuleListener
    void on(TeamEvent.MemberRemoved e) {
        team(e, "MEMBER_REMOVED", "MEMBER", e.memberId().value(), Map.of());
    }

    @ApplicationModuleListener
    void on(TeamEvent.MemberLeft e) {
        team(e, "MEMBER_LEFT", "MEMBER", e.actorId().value(), Map.of());
    }

    private void team(TeamEvent e, String type, String subjectType, UUID subjectId, Map<String, ?> details) {
        audit.record(
                e.eventId(),
                type,
                e.teamId().value(),
                e.actorId().value(),
                subjectType,
                subjectId,
                details,
                e.occurredAt());
    }

    // --- expenses -----------------------------------------------------------------------------------------------

    @ApplicationModuleListener
    void on(ExpenseEvent.Created e) {
        expense(e, "EXPENSE_CREATED", Map.of());
    }

    @ApplicationModuleListener
    void on(ExpenseEvent.Edited e) {
        expense(e, "EXPENSE_EDITED", Map.of("changedFields", e.changedFields()));
    }

    @ApplicationModuleListener
    void on(ExpenseEvent.Deleted e) {
        expense(e, "EXPENSE_DELETED", Map.of());
    }

    @ApplicationModuleListener
    void on(ExpenseEvent.Submitted e) {
        expense(e, "EXPENSE_SUBMITTED", Map.of());
    }

    @ApplicationModuleListener
    void on(ExpenseEvent.Withdrawn e) {
        expense(e, "EXPENSE_WITHDRAWN", Map.of());
    }

    @ApplicationModuleListener
    void on(ExpenseEvent.Approved e) {
        expense(e, "EXPENSE_APPROVED", Map.of());
    }

    @ApplicationModuleListener
    void on(ExpenseEvent.Rejected e) {
        expense(e, "EXPENSE_REJECTED", Map.of());
    }

    @ApplicationModuleListener
    void on(ExpenseEvent.Reopened e) {
        expense(e, "EXPENSE_REOPENED", Map.of());
    }

    @ApplicationModuleListener
    void on(ExpenseEvent.Reimbursed e) {
        expense(e, "EXPENSE_REIMBURSED", Map.of());
    }

    private void expense(ExpenseEvent e, String type, Map<String, ?> details) {
        audit.record(
                e.eventId(),
                type,
                e.teamId().value(),
                e.actorId().value(),
                "EXPENSE",
                e.expenseId().value(),
                details,
                e.occurredAt());
    }

    // --- receipts -----------------------------------------------------------------------------------------------

    @ApplicationModuleListener
    void on(ReceiptEvent.Uploaded e) {
        receipt(e, "RECEIPT_UPLOADED");
    }

    @ApplicationModuleListener
    void on(ReceiptEvent.Accepted e) {
        receipt(e, "RECEIPT_ACCEPTED");
    }

    @ApplicationModuleListener
    void on(ReceiptEvent.Quarantined e) {
        receipt(e, "RECEIPT_QUARANTINED");
    }

    @ApplicationModuleListener
    void on(ReceiptEvent.Deleted e) {
        receipt(e, "RECEIPT_DELETED");
    }

    private void receipt(ReceiptEvent e, String type) {
        audit.record(
                e.eventId(),
                type,
                e.teamId().value(),
                e.actorId() == null ? null : e.actorId().value(),
                "RECEIPT",
                e.receiptId(),
                Map.of("expenseId", e.expenseId().value()),
                e.occurredAt());
    }

    // --- imports ------------------------------------------------------------------------------------------------

    @ApplicationModuleListener
    void on(ImportCompleted e) {
        audit.record(
                e.eventId(),
                "IMPORT_COMPLETED",
                e.teamId().value(),
                e.actorId().value(),
                "IMPORT",
                e.importId(),
                Map.of("importedRows", e.importedRows(), "skippedRows", e.skippedRows(), "failed", e.failed()),
                e.occurredAt());
    }
}
