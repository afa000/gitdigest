package dev.gitdigest;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Paces requests so that a burst of parallel work does not arrive at an API all
 * at once.
 *
 * <p>This is deliberately <em>not</em> a fix for the hourly rate limit. Nothing
 * a client does can stretch a 5000-an-hour quota; when it is gone the only
 * honest moves are to stop and to say so, which is what
 * {@link ChangelogEnricher} does. What a bucket does prevent is GitHub's
 * secondary "abuse detection" limit, which reacts to the shape of the traffic
 * rather than its total - many requests landing in the same instant. So the
 * rate here sits comfortably above anything a changelog needs and only clips
 * the spike.
 *
 * <p>The implementation reserves a slot rather than counting tokens: each
 * caller takes the next free moment on the timeline and sleeps until it comes
 * round. Reserving is short and synchronized; sleeping happens outside the
 * lock, so waiting threads do not queue on the monitor as well as on the clock.
 */
final class TokenBucket {

    /** Lets a test observe the wait instead of living through it. */
    @FunctionalInterface
    interface Sleeper {
        void sleepNanos(long nanos) throws InterruptedException;
    }

    private final long intervalNanos;
    private final long burstNanos;
    private final LongSupplier clock;
    private final Sleeper sleeper;

    /** The earliest moment the next caller may go. */
    private long nextFreeNanos;

    TokenBucket(double permitsPerSecond, int burst) {
        this(permitsPerSecond, burst, System::nanoTime, nanos -> TimeUnit.NANOSECONDS.sleep(nanos));
    }

    TokenBucket(double permitsPerSecond, int burst, LongSupplier clock, Sleeper sleeper) {
        if (permitsPerSecond <= 0) {
            throw new IllegalArgumentException("permitsPerSecond must be positive");
        }
        if (burst < 1) {
            throw new IllegalArgumentException("burst must be at least 1");
        }
        this.intervalNanos = (long) (TimeUnit.SECONDS.toNanos(1) / permitsPerSecond);
        // Credit for idle time, capped so that a long pause buys exactly `burst`
        // immediate permits and not an unbounded backlog of them.
        this.burstNanos = intervalNanos * (burst - 1L);
        this.clock = clock;
        this.sleeper = sleeper;
        // Starting a full burst in credit, so an idle bucket does not make the
        // very first caller wait for a queue that does not exist yet.
        this.nextFreeNanos = clock.getAsLong() - burstNanos;
    }

    /** Waits, if it has to, until this caller's turn. */
    void acquire() throws InterruptedException {
        long now = clock.getAsLong();
        long goAt = reserve(now);
        long wait = goAt - now;
        if (wait > 0) {
            sleeper.sleepNanos(wait);
        }
    }

    private synchronized long reserve(long now) {
        // Subtracting the burst credit is what lets an idle bucket fire several
        // permits back to back; without it the very first callers would be
        // spaced out too, for no reason.
        long goAt = Math.max(nextFreeNanos, now - burstNanos);
        nextFreeNanos = goAt + intervalNanos;
        return goAt;
    }
}
