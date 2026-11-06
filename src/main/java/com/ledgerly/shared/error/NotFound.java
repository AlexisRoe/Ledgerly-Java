package com.ledgerly.shared.error;

import java.util.List;

/** Also used when the caller may not know the resource exists. */
public final class NotFound extends LedgerlyException {

    /** Same answer for "does not exist" and "you may not know it exists". */
    public NotFound() {
        super(ErrorCatalogue.NOT_FOUND, "Resource not found", List.of());
    }
}
