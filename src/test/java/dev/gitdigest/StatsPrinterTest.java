package dev.gitdigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The styling is pinned rather than detected in these tests, so the expected
 * output does not change with the terminal that happens to run them.
 */
class StatsPrinterTest {

    @Test
    void theBiggestValueFillsTheBar() {
        String out = render(stats(Map.of("Alice", 10L)));

        assertTrue(out.contains("#".repeat(20)), out);
    }

    @Test
    void aSmallValueNeverRoundsDownToNothing() {
        // 1 of 100 scales to 1*20/100 = 0 in integer arithmetic; a real
        // contribution must not render as an empty bar
        Map<String, Long> authors = new LinkedHashMap<>();
        authors.put("Dominant", 100L);
        authors.put("Occasional", 1L);

        String line = lineContaining(render(stats(authors)), "Occasional");

        assertTrue(line.contains("#"), "a non-zero value must get at least one block: " + line);
        assertEquals(1, line.chars().filter(c -> c == '#').count(), line);
    }

    @Test
    void quietDaysAndHoursStillGetARow() {
        String out = render(stats(Map.of("Alice", 3L)));

        // every weekday appears, even the ones with no commits
        for (DayOfWeek day : DayOfWeek.values()) {
            assertTrue(out.contains(day.name().substring(0, 3)), "missing " + day + " in:\n" + out);
        }
        assertTrue(out.contains("  00  "), out);
        assertTrue(out.contains("  23  "), out);
    }

    @Test
    void aLongPathKeepsItsFileName() {
        RepoStats stats = new RepoStats(
                1,
                Map.of("Alice", 1L),
                Map.of("src/main/java/dev/gitdigest/AVeryLongClassNameIndeed.java", 1L),
                Map.of(DayOfWeek.MONDAY, 1L),
                Map.of(9, 1L));

        String line = lineContaining(render(stats), "Indeed.java");

        // elided from the front, so the identifying end survives
        assertTrue(line.contains("~"), line);
        assertTrue(line.contains("AVeryLongClassNameIndeed.java"), line);
    }

    @Test
    void anEmptyRepositorySaysSoInsteadOfPrintingEmptyTables() {
        RepoStats empty = new RepoStats(0, Map.of(), Map.of(), Map.of(), Map.of());
        String out = render(empty);

        assertTrue(out.contains("0 commits"), out);
        assertTrue(out.contains("No commits to analyze."), out);
        assertFalse(out.contains("Activity by day"), out);
    }

    @Test
    void filesSectionSaysSoWhenThereIsNoFileData() {
        RepoStats noFiles = new RepoStats(
                1, Map.of("Alice", 1L), Map.of(), Map.of(DayOfWeek.MONDAY, 1L), Map.of(9, 1L));

        assertTrue(render(noFiles).contains("(no data)"), render(noFiles));
    }

    @Test
    void colourIsEmittedOnlyWhenAskedFor() {
        RepoStats stats = stats(Map.of("Alice", 1L));
        String escape = String.valueOf((char) 27);

        assertFalse(render(stats).contains(escape), "plain mode must stay free of escape codes");
        assertTrue(new String(bytes(stats, true)).contains(escape), "colour mode must style the headings");
    }

    private static RepoStats stats(Map<String, Long> authors) {
        long total = authors.values().stream().mapToLong(Long::longValue).sum();
        return new RepoStats(
                (int) total,
                authors,
                Map.of("README.md", total),
                Map.of(DayOfWeek.MONDAY, total),
                Map.of(9, total));
    }

    private static String render(RepoStats stats) {
        return new String(bytes(stats, false), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(RepoStats stats, boolean color) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        new StatsPrinter("#", color).print(stats, new PrintStream(buffer, true, StandardCharsets.UTF_8));
        return buffer.toByteArray();
    }

    private static String lineContaining(String output, String needle) {
        return output.lines()
                .filter(line -> line.contains(needle))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no line containing " + needle + " in:\n" + output));
    }
}
