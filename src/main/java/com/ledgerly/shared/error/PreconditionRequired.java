package com.ledgerly.shared.error;

import java.util.List;

/** 428: the request must carry the named header (If-Match). */
public final class PreconditionRequired extends LedgerlyException {

    /** @param header the missing header's name */
    public PreconditionRequired(String header) {
        super(ErrorCatalogue.PRECONDITION_REQUIRED, "The " + header + " header is required", List.of());
    }
}
