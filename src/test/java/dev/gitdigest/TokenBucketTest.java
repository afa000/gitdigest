package dev.gitdigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * The bucket is tested against a clock it does not control, so the assertions
 * are about how long it asks to wait rather than how long the test takes. A
 * pacing test that actually paced would be slow and flaky for no gain.
 */
class TokenBucketTest {

    private static final long MILLIS = TimeUnit.MILLISECONDS.toNanos(1);

    /** A clock the test moves by hand, and a record of every sleep requested. */
    private static final class FakeTime {
        long nowNanos;
        final List<Long> sleeps = new ArrayList<>();

        TokenBucket bucket(double permitsPerSecond, int burst) {
            return new TokenBucket(permitsPerSecond, burst, () -> nowNanos, nanos -> {
                sleeps.add(nanos);
                // A real sleeper would have let the clock run; do the same, so
                // the caller's view of "now" is honest on the next acquire.
                nowNanos += nanos;
            });
        }
    }

    @Test
    void lettingTheBurstThroughImmediately() throws InterruptedException {
        FakeTime time = new FakeTime();
        TokenBucket bucket = time.bucket(10, 3); // one permit per 100ms

        bucket.acquire();
        bucket.acquire();
        bucket.acquire();

        assertEquals(List.of(), time.sleeps, "a burst of three should not have waited");
    }

    @Test
    void spacingOutWhatFollowsTheBurst() throws InterruptedException {
        FakeTime time = new FakeTime();
        TokenBucket bucket = time.bucket(10, 2);

        bucket.acquire();
        bucket.acquire();
        bucket.acquire();
        bucket.acquire();

        assertEquals(List.of(100 * MILLIS, 100 * MILLIS), time.sleeps);
    }

    @Test
    void idleTimeBuysBackTheBurstButNoMore() throws InterruptedException {
        FakeTime time = new FakeTime();
        TokenBucket bucket = time.bucket(10, 2);

        bucket.acquire();
        bucket.acquire();
        time.nowNanos += 10_000 * MILLIS; // ten idle seconds: a hundred intervals

        bucket.acquire();
        bucket.acquire();
        bucket.acquire();

        // Two free from the credit that was rebuilt, then back to the interval.
        // The cap is the point: ten idle seconds must not authorise a hundred
        // requests at once the moment work resumes.
        assertEquals(List.of(100 * MILLIS), time.sleeps);
    }

    @Test
    void aSingleSlotBucketNeverOverlaps() throws InterruptedException {
        FakeTime time = new FakeTime();
        TokenBucket bucket = time.bucket(4, 1); // one permit per 250ms

        bucket.acquire();
        bucket.acquire();
        bucket.acquire();

        assertEquals(List.of(250 * MILLIS, 250 * MILLIS), time.sleeps);
    }

    @Test
    void refusesASettingThatWouldNeverRelease() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(10, 0));
    }

    @Test
    void releasesEveryCallerUnderContention() throws InterruptedException {
        // The reservation is the only shared state; a lost update there would
        // strand a thread on a slot that has already been handed out.
        TokenBucket bucket = new TokenBucket(100_000, 1);
        int threads = 64;
        List<Thread> started = new ArrayList<>();
        AtomicInteger through = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            started.add(Thread.ofVirtual().start(() -> {
                try {
                    bucket.acquire();
                    through.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        for (Thread thread : started) {
            thread.join();
        }

        assertEquals(threads, through.get());
    }
}
