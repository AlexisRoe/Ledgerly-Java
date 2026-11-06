package com.ledgerly.shared.error;

import java.util.List;

/** 413: the body or upload exceeds the configured limit. */
public final class PayloadTooLarge extends LedgerlyException {

    public PayloadTooLarge(String detail) {
        super(ErrorCatalogue.PAYLOAD_TOO_LARGE, detail, List.of());
    }
}
