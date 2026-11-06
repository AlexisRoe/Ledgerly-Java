package com.ledgerly.shared.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Token-bucket rate limits kept in memory, one bucket per (scope, key): for example ("api", user id) or
 * ("login", email).
 *
 * <p><strong>What this means with more than one instance:</strong> the buckets live in the memory of one
 * process. Two instances behind a load balancer each keep their own, so a client can get up to twice the limit,
 * and a restart forgets all of them (including the lock after failed logins). A shared limit would need a
 * Bucket4j backend on Redis or the database; for one instance, or limits that only have to be roughly right, this
 * is enough. The cache is bounded and entries expire, so the limiter cannot itself be used to exhaust memory.
 */
@Component
public class RateLimiter {

    /** The outcome of asking for one token. */
    public record Decision(boolean allowed, Duration retryAfter, long remaining) {}

    private final ClockTimeMeter time;
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(200_000)
            .expireAfterAccess(Duration.ofHours(2))
            .build();

    /** @param clock the application clock, so tests can move time */
    RateLimiter(Clock clock) {
        this.time = new ClockTimeMeter(clock);
    }

    /** Takes one token if there is one. */
    public Decision tryConsume(String scope, String key, RateLimit limit) {
        var probe = bucket(scope, key, limit).tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return new Decision(true, Duration.ZERO, probe.getRemainingTokens());
        }
        return new Decision(false, Duration.ofNanos(probe.getNanosToWaitForRefill()), 0);
    }

    /** True when no token is left, without taking one. */
    public boolean isExhausted(String scope, String key, RateLimit limit) {
        return bucket(scope, key, limit).getAvailableTokens() < 1;
    }

    /** Fills the bucket again (a successful login forgives earlier failures). */
    public void reset(String scope, String key, RateLimit limit) {
        bucket(scope, key, limit).reset();
    }

    private Bucket bucket(String scope, String key, RateLimit limit) {
        return buckets.get(
                scope + '\u0000' + key,
                k -> Bucket.builder()
                        .withCustomTimePrecision(time)
                        .addLimit(l -> {
                            var capacity = l.capacity(limit.capacity());
                            return limit.gradual()
                                    ? capacity.refillGreedy(limit.capacity(), limit.period())
                                    : capacity.refillIntervally(limit.capacity(), limit.period());
                        })
                        .build());
    }
}
