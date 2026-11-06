package com.ledgerly.shared.error;

import java.time.Duration;
import java.util.List;

/** 429. {@link #retryAfter()} becomes the {@code Retry-After} header. */
/** 429: a rate limit was hit; {@link #retryAfter} says when to try again. */
public final class TooManyRequests extends LedgerlyException {

    private final Duration retryAfter;

    /** @param retryAfter how long until a token is available again */
    public TooManyRequests(String detail, Duration retryAfter) {
        super(ErrorCatalogue.RATE_LIMITED, detail, List.of());
        this.retryAfter = retryAfter;
    }

    /** Becomes the Retry-After header (at least one second). */
    public Duration retryAfter() {
        return retryAfter;
    }
}
