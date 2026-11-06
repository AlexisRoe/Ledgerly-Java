package com.ledgerly.receipts.events;

import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import com.ledgerly.shared.id.UuidV7;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Something that happened to a receipt. Carries ids only, never file names or content. */
public sealed interface ReceiptEvent
        permits ReceiptEvent.Uploaded, ReceiptEvent.Accepted, ReceiptEvent.Quarantined, ReceiptEvent.Deleted {

    /** Stable across re-delivery, so a listener can recognise an event it has already handled. */
    UUID eventId();

    /** The team the receipt's expense belongs to. */
    TeamId teamId();

    /** The expense the receipt is attached to. */
    ExpenseId expenseId();

    /** The receipt. */
    UUID receiptId();

    /** When it happened (application clock). */
    Instant occurredAt();

    /** The user who did it; null when the system did (the malware scan). */
    default @Nullable UserId actorId() {
        return null;
    }

    /** A file was stored and is waiting for the malware scan. */
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

    /** A user removed a receipt. */
    record Deleted(UUID eventId, TeamId teamId, ExpenseId expenseId, UUID receiptId, UserId actorId, Instant occurredAt)
            implements ReceiptEvent {
        public Deleted(TeamId teamId, ExpenseId expenseId, UUID receiptId, UserId actorId, Instant occurredAt) {
            this(UuidV7.generate(), teamId, expenseId, receiptId, actorId, occurredAt);
        }
    }
}
