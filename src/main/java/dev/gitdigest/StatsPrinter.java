package dev.gitdigest;

import java.io.PrintStream;
import java.time.DayOfWeek;
import java.util.Collection;
import java.util.LinkedHashMap;
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

    /**
     * Authors shown before the rest are summarised in one line. picocli has
     * 153, and a table that long scrolls its own heading off the screen; the
     * long tail is in the JSON and Markdown output, which are for keeping.
     */
    private static final int TOP_AUTHORS = 10;

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

        printRanked(out, "Commits per author", stats.commitsPerAuthor(), false, TOP_AUTHORS);
        printRanked(out, "Top changed files", stats.topChangedFiles(), true, Integer.MAX_VALUE);
        printByDay(out, stats.commitsByDayOfWeek());
        printByHour(out, stats.commitsByHour());
    }

    /** Prints an already-ranked map: label, bar, count, up to {@code limit} rows. */
    private void printRanked(PrintStream out, String title, Map<String, Long> ranked, boolean isPath, int limit) {
        out.println();
        out.println(heading(title));
        if (ranked.isEmpty()) {
            out.println("  (no data)");
            return;
        }
        // The map is already in rank order, so the first entries are the top.
        Map<String, Long> counts = new LinkedHashMap<>();
        ranked.entrySet().stream().limit(limit).forEach(e -> counts.put(e.getKey(), e.getValue()));

        int labelWidth = Math.min(
                counts.keySet().stream().mapToInt(String::length).max().orElse(0),
                MAX_LABEL_WIDTH);
        long max = maxOf(counts.values());
        String format = "  %-" + labelWidth + "s  %-" + BAR_WIDTH + "s  %d%n";

        counts.forEach((label, count) ->
                out.printf(Locale.ROOT, format, truncate(label, labelWidth, isPath), bar(count, max), count));

        int hidden = ranked.size() - counts.size();
        if (hidden > 0) {
            out.printf(Locale.ROOT, "  ... and %d more (--format json lists them all)%n", hidden);
        }
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
            // output means the golden files stay stable everywhere.
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

    /** Reads as "there is more to the left of this", in plain ASCII. */
    private static final String ELIDED = "...";

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
                ? elidePath(text, width)
                : text.substring(0, width - ELIDED.length()) + ELIDED;
    }

    /**
     * Drops leading directories, whole ones at a time.
     *
     * <p>Cutting a path at whatever character the width lands on produces
     * labels like "rc/main/resources/styles.css" - a path that does not exist,
     * out of a word that has been sliced in half. Keeping whole segments means
     * every label is a real suffix of a real path, which is what makes a
     * column of them comparable at a glance.
     */
    private static String elidePath(String path, int width) {
        String[] segments = path.split("/");
        String kept = "";
        // The separator stays after the ellipsis: ".../main/..." cannot be
        // misread as a cut word the way "...main/..." can.
        String prefix = ELIDED + "/";
        for (int i = segments.length - 1; i >= 0; i--) {
            String candidate = kept.isEmpty() ? segments[i] : segments[i] + "/" + kept;
            if (prefix.length() + candidate.length() > width) {
                break;
            }
            kept = candidate;
        }
        // A single file name longer than the column has no segment boundary
        // left to cut on, so it falls back to slicing characters.
        return kept.isEmpty()
                ? ELIDED + path.substring(path.length() - (width - ELIDED.length()))
                : prefix + kept;
    }
}
