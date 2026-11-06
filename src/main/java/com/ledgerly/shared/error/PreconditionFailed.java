package com.ledgerly.shared.error;

import java.util.List;

/** 412: the version in If-Match is stale; the client must re-read. */
public final class PreconditionFailed extends LedgerlyException {

    public PreconditionFailed() {
        super(ErrorCatalogue.PRECONDITION_FAILED, "The resource changed since you read it", List.of());
    }
}
