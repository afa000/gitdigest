package dev.gitdigest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Groups commits into a changelog using conventional-commit subjects.
 *
 * <p>Pure logic with no I/O, so it is cheap to unit test.
 */
public class ChangelogBuilder {

    /**
     * Matches "type(scope)!: description", where scope and "!" are optional.
     * Character classes stand in for escaped parentheses to keep the literal
     * free of backslashes.
     */
    private static final Pattern CONVENTIONAL =
            Pattern.compile("^([a-zA-Z][a-zA-Z0-9]*)(?:[(]([^)]*)[)])?(!)?: +(.*)$");

    public Changelog build(List<CommitInfo> commits, String fromRef, String toRef) {
        Map<ChangeGroup, List<ChangelogEntry>> groups = new EnumMap<>(ChangeGroup.class);
        for (CommitInfo commit : commits) {
            ChangelogEntry entry = toEntry(commit);
            groups.computeIfAbsent(ChangeGroup.forType(entry.type()), key -> new ArrayList<>()).add(entry);
        }

        // An EnumMap keeps the buckets in ChangeGroup order; Map.copyOf would
        // give back an immutable map with no ordering guarantee at all.
        Map<ChangeGroup, List<ChangelogEntry>> ordered = new EnumMap<>(ChangeGroup.class);
        groups.forEach((group, entries) -> ordered.put(group, List.copyOf(entries)));
        return new Changelog(fromRef, toRef, Collections.unmodifiableMap(ordered));
    }

    /**
     * A subject that does not follow the convention still belongs in the
     * changelog: it keeps its full text and lands in "Other" rather than being
     * dropped for being untidy.
     */
    private static ChangelogEntry toEntry(CommitInfo commit) {
        Matcher matcher = CONVENTIONAL.matcher(commit.subject());
        if (!matcher.matches()) {
            return new ChangelogEntry(
                    commit.sha(), null, null, false, commit.subject(), commit.authorName(), null);
        }
        return new ChangelogEntry(
                commit.sha(),
                matcher.group(1).toLowerCase(Locale.ROOT),
                matcher.group(2),
                matcher.group(3) != null,
                matcher.group(4).trim(),
                commit.authorName(),
                null);
    }
}
