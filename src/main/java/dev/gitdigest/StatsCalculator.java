package dev.gitdigest;

import java.time.DayOfWeek;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Pure logic: turns a list of commits into aggregated stats.
 * No I/O, no JGit — which is exactly why it's easy to unit test.
 */
public class StatsCalculator {

    private static final int TOP_FILES_LIMIT = 10;

    public RepoStats calculate(List<CommitInfo> commits) {
        Map<String, Long> perAuthor = commits.stream()
                .collect(Collectors.groupingBy(CommitInfo::authorName, Collectors.counting()));

        Map<String, Long> perFile = commits.stream()
                .flatMap(commit -> commit.filesChanged().stream())
                .collect(Collectors.groupingBy(path -> path, Collectors.counting()));

        // Day and hour keep their natural order (MONDAY..SUNDAY, 0..23) rather
        // than being ranked by count: a histogram only reads correctly in
        // chronological order.
        Map<DayOfWeek, Long> perDay = commits.stream()
                .collect(Collectors.groupingBy(
                        commit -> commit.when().getDayOfWeek(),
                        TreeMap::new,
                        Collectors.counting()));

        Map<Integer, Long> perHour = commits.stream()
                .collect(Collectors.groupingBy(
                        commit -> commit.when().getHour(),
                        TreeMap::new,
                        Collectors.counting()));

        return new RepoStats(
                commits.size(),
                rankByCountDesc(perAuthor, Integer.MAX_VALUE),
                rankByCountDesc(perFile, TOP_FILES_LIMIT),
                perDay,
                perHour);
    }

    /**
     * Re-collects a count map into a LinkedHashMap ordered by count descending,
     * keeping at most {@code limit} entries.
     *
     * <p>Equal counts are broken by key so the ordering is total: without that,
     * two authors with the same number of commits could swap places between
     * runs, which would make the golden-file tests in Phase 6 flaky.
     */
    private static <K extends Comparable<K>> Map<K, Long> rankByCountDesc(Map<K, Long> counts, int limit) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<K, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new));
    }
}
