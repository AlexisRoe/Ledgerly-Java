package com.ledgerly.shared.error;

import java.util.List;

public final class Unauthenticated extends LedgerlyException {

    public Unauthenticated() {
        super(ErrorCatalogue.UNAUTHENTICATED, "Authentication required", List.of());
    }
}
