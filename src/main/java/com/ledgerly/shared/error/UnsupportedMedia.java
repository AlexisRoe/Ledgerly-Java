package com.ledgerly.shared.error;

import java.util.List;

/** 415: the file or content type is not one we accept. */
public final class UnsupportedMedia extends LedgerlyException {

    public UnsupportedMedia(String detail) {
        super(ErrorCatalogue.UNSUPPORTED_MEDIA_TYPE, detail, List.of());
    }
}
