package com.ledgerly;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock tests can move forward (expiry, lockout) and put back. Starts at the real time. */
public class MutableClock extends Clock {

    private volatile Instant instant = Instant.now();

    public void advance(Duration duration) {
        instant = instant.plus(duration);
    }

    public void reset() {
        instant = Instant.now();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
