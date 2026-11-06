package com.ledgerly.receipts.events;

import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import java.time.Instant;
import java.util.UUID;

public sealed interface ReceiptEvent
        permits ReceiptEvent.Uploaded, ReceiptEvent.Accepted, ReceiptEvent.Quarantined, ReceiptEvent.Deleted {

    TeamId teamId();

    ExpenseId expenseId();

    UUID receiptId();

    Instant occurredAt();

    record Uploaded(TeamId teamId, ExpenseId expenseId, UUID receiptId, UserId actorId, Instant occurredAt)
            implements ReceiptEvent {}

    /** The malware scan passed. Done by the system, not a user. */
    record Accepted(TeamId teamId, ExpenseId expenseId, UUID receiptId, Instant occurredAt) implements ReceiptEvent {}

    /** The malware scan found something. Done by the system, not a user. */
    record Quarantined(TeamId teamId, ExpenseId expenseId, UUID receiptId, Instant occurredAt)
            implements ReceiptEvent {}

    record Deleted(TeamId teamId, ExpenseId expenseId, UUID receiptId, UserId actorId, Instant occurredAt)
            implements ReceiptEvent {}
}
