package com.ledgerly.teams.internal.domain;

public enum InvitationStatus {
    PENDING,
    ACCEPTED,
    DECLINED,
    REVOKED,
    /** Never stored: computed from the clock when an invitation is read. */
    EXPIRED
}
