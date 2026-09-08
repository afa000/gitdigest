package dev.gitdigest;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Narrows a list of commits by date range and author.
 *
 * <p>Pure logic with no I/O, so it is cheap to unit test. Any field may be
 * null, meaning "no constraint"; a filter with every field null keeps
 * everything.
 *
 * @param since  earliest date to keep, inclusive
 * @param until  latest date to keep, inclusive
 * @param author case-insensitive substring matched against name and email
 */
public record CommitFilter(LocalDate since, LocalDate until, String author) {

    public List<CommitInfo> apply(List<CommitInfo> commits) {
        return commits.stream().filter(this::matches).toList();
    }

    public boolean matches(CommitInfo commit) {
        return withinDates(commit) && byAuthor(commit);
    }

    /**
     * Compares against the commit's own local date.
     *
     * <p>The date the author saw on their calendar is the one they would filter
     * by, so a commit written late on the 3rd in Tokyo stays on the 3rd rather
     * than sliding to the 2nd because the report runs in New York.
     */
    private boolean withinDates(CommitInfo commit) {
        LocalDate date = commit.when().toLocalDate();
        if (since != null && date.isBefore(since)) {
            return false;
        }
        return until == null || !date.isAfter(until);
    }

    private boolean byAuthor(CommitInfo commit) {
        if (author == null) {
            return true;
        }
        String needle = author.toLowerCase(Locale.ROOT);
        return commit.authorName().toLowerCase(Locale.ROOT).contains(needle)
                || commit.authorEmail().toLowerCase(Locale.ROOT).contains(needle);
    }
}
