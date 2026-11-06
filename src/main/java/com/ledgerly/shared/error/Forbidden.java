package com.ledgerly.shared.error;

import java.util.List;

/** 403: the caller is known but may not do this (wrong role, unverified email). */
public final class Forbidden extends LedgerlyException {

    /** Generic FORBIDDEN. */
    public Forbidden(String detail) {
        super(ErrorCatalogue.FORBIDDEN, detail, List.of());
    }

    /** With a specific code, for example EMAIL_NOT_VERIFIED. */
    public Forbidden(ErrorCatalogue error, String detail) {
        super(error, detail, List.of());
    }
}
