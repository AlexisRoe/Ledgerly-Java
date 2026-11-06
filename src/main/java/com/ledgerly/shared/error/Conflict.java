package com.ledgerly.shared.error;

import java.util.List;

public final class Conflict extends LedgerlyException {

    public Conflict(ErrorCatalogue error, String detail) {
        super(error, detail, List.of());
    }
}
