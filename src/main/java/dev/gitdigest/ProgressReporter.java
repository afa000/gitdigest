package dev.gitdigest;

import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A counter that rewrites itself in place: {@code Fetching pull requests 45/120}.
 *
 * <p>Two rules shape this class. It only redraws when the stream is a terminal,
 * because a carriage return in a log file is noise rather than animation; and
 * every write is serialized, because the whole point is that several threads
 * are reporting at once.
 *
 * <p>Redraws are also throttled. On a fast cached run the counter can advance
 * hundreds of times a second, and repainting on every one of those costs more
 * than the work being measured.
 */
final class ProgressReporter implements AutoCloseable {

    private static final long REDRAW_INTERVAL_NANOS = 60_000_000L; // 60ms

    private final PrintStream out;
    private final String label;
    private final int total;
    private final boolean inPlace;
    private final AtomicInteger done = new AtomicInteger();

    private long lastDrawNanos;
    private int lastDrawnWidth;

    ProgressReporter(PrintStream out, String label, int total) {
        this(out, label, total, Terminal.isInteractive());
    }

    ProgressReporter(PrintStream out, String label, int total, boolean inPlace) {
        this.out = out;
        this.label = label;
        this.total = total;
        this.inPlace = inPlace;
    }

    /** Records one finished item and repaints if enough time has passed. */
    void step() {
        int finished = done.incrementAndGet();
        if (!inPlace) {
            return;
        }
        synchronized (this) {
            long now = System.nanoTime();
            boolean last = finished >= total;
            if (!last && now - lastDrawNanos < REDRAW_INTERVAL_NANOS) {
                return;
            }
            lastDrawNanos = now;
            draw(finished);
        }
    }

    /**
     * Prints a line above the counter without the two fighting over the cursor.
     *
     * <p>Warnings during enrichment are the reason this exists: a message
     * written straight to the stream would land halfway through the counter and
     * leave the tail of it stranded on the line.
     */
    synchronized void message(String text) {
        clearLine();
        out.println(text);
        if (inPlace) {
            draw(done.get());
        }
    }

    private void draw(int finished) {
        String text = label + " " + finished + "/" + total;
        // Pad to whatever the previous line was, so a shrinking counter cannot
        // leave stale digits behind.
        String padded = text.length() < lastDrawnWidth
                ? text + " ".repeat(lastDrawnWidth - text.length())
                : text;
        lastDrawnWidth = text.length();
        out.print("\r" + padded);
        out.flush();
    }

    private void clearLine() {
        if (inPlace && lastDrawnWidth > 0) {
            out.print("\r" + " ".repeat(lastDrawnWidth) + "\r");
            lastDrawnWidth = 0;
        }
    }

    /** Erases the counter, leaving the terminal as it was found. */
    @Override
    public synchronized void close() {
        clearLine();
        out.flush();
    }
}
