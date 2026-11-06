package com.ledgerly.shared.error;

import java.util.List;

/** Base of the errors the API reports. Sealed, so a {@code switch} over it can be exhaustive. */
public abstract sealed class LedgerlyException extends RuntimeException
        permits NotFound,
                Forbidden,
                Unauthenticated,
                Conflict,
                PreconditionFailed,
                PreconditionRequired,
                Unprocessable,
                InvalidRequest,
                PayloadTooLarge,
                UnsupportedMedia {

    private final ErrorCatalogue error;
    private final List<FieldViolation> violations;

    protected LedgerlyException(ErrorCatalogue error, String detail, List<FieldViolation> violations) {
        super(detail);
        this.error = error;
        this.violations = List.copyOf(violations);
    }

    public ErrorCatalogue error() {
        return error;
    }

    public List<FieldViolation> violations() {
        return violations;
    }
}
