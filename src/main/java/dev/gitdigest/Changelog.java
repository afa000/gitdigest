package dev.gitdigest;

import java.util.List;
import java.util.Map;

/**
 * Commits between two revisions, grouped by conventional-commit type.
 *
 * @param fromRef the excluded starting revision, or null when the range is
 *                open-ended and runs back to the first commit
 * @param toRef   the included ending revision
 * @param groups  bucket to entries, in ChangeGroup order; empty buckets are
 *                left out entirely
 */
public record Changelog(String fromRef, String toRef, Map<ChangeGroup, List<ChangelogEntry>> groups) {

    public int totalEntries() {
        return groups.values().stream().mapToInt(List::size).sum();
    }
}
