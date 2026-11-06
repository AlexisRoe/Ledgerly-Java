package com.ledgerly.shared.error;

import java.util.List;

/**
 * 409: the request is fine but clashes with the current state (already a member, expired invitation, last owner, ...).
 */
public final class Conflict extends LedgerlyException {

    /** @param error catalogue entry that gives the status and code */
    public Conflict(ErrorCatalogue error, String detail) {
        super(error, detail, List.of());
    }
}
