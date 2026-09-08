package dev.gitdigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class StatsCalculatorTest {

    @Test
    void countsCommitsPerAuthor() {
        List<CommitInfo> commits = List.of(
                commit("Alice", "2026-08-03T10:15:00"),
                commit("Alice", "2026-08-04T11:00:00"),
                commit("Bob", "2026-08-04T12:30:00"));

        RepoStats stats = new StatsCalculator().calculate(commits);

        assertEquals(3, stats.totalCommits());
        assertEquals(2, stats.commitsPerAuthor().get("Alice"));
        assertEquals(1, stats.commitsPerAuthor().get("Bob"));
    }

    @Test
    void authorsAreSortedByCommitCountDescending() {
        List<CommitInfo> commits = List.of(
                commit("Bob", "2026-08-01T09:00:00"),
                commit("Alice", "2026-08-02T09:00:00"),
                commit("Alice", "2026-08-03T09:00:00"));

        RepoStats stats = new StatsCalculator().calculate(commits);

        // first key in the map must be the author with the most commits
        String firstAuthor = stats.commitsPerAuthor().keySet().iterator().next();
        assertEquals("Alice", firstAuthor);
    }

    @Test
    void countsCommitsByDayOfWeek() {
        List<CommitInfo> commits = List.of(
                commit("Alice", "2026-08-03T10:15:00"),  // Monday
                commit("Alice", "2026-08-04T11:00:00"),  // Tuesday
                commit("Bob", "2026-08-04T12:30:00"));   // Tuesday

        RepoStats stats = new StatsCalculator().calculate(commits);

        assertEquals(1, stats.commitsByDayOfWeek().get(DayOfWeek.MONDAY));
        assertEquals(2, stats.commitsByDayOfWeek().get(DayOfWeek.TUESDAY));
        assertFalse(stats.commitsByDayOfWeek().containsKey(DayOfWeek.WEDNESDAY));
    }

    @Test
    void countsCommitsByHour() {
        List<CommitInfo> commits = List.of(
                commit("Alice", "2026-08-03T10:15:00"),
                commit("Alice", "2026-08-03T10:45:00"),
                commit("Bob", "2026-08-03T14:05:00"));

        RepoStats stats = new StatsCalculator().calculate(commits);

        assertEquals(2, stats.commitsByHour().get(10));
        assertEquals(1, stats.commitsByHour().get(14));
    }

    @Test
    void countsFileChangesAcrossCommits() {
        List<CommitInfo> commits = List.of(
                commit("Alice", "2026-08-03T10:00:00", List.of("App.java", "README.md")),
                commit("Alice", "2026-08-04T10:00:00", List.of("App.java")),
                commit("Bob", "2026-08-05T10:00:00", List.of("App.java", "pom.xml")));

        RepoStats stats = new StatsCalculator().calculate(commits);

        assertEquals(3, stats.topChangedFiles().get("App.java"));
        assertEquals(1, stats.topChangedFiles().get("README.md"));

        // busiest file ranks first
        String firstFile = stats.topChangedFiles().keySet().iterator().next();
        assertEquals("App.java", firstFile);
    }

    @Test
    void topChangedFilesKeepsOnlyTheTopTen() {
        // 12 distinct files; file-12 is touched most, file-01 least
        List<CommitInfo> commits = new ArrayList<>();
        for (int fileNumber = 1; fileNumber <= 12; fileNumber++) {
            String path = String.format("file-%02d.java", fileNumber);
            for (int touch = 0; touch < fileNumber; touch++) {
                commits.add(commit("Alice", "2026-08-03T10:00:00", List.of(path)));
            }
        }

        RepoStats stats = new StatsCalculator().calculate(commits);

        assertEquals(10, stats.topChangedFiles().size());
        // the two least-touched files are dropped, the busiest is kept
        assertTrue(stats.topChangedFiles().containsKey("file-12.java"));
        assertFalse(stats.topChangedFiles().containsKey("file-01.java"));
        assertFalse(stats.topChangedFiles().containsKey("file-02.java"));
    }

    private static CommitInfo commit(String author, String isoDateTime) {
        return commit(author, isoDateTime, List.of());
    }

    private static CommitInfo commit(String author, String isoDateTime, List<String> files) {
        return new CommitInfo(
                "deadbeef",
                author,
                author.toLowerCase() + "@example.com",
                LocalDateTime.parse(isoDateTime).atZone(ZoneId.of("America/Chicago")),
                "test commit",
                files);
    }
}
