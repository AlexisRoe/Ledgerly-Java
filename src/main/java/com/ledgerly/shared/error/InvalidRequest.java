package com.ledgerly.shared.error;

import java.util.List;

public final class InvalidRequest extends LedgerlyException {

    public InvalidRequest(String field, String message) {
        super(
                ErrorCatalogue.VALIDATION_FAILED,
                "Request validation failed",
                List.of(new FieldViolation(field, message)));
    }
}
