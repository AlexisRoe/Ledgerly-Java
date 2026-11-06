package com.ledgerly.shared.error;

import java.util.List;

/** 422: the request is well-formed but breaks a rule; may list field violations. */
public final class Unprocessable extends LedgerlyException {

    /** Without field details. */
    public Unprocessable(ErrorCatalogue error, String detail) {
        super(error, detail, List.of());
    }

    /** With one entry per offending field. */
    public Unprocessable(ErrorCatalogue error, String detail, List<FieldViolation> violations) {
        super(error, detail, violations);
    }
}
