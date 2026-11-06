package com.ledgerly.shared.error;

import java.time.Duration;
import java.util.List;

/** 429. {@link #retryAfter()} becomes the {@code Retry-After} header. */
public final class TooManyRequests extends LedgerlyException {

    private final Duration retryAfter;

    public TooManyRequests(String detail, Duration retryAfter) {
        super(ErrorCatalogue.RATE_LIMITED, detail, List.of());
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
