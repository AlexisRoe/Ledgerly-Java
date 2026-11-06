package com.ledgerly.shared.error;

import java.util.List;

public final class PreconditionFailed extends LedgerlyException {

    public PreconditionFailed() {
        super(ErrorCatalogue.PRECONDITION_FAILED, "The resource changed since you read it", List.of());
    }
}
