package com.ledgerly.shared.error;

import java.util.List;

public final class Unprocessable extends LedgerlyException {

    public Unprocessable(ErrorCatalogue error, String detail) {
        super(error, detail, List.of());
    }
}
