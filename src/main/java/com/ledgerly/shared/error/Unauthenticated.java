package com.ledgerly.shared.error;

import java.util.List;

/** 401: no valid credentials. Deliberately says nothing about why. */
public final class Unauthenticated extends LedgerlyException {

    public Unauthenticated() {
        super(ErrorCatalogue.UNAUTHENTICATED, "Authentication required", List.of());
    }
}
