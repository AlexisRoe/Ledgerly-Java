package com.ledgerly.expenses.internal.domain;

import com.ledgerly.shared.id.UserId;

/** One rejection of an expense. Every rejection is kept; none is overwritten. */
public record Rejection(UserId rejectedBy, String reason) {}
