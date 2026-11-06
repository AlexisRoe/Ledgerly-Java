package com.ledgerly.shared.error;

import java.util.List;

/** 422: one request field is invalid; names the field. */
public final class InvalidRequest extends LedgerlyException {

    /**
     * @param field the request field as the API names it
     * @param message what is wrong with it
     */
    public InvalidRequest(String field, String message) {
        super(
                ErrorCatalogue.VALIDATION_FAILED,
                "Request validation failed",
                List.of(new FieldViolation(field, message)));
    }
}
