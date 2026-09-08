package dev.gitdigest;

import java.io.PrintStream;
import java.time.DayOfWeek;
import java.util.Locale;
import java.util.Map;

public class StatsMarkdownRenderer implements StatsRenderer {

    @Override
    public void print(RepoStats stats, PrintStream out) {
        out.printf(Locale.ROOT, "# Repository stats%n%n");
        out.printf(Locale.ROOT, "**%d commit%s**%n",
                stats.totalCommits(), stats.totalCommits() == 1 ? "" : "s");

        if (stats.totalCommits() == 0) {
            out.println();
            out.println("No commits to analyze.");
            return;
        }

        table(out, "Commits per author", "Author", stats.commitsPerAuthor());
        table(out, "Top changed files", "File", stats.topChangedFiles());

        out.printf(Locale.ROOT, "%n## Activity by day%n%n");
        out.println("| Day | Commits |");
        out.println("|---|---:|");
        for (DayOfWeek day : DayOfWeek.values()) {
            out.printf(Locale.ROOT, "| %s | %d |%n", day.name(), stats.commitsByDayOfWeek().getOrDefault(day, 0L));
        }

        out.printf(Locale.ROOT, "%n## Activity by hour%n%n");
        out.println("| Hour | Commits |");
        out.println("|---|---:|");
        for (int hour = 0; hour < 24; hour++) {
            out.printf(Locale.ROOT, "| %02d | %d |%n", hour, stats.commitsByHour().getOrDefault(hour, 0L));
        }
    }

    private static void table(PrintStream out, String title, String keyHeading, Map<String, Long> counts) {
        out.printf(Locale.ROOT, "%n## %s%n%n", title);
        if (counts.isEmpty()) {
            out.println("_No data._");
            return;
        }
        out.printf(Locale.ROOT, "| %s | Commits |%n", keyHeading);
        out.println("|---|---:|");
        counts.forEach((key, count) -> out.printf(Locale.ROOT, "| %s | %d |%n", escapePipes(key), count));
    }

    /** A literal pipe would otherwise split the cell it sits in. */
    private static String escapePipes(String text) {
        return text.replace("|", "&#124;");
    }
}
