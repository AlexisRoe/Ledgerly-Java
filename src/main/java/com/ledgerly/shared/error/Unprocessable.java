package com.ledgerly.shared.error;

import java.util.List;

public final class Unprocessable extends LedgerlyException {

    public Unprocessable(ErrorCatalogue error, String detail) {
        super(error, detail, List.of());
    }

    public Unprocessable(ErrorCatalogue error, String detail, List<FieldViolation> violations) {
        super(error, detail, violations);
    }
}
