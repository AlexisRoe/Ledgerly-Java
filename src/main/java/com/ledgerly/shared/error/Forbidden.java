package com.ledgerly.shared.error;

import java.util.List;

public final class Forbidden extends LedgerlyException {

    public Forbidden(String detail) {
        super(ErrorCatalogue.FORBIDDEN, detail, List.of());
    }
}
