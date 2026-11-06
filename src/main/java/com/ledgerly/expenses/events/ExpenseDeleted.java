package com.ledgerly.expenses.events;

import com.ledgerly.shared.id.ExpenseId;
import com.ledgerly.shared.id.TeamId;
import com.ledgerly.shared.id.UserId;
import java.time.Instant;

/** A draft expense was deleted. Receipts removes its files in response. */
public record ExpenseDeleted(TeamId teamId, ExpenseId expenseId, UserId actorId, Instant occurredAt) {}
