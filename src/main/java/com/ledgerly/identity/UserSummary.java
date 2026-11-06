package com.ledgerly.identity;

import com.ledgerly.shared.id.UserId;

/** What other modules may know about a user. Email is for matching invitations only; never store or log it. */
public record UserSummary(UserId id, String email, boolean verified) {}
