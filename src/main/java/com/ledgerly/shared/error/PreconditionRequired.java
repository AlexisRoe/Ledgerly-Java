package com.ledgerly.shared.error;

import java.util.List;

public final class PreconditionRequired extends LedgerlyException {

    public PreconditionRequired(String header) {
        super(ErrorCatalogue.PRECONDITION_REQUIRED, "The " + header + " header is required", List.of());
    }
}
