package com.ledgerly.study;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * M8 language study: scratch tests, not product code. Each test is a small experiment whose assertions say what
 * was learned. Nothing here is used by the application, except the ideas (the import job runs on virtual
 * threads, and the last-OWNER rule of M6 was a race like the one below).
 */
class ConcurrencyStudyTest {

    @Nested
    class ExecutorServices {

        @Test
        void aFixedPoolRunsTasksOnItsOwnThreadsAndFuturesCarryTheResult() throws Exception {
            try (var pool = Executors.newFixedThreadPool(2)) {
                Future<Integer> answer = pool.submit(() -> 6 * 7);
                Future<String> thread = pool.submit(() -> Thread.currentThread().getName());
                assertThat(answer.get()).isEqualTo(42);
                assertThat(thread.get())
                        .startsWith("pool-")
                        .isNotEqualTo(Thread.currentThread().getName());
            }
        }

        @Test
        void anExceptionInATaskSurfacesWrappedWhenYouAskTheFuture() {
            try (var pool = Executors.newSingleThreadExecutor()) {
                Future<Object> failing = pool.submit(() -> {
                    throw new IllegalStateException("boom");
                });
                assertThatThrownBy(failing::get)
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(IllegalStateException.class);
            }
        }

        @Test
        void aPoolOfTwoRunsAtMostTwoTasksAtOnce() throws Exception {
            AtomicInteger running = new AtomicInteger();
            AtomicInteger peak = new AtomicInteger();
            try (var pool = Executors.newFixedThreadPool(2)) {
                List<Callable<Void>> tasks = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    tasks.add(() -> {
                        peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                        Thread.sleep(20);
                        running.decrementAndGet();
                        return null;
                    });
                }
                pool.invokeAll(tasks); // waits for all of them
            }
            assertThat(peak.get()).isEqualTo(2);
        }
    }

    @Nested
    class VirtualThreads {

        @Test
        void aVirtualThreadIsCheapEnoughToStartOnePerTask() throws Exception {
            // 2,000 tasks that each block for 200 ms. With 2,000 platform threads this would be heavy; with
            // virtual threads the blocked ones are parked and the whole batch takes about one sleep.
            long start = System.nanoTime();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<Boolean>> results = new ArrayList<>();
                for (int i = 0; i < 2_000; i++) {
                    results.add(executor.submit(() -> {
                        Thread.sleep(200);
                        return Thread.currentThread().isVirtual();
                    }));
                }
                for (Future<Boolean> result : results) {
                    assertThat(result.get()).isTrue();
                }
            }
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        }

        @Test
        void virtualThreadsAreForBlockingWorkNotForCpuWork() throws Exception {
            // They help when threads mostly wait (database, network, object storage), which is the shape of an
            // import: read a line, wait for the database. They do not make a loop faster.
            var thread = Thread.ofVirtual().name("study-vt").start(() -> {});
            thread.join();
            assertThat(thread.isVirtual()).isTrue();
            assertThat(thread.isDaemon())
                    .as("virtual threads are always daemon threads")
                    .isTrue();
        }
    }

    @Nested
    class CompletableFutures {

        @Test
        void stagesComposeWithoutBlocking() {
            CompletableFuture<Integer> price = CompletableFuture.supplyAsync(() -> 100);
            CompletableFuture<Integer> tax = CompletableFuture.supplyAsync(() -> 19);
            assertThat(price.thenCombine(tax, Integer::sum)
                            .thenApply(total -> "EUR " + total)
                            .join())
                    .isEqualTo("EUR 119");
        }

        @Test
        void exceptionallyTurnsAFailureIntoAFallback() {
            CompletableFuture<String> flaky = CompletableFuture.<String>supplyAsync(() -> {
                        throw new IllegalStateException("scanner down");
                    })
                    .exceptionally(e -> "PENDING_SCAN");
            assertThat(flaky.join()).isEqualTo("PENDING_SCAN");
        }

        @Test
        void joinWrapsFailuresInCompletionException() {
            var failed = CompletableFuture.supplyAsync(() -> {
                throw new IllegalArgumentException("bad row");
            });
            assertThatThrownBy(failed::join)
                    .isInstanceOf(java.util.concurrent.CompletionException.class)
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void allOfWaitsForEveryFuture() {
            var results = new ArrayList<Integer>();
            var lock = new Object();
            CompletableFuture<?>[] futures = new CompletableFuture<?>[5];
            for (int i = 0; i < 5; i++) {
                int n = i;
                futures[i] = CompletableFuture.runAsync(() -> {
                    synchronized (lock) {
                        results.add(n);
                    }
                });
            }
            CompletableFuture.allOf(futures).join();
            assertThat(results).containsExactlyInAnyOrder(0, 1, 2, 3, 4);
        }
    }

    /** The race first, then three fixes. The M6 last-OWNER bug had the same shape: read, decide, write. */
    @Nested
    class RaceConditions {

        static final int THREADS = 2;

        static class UnsafeCounter {
            int value;

            void increment() throws InterruptedException {
                int seen = value; // read
                Thread.sleep(1); // ...time passes: the other thread reads the same old value...
                value = seen + 1; // write: overwrites the other thread's update
            }
        }

        /** Runs {@code action} on {@code THREADS} threads that all start at the same moment. */
        private static void inParallel(ThrowingRunnable action) throws Exception {
            var start = new CyclicBarrier(THREADS);
            var done = new CountDownLatch(THREADS);
            try (var pool = Executors.newFixedThreadPool(THREADS)) {
                for (int i = 0; i < THREADS; i++) {
                    pool.execute(() -> {
                        try {
                            start.await();
                            action.run();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        } finally {
                            done.countDown();
                        }
                    });
                }
                assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            }
        }

        interface ThrowingRunnable {
            void run() throws Exception;
        }

        @Test
        void aLostUpdateHappensWhenTwoThreadsReadModifyWriteWithoutCoordination() throws Exception {
            var counter = new UnsafeCounter();
            inParallel(counter::increment);
            // Two increments, but the second one overwrote the first: the classic lost update.
            assertThat(counter.value).isEqualTo(1);
        }

        @Test
        void fixOneSynchronizedMakesTheWholeReadModifyWriteOneUnit() throws Exception {
            var counter = new UnsafeCounter();
            inParallel(() -> {
                synchronized (counter) {
                    counter.increment();
                }
            });
            assertThat(counter.value).isEqualTo(2);
        }

        @Test
        void fixTwoAnExplicitLockDoesTheSameAndAddsTryLockAndTimeouts() throws Exception {
            var counter = new UnsafeCounter();
            var lock = new ReentrantLock();
            inParallel(() -> {
                lock.lock();
                try {
                    counter.increment();
                } finally {
                    lock.unlock();
                }
            });
            assertThat(counter.value).isEqualTo(2);
        }

        @Test
        void fixThreeAnAtomicVariableRetriesTheUpdateInsteadOfLocking() throws Exception {
            var counter = new AtomicInteger();
            inParallel(() -> {
                Thread.sleep(1);
                counter.incrementAndGet(); // compare-and-set loop inside; no lock held while "time passes"
            });
            assertThat(counter.get()).isEqualTo(2);
        }
    }

    @Nested
    class ScopedValues {

        static final ScopedValue<String> REQUEST_ID = ScopedValue.newInstance();

        @Test
        void aValueIsBoundOnlyInsideTheScope() {
            assertThat(REQUEST_ID.isBound()).isFalse();
            ScopedValue.where(REQUEST_ID, "req-1").run(() -> {
                assertThat(REQUEST_ID.isBound()).isTrue();
                assertThat(REQUEST_ID.get()).isEqualTo("req-1");
            });
            assertThat(REQUEST_ID.isBound()).isFalse();
        }

        @Test
        void aNestedScopeRebindsAndTheOuterValueComesBack() {
            ScopedValue.where(REQUEST_ID, "outer").run(() -> {
                ScopedValue.where(REQUEST_ID, "inner")
                        .run(() -> assertThat(REQUEST_ID.get()).isEqualTo("inner"));
                assertThat(REQUEST_ID.get()).isEqualTo("outer");
            });
        }

        @Test
        void callReturnsAValueFromInsideTheScope() throws Exception {
            String result = ScopedValue.where(REQUEST_ID, "req-2").call(() -> "handled " + REQUEST_ID.get());
            assertThat(result).isEqualTo("handled req-2");
        }

        @Test
        void unlikeAThreadLocalItIsImmutableAndCannotLeakToTheNextRequest() throws Exception {
            var threadLocal = new ThreadLocal<String>();
            try (var pool = Executors.newSingleThreadExecutor()) {
                // A pooled thread that forgets to clean up leaks the value into the next task.
                pool.submit(() -> threadLocal.set("user-A")).get();
                assertThat(pool.submit(threadLocal::get).get()).isEqualTo("user-A");

                // A scoped value cannot outlive its scope, so there is nothing to forget.
                pool.submit(() -> ScopedValue.where(REQUEST_ID, "user-A").run(() -> {}))
                        .get();
                assertThat(pool.submit(REQUEST_ID::isBound).get()).isFalse();
            }
        }

        @Test
        void aNewThreadDoesNotInheritTheBindingUnlessTheScopeIsCarriedOver() throws Exception {
            ScopedValue.where(REQUEST_ID, "req-3").run(() -> {
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    // A plain executor thread has no binding...
                    assertThat(executor.submit(REQUEST_ID::isBound).get()).isFalse();
                    // ...so hand the value over explicitly, the way a Spring MDC filter would.
                    String captured = REQUEST_ID.get();
                    assertThat(executor.submit(() -> ScopedValue.where(REQUEST_ID, captured)
                                            .call(REQUEST_ID::get))
                                    .get())
                            .isEqualTo("req-3");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }
}
