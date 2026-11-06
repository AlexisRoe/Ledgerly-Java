package com.ledgerly.shared.error;

import java.util.List;

public final class PayloadTooLarge extends LedgerlyException {

    public PayloadTooLarge(String detail) {
        super(ErrorCatalogue.PAYLOAD_TOO_LARGE, detail, List.of());
    }
}
