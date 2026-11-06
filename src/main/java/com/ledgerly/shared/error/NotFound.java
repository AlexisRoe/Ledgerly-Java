package com.ledgerly.shared.error;

import java.util.List;

/** Also used when the caller may not know the resource exists. */
public final class NotFound extends LedgerlyException {

    public NotFound() {
        super(ErrorCatalogue.NOT_FOUND, "Resource not found", List.of());
    }
}
