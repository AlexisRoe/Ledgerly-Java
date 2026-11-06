package com.ledgerly.expenses.internal.domain;

import com.ledgerly.shared.id.UserId;
import jakarta.persistence.Embeddable;

/** One rejection of an expense. Every rejection is kept; none is overwritten. */
@Embeddable
public record Rejection(UserId rejectedBy, String reason) {}
