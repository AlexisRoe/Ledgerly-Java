package com.ledgerly.teams.internal.domain;

/** Where an invitation is in its life; only PENDING can still be answered. */
public enum InvitationStatus {
    PENDING,
    ACCEPTED,
    DECLINED,
    REVOKED,
    /** Never stored: computed from the clock when an invitation is read. */
    EXPIRED
}
