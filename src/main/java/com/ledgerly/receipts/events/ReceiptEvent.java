package com.ledgerly.receipts.events;

import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.id.UuidV7;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public sealed interface ReceiptEvent
        permits ReceiptEvent.Uploaded, ReceiptEvent.Accepted, ReceiptEvent.Quarantined, ReceiptEvent.Deleted {

    /** Stable across re-delivery, so a listener can recognise an event it has already handled. */
    UUID eventId();

    TeamId teamId();

    ExpenseId expenseId();

    UUID receiptId();

    Instant occurredAt();

    /** The user who did it; null when the system did (the malware scan). */
    default @Nullable UserId actorId() {
        return null;
    }

    record Uploaded(
            UUID eventId, TeamId teamId, ExpenseId expenseId, UUID receiptId, UserId actorId, Instant occurredAt)
            implements ReceiptEvent {
        public Uploaded(TeamId teamId, ExpenseId expenseId, UUID receiptId, UserId actorId, Instant occurredAt) {
            this(UuidV7.generate(), teamId, expenseId, receiptId, actorId, occurredAt);
        }
    }

    /** The malware scan passed. Done by the system, not a user. */
    record Accepted(UUID eventId, TeamId teamId, ExpenseId expenseId, UUID receiptId, Instant occurredAt)
            implements ReceiptEvent {
        public Accepted(TeamId teamId, ExpenseId expenseId, UUID receiptId, Instant occurredAt) {
            this(UuidV7.generate(), teamId, expenseId, receiptId, occurredAt);
        }
    }

    /** The malware scan found something. Done by the system, not a user. */
    record Quarantined(UUID eventId, TeamId teamId, ExpenseId expenseId, UUID receiptId, Instant occurredAt)
            implements ReceiptEvent {
        public Quarantined(TeamId teamId, ExpenseId expenseId, UUID receiptId, Instant occurredAt) {
            this(UuidV7.generate(), teamId, expenseId, receiptId, occurredAt);
        }
    }

    record Deleted(UUID eventId, TeamId teamId, ExpenseId expenseId, UUID receiptId, UserId actorId, Instant occurredAt)
            implements ReceiptEvent {
        public Deleted(TeamId teamId, ExpenseId expenseId, UUID receiptId, UserId actorId, Instant occurredAt) {
            this(UuidV7.generate(), teamId, expenseId, receiptId, actorId, occurredAt);
        }
    }
}
