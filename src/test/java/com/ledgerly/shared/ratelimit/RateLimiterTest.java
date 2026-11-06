package com.ledgerly.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerly.MutableClock;
import java.time.Duration;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    final MutableClock clock = new MutableClock();
    final RateLimiter limiter = new RateLimiter(clock);

    @Test
    void allowsUpToTheCapacityThenRefusesWithAWaitTime() {
        var limit = RateLimit.perMinute(3);
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryConsume("api", "u1", limit).allowed()).isTrue();
        }
        var refused = limiter.tryConsume("api", "u1", limit);
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfter()).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(60));
    }

    @Test
    void remainingTokensCountDown() {
        var limit = RateLimit.perMinute(5);
        assertThat(limiter.tryConsume("api", "u1", limit).remaining()).isEqualTo(4);
        assertThat(limiter.tryConsume("api", "u1", limit).remaining()).isEqualTo(3);
    }

    @Test
    void gradualLimitsGetTokensBackOverTime() {
        var limit = RateLimit.perMinute(60); // one token per second
        for (int i = 0; i < 60; i++) limiter.tryConsume("api", "u1", limit);
        assertThat(limiter.tryConsume("api", "u1", limit).allowed()).isFalse();
        clock.advance(Duration.ofSeconds(2));
        assertThat(limiter.tryConsume("api", "u1", limit).allowed()).isTrue();
        assertThat(limiter.tryConsume("api", "u1", limit).allowed()).isTrue();
        assertThat(limiter.tryConsume("api", "u1", limit).allowed()).isFalse();
    }

    @Test
    void intervalLimitsGetEverythingBackAtTheEndOfThePeriod() {
        var limit = RateLimit.intervally(5, Duration.ofMinutes(15));
        for (int i = 0; i < 5; i++) limiter.tryConsume("login", "a@x.test", limit);
        assertThat(limiter.isExhausted("login", "a@x.test", limit)).isTrue();
        clock.advance(Duration.ofMinutes(14));
        assertThat(limiter.isExhausted("login", "a@x.test", limit)).isTrue();
        clock.advance(Duration.ofMinutes(2));
        assertThat(limiter.isExhausted("login", "a@x.test", limit)).isFalse();
    }

    @Test
    void checkingIfExhaustedTakesNothing() {
        var limit = RateLimit.intervally(1, Duration.ofMinutes(1));
        for (int i = 0; i < 10; i++)
            assertThat(limiter.isExhausted("login", "k", limit)).isFalse();
        assertThat(limiter.tryConsume("login", "k", limit).allowed()).isTrue();
    }

    @Test
    void resetFillsTheBucketAgain() {
        var limit = RateLimit.intervally(2, Duration.ofHours(1));
        limiter.tryConsume("login", "k", limit);
        limiter.tryConsume("login", "k", limit);
        assertThat(limiter.isExhausted("login", "k", limit)).isTrue();
        limiter.reset("login", "k", limit);
        assertThat(limiter.isExhausted("login", "k", limit)).isFalse();
    }

    @Test
    void scopesAndKeysAreIndependent() {
        var limit = RateLimit.perMinute(1);
        assertThat(limiter.tryConsume("api", "u1", limit).allowed()).isTrue();
        assertThat(limiter.tryConsume("api", "u1", limit).allowed()).isFalse();
        assertThat(limiter.tryConsume("api", "u2", limit).allowed()).isTrue();
        assertThat(limiter.tryConsume("other", "u1", limit).allowed()).isTrue();
    }

    @Test
    void concurrentCallersNeverGetMoreThanTheCapacity() throws Exception {
        var limit = RateLimit.perMinute(50);
        var allowed = new AtomicInteger();
        var gate = new CyclicBarrier(8);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int t = 0; t < 8; t++) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    for (int i = 0; i < 100; i++) {
                        if (limiter.tryConsume("api", "shared", limit).allowed()) allowed.incrementAndGet();
                    }
                    return null;
                }));
            }
            for (var f : futures) f.get();
        }
        assertThat(allowed.get()).isEqualTo(50);
    }

    @Test
    void aLimitNeedsAPositiveCapacityAndPeriod() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RateLimit.perMinute(0))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RateLimit.intervally(1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
