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
                UnsupportedMedia,
                TooManyRequests {

    private final ErrorCatalogue error;
    private final List<FieldViolation> violations;

    /**
     * @param error catalogue entry (status, code, title)
     * @param detail message for the client; no secrets or personal data
     * @param violations per-field problems, may be empty
     */
    protected LedgerlyException(ErrorCatalogue error, String detail, List<FieldViolation> violations) {
        super(detail);
        this.error = error;
        this.violations = List.copyOf(violations);
    }

    /** What kind of error this is. */
    public ErrorCatalogue error() {
        return error;
    }

    /** Per-field problems; empty for most errors. */
    public List<FieldViolation> violations() {
        return violations;
    }
}
