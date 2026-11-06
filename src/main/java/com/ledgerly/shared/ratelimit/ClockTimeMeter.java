package com.ledgerly.shared.ratelimit;

import io.github.bucket4j.TimeMeter;
import java.time.Clock;
import java.time.Instant;

/**
 * Lets Bucket4j read the time from the application's {@link Clock}, so tests can move time forward instead of
 * sleeping through a minute or a lock-out period.
 */
final class ClockTimeMeter implements TimeMeter {

    private final Clock clock;

    /** @param clock the application clock to read time from */
    ClockTimeMeter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public long currentTimeNanos() {
        Instant now = clock.instant();
        return Math.addExact(Math.multiplyExact(now.getEpochSecond(), 1_000_000_000L), now.getNano());
    }

    @Override
    public boolean isWallClockBased() {
        return true;
    }
}
