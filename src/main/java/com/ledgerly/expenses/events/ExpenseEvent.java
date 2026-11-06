package com.ledgerly.expenses.events;

import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.id.UuidV7;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Everything that happens to an expense. Ids and field names only: no amounts, merchants, notes or rejection
 * reasons, which may be personal (ADR 0001). The audit trail answers "who changed what", not "to what".
 */
public sealed interface ExpenseEvent
        permits ExpenseEvent.Created,
                ExpenseEvent.Edited,
                ExpenseEvent.Deleted,
                ExpenseEvent.Submitted,
                ExpenseEvent.Withdrawn,
                ExpenseEvent.Approved,
                ExpenseEvent.Rejected,
                ExpenseEvent.Reopened,
                ExpenseEvent.Reimbursed {

    /** Stable across re-delivery, so a listener can recognise an event it has already handled. */
    UUID eventId();

    /** The team the expense belongs to. */
    TeamId teamId();

    /** The expense the event is about. */
    ExpenseId expenseId();

    /** Who did it. */
    UserId actorId();

    /** When it happened (from the application clock, not the delivery time). */
    Instant occurredAt();

    /** A draft was created: through the API, a batch or an import. */
    record Created(UUID eventId, TeamId teamId, ExpenseId expenseId, UserId actorId, Instant occurredAt)
            implements ExpenseEvent {
        public Created(TeamId teamId, ExpenseId expenseId, UserId actorId, Instant occurredAt) {
            this(UuidV7.generate(), teamId, expenseId, actorId, occurredAt);
        }
    }

    /** @param changedFields the names of the fields that were edited, never their values */
    record Edited(
            UUID eventId,
            TeamId teamId,
            ExpenseId expenseId,
            UserId actorId,
            List<String> changedFields,
            Instant occurredAt)
            implements ExpenseEvent {
        public Edited(
                TeamId teamId, ExpenseId expenseId, UserId actorId, List<String> changedFields, Instant occurredAt) {
            this(UuidV7.generate(), teamId, expenseId, actorId, changedFields, occurredAt);
        }
    }

    /** A draft was deleted. Receipts removes its files in response. */
    record Deleted(UUID eventId, TeamId teamId, ExpenseId expenseId, UserId actorId, Instant occurredAt)
            implements ExpenseEvent {
        public Deleted(TeamId teamId, ExpenseId expenseId, UserId actorId, Instant occurredAt) {
            this(UuidV7.generate(), teamId, expenseId, actorId, occurredAt);
        }
    }

    /** A draft was submitted for approval. */
    record Submitted(UUID eventId, TeamId teamId, ExpenseId expenseId, UserId actorId, Instant occurredAt)
            implements ExpenseEvent {
        public Submitted(TeamId teamId, ExpenseId expenseId, UserId actorId, Instant occurredAt) {
            this(UuidV7.generate(), teamId, expenseId, actorId, occurredAt);
        }
    }

    /** The claimant took a submitted expense back to draft. */
    record Withdrawn(UUID eventId, TeamId teamId, ExpenseId expenseId, UserId actorId, Instant occurredAt)
            implements ExpenseEvent {
        public Withdrawn(TeamId teamId, ExpenseId expenseId, UserId actorId, Instant occurredAt) {
            this(UuidV7.generate(), teamId, expenseId, actorId, occurredAt);
        }
    }

    /** An approver approved a submitted expense. */
    record Approved(UUID eventId, TeamId teamId, ExpenseId expenseId, UserId actorId, Instant occurredAt)
            implements ExpenseEvent {
        public Approved(TeamId teamId, ExpenseId expenseId, UserId actorId, Instant occurredAt) {
            this(UuidV7.generate(), teamId, expenseId, actorId, occurredAt);
        }
    }

    /** The reason is kept with the expense itself, not copied into the event. */
    record Rejected(UUID eventId, TeamId teamId, ExpenseId expenseId, UserId actorId, Instant occurredAt)
            implements ExpenseEvent {
        public Rejected(TeamId teamId, ExpenseId expenseId, UserId actorId, Instant occurredAt) {
            this(UuidV7.generate(), teamId, expenseId, actorId, occurredAt);
        }
    }

    /** The claimant moved a rejected expense back to draft. */
    record Reopened(UUID eventId, TeamId teamId, ExpenseId expenseId, UserId actorId, Instant occurredAt)
            implements ExpenseEvent {
        public Reopened(TeamId teamId, ExpenseId expenseId, UserId actorId, Instant occurredAt) {
            this(UuidV7.generate(), teamId, expenseId, actorId, occurredAt);
        }
    }

    /** An owner marked an approved expense as paid out. */
    record Reimbursed(UUID eventId, TeamId teamId, ExpenseId expenseId, UserId actorId, Instant occurredAt)
            implements ExpenseEvent {
        public Reimbursed(TeamId teamId, ExpenseId expenseId, UserId actorId, Instant occurredAt) {
            this(UuidV7.generate(), teamId, expenseId, actorId, occurredAt);
        }
    }
}
