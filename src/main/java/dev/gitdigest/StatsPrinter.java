package dev.gitdigest;

import java.io.PrintStream;
import java.time.DayOfWeek;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;

import picocli.CommandLine.Help.Ansi;

/**
 * Renders RepoStats as human-readable terminal output.
 * Takes a PrintStream instead of calling System.out directly,
 * so tests can capture the output.
 */
public class StatsPrinter implements StatsRenderer {

    private static final int BAR_WIDTH = 20;
    private static final int MAX_LABEL_WIDTH = 40;

    private final String block;
    private final boolean color;

    /** Adapts to whatever the terminal on the other end can handle. */
    public StatsPrinter() {
        this(Terminal.barCharacter(), Terminal.supportsColor());
    }

    /**
     * Pins the styling instead of detecting it, so output is identical
     * everywhere. Tests depend on this; detection would make them pass or fail
     * according to the terminal that happened to run them.
     */
    StatsPrinter(String block, boolean color) {
        this.block = block;
        this.color = color;
    }

    @Override
    public void print(RepoStats stats, PrintStream out) {
        out.printf(Locale.ROOT, "Repository stats - %d commit%s%n",
                stats.totalCommits(), stats.totalCommits() == 1 ? "" : "s");

        if (stats.totalCommits() == 0) {
            out.println();
            out.println("No commits to analyze.");
            return;
        }

        printRanked(out, "Commits per author", stats.commitsPerAuthor(), false);
        printRanked(out, "Top changed files", stats.topChangedFiles(), true);
        printByDay(out, stats.commitsByDayOfWeek());
        printByHour(out, stats.commitsByHour());
    }

    /** Prints an already-ranked map: label, bar, count. */
    private void printRanked(PrintStream out, String title, Map<String, Long> counts, boolean isPath) {
        out.println();
        out.println(heading(title));
        if (counts.isEmpty()) {
            out.println("  (no data)");
            return;
        }

        int labelWidth = Math.min(
                counts.keySet().stream().mapToInt(String::length).max().orElse(0),
                MAX_LABEL_WIDTH);
        long max = maxOf(counts.values());
        String format = "  %-" + labelWidth + "s  %-" + BAR_WIDTH + "s  %d%n";

        counts.forEach((label, count) ->
                out.printf(Locale.ROOT, format, truncate(label, labelWidth, isPath), bar(count, max), count));
    }

    /**
     * Every day is listed, including the quiet ones: a histogram with missing
     * rows is much harder to read than one with empty bars.
     */
    private void printByDay(PrintStream out, Map<DayOfWeek, Long> byDay) {
        out.println();
        out.println(heading("Activity by day"));
        long max = maxOf(byDay.values());
        for (DayOfWeek day : DayOfWeek.values()) {
            long count = byDay.getOrDefault(day, 0L);
            // name().substring beats getDisplayName here: no locale in the
            // output means the Phase 6 golden files stay stable everywhere.
            out.printf(Locale.ROOT, "  %-3s  %-" + BAR_WIDTH + "s  %d%n",
                    day.name().substring(0, 3), bar(count, max), count);
        }
    }

    private void printByHour(PrintStream out, Map<Integer, Long> byHour) {
        out.println();
        out.println(heading("Activity by hour"));
        long max = maxOf(byHour.values());
        for (int hour = 0; hour < 24; hour++) {
            long count = byHour.getOrDefault(hour, 0L);
            out.printf(Locale.ROOT, "  %02d  %-" + BAR_WIDTH + "s  %d%n",
                    hour, bar(count, max), count);
        }
    }

    /**
     * Scales a value to a bar of at most BAR_WIDTH blocks.
     *
     * <p>Integer division truncates, so on a repo where one author dwarfs the
     * rest the small values would all round down to an empty bar. Anything
     * non-zero therefore gets at least one block: the bar says "some" or
     * "none", never a misleading "none" for a real value.
     */
    private String bar(long value, long max) {
        if (value <= 0) {
            return "";
        }
        int width = (int) (value * BAR_WIDTH / max);
        return block.repeat(Math.max(1, width));
    }

    /** Section headings are the only thing coloured; the data stays plain. */
    private String heading(String text) {
        return color ? Ansi.ON.string("@|bold " + text + "|@") : text;
    }

    private static long maxOf(Collection<Long> values) {
        return values.stream().mapToLong(Long::longValue).max().orElse(1);
    }

    /**
     * Shortens an over-long label to {@code width}.
     *
     * <p>Paths are cut from the front: every file under a deep source tree
     * shares the same prefix, so trimming the tail would leave a column of
     * identical-looking directory names with the file names cut off.
     */
    private static String truncate(String text, int width, boolean isPath) {
        if (text.length() <= width) {
            return text;
        }
        return isPath
                ? "~" + text.substring(text.length() - (width - 1))
                : text.substring(0, width - 1) + "~";
    }
}
