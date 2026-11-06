package com.ledgerly.shared.ratelimit;

import java.time.Duration;

/**
 * How many actions are allowed per period.
 *
 * @param gradual true: tokens trickle back one at a time (good for request rates); false: all tokens return at the
 *     end of each period (good for "5 failed logins, then locked until the period is over")
 */
public record RateLimit(long capacity, Duration period, boolean gradual) {

    public RateLimit {
        if (capacity < 1 || period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("A rate limit needs a positive capacity and period");
        }
    }

    /** Smooth: the allowance refills continuously over a minute. */
    public static RateLimit perMinute(long requests) {
        return new RateLimit(requests, Duration.ofMinutes(1), true);
    }

    /** All {@code capacity} tokens come back together once {@code period} has passed (used for the login lock). */
    public static RateLimit intervally(long capacity, Duration period) {
        return new RateLimit(capacity, period, false);
    }
}
