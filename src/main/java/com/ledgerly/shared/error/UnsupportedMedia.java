package com.ledgerly.shared.error;

import java.util.List;

public final class UnsupportedMedia extends LedgerlyException {

    public UnsupportedMedia(String detail) {
        super(ErrorCatalogue.UNSUPPORTED_MEDIA_TYPE, detail, List.of());
    }
}
