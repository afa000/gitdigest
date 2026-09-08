package dev.gitdigest;

import java.time.DayOfWeek;
import java.util.Map;

/**
 * Everything the stats command displays, pre-aggregated.
 *
 * @param totalCommits      total number of commits analyzed
 * @param commitsPerAuthor  author name -> commit count, highest first
 * @param topChangedFiles   file path -> change count, top 10 only, highest first
 * @param commitsByDayOfWeek MONDAY..SUNDAY -> commit count
 * @param commitsByHour     hour of day (0-23) -> commit count
 */
public record RepoStats(
        int totalCommits,
        Map<String, Long> commitsPerAuthor,
        Map<String, Long> topChangedFiles,
        Map<DayOfWeek, Long> commitsByDayOfWeek,
        Map<Integer, Long> commitsByHour) {
}
