package dev.gitdigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

class ChangelogBuilderTest {

    @Test
    void splitsFeaturesFixesAndEverythingElse() {
        Changelog log = build(
                commit("feat: add search"),
                commit("fix: stop the crash"),
                commit("chore: bump deps"));

        assertEquals(1, log.groups().get(ChangeGroup.FEATURES).size());
        assertEquals(1, log.groups().get(ChangeGroup.FIXES).size());
        assertEquals(1, log.groups().get(ChangeGroup.OTHER).size());
        assertEquals(3, log.totalEntries());
    }

    @Test
    void groupsComeOutInPresentationOrder() {
        // built fix-first, but Features must still be presented first
        Changelog log = build(commit("fix: a"), commit("feat: b"), commit("chore: c"));

        assertEquals(
                List.of(ChangeGroup.FEATURES, ChangeGroup.FIXES, ChangeGroup.OTHER),
                List.copyOf(log.groups().keySet()));
    }

    @Test
    void emptyGroupsAreLeftOutEntirely() {
        Changelog log = build(commit("feat: only a feature"));

        assertEquals(List.of(ChangeGroup.FEATURES), List.copyOf(log.groups().keySet()));
        assertFalse(log.groups().containsKey(ChangeGroup.FIXES));
    }

    @Test
    void parsesScopeAndStripsThePrefix() {
        ChangelogEntry entry = build(commit("feat(api): add search")).groups()
                .get(ChangeGroup.FEATURES).get(0);

        assertEquals("feat", entry.type());
        assertEquals("api", entry.scope());
        assertEquals("add search", entry.description());
        assertFalse(entry.breaking());
    }

    @Test
    void recognisesTheBreakingMarker() {
        ChangelogEntry withScope = build(commit("feat(auth)!: drop tokens")).groups()
                .get(ChangeGroup.FEATURES).get(0);
        assertTrue(withScope.breaking());
        assertEquals("drop tokens", withScope.description());

        ChangelogEntry withoutScope = build(commit("feat!: drop tokens")).groups()
                .get(ChangeGroup.FEATURES).get(0);
        assertTrue(withoutScope.breaking());
        assertNull(withoutScope.scope());
    }

    @Test
    void keepsUnconventionalSubjectsInsteadOfDroppingThem() {
        ChangelogEntry entry = build(commit("Rewrote the parser entirely")).groups()
                .get(ChangeGroup.OTHER).get(0);

        assertNull(entry.type());
        assertEquals("Rewrote the parser entirely", entry.description(),
                "a subject that ignores the convention keeps its full text");
    }

    @Test
    void aColonAloneDoesNotMakeItConventional() {
        // no space after the colon, so this is prose, not "WIP" typed as a type
        ChangelogEntry entry = build(commit("WIP:something")).groups()
                .get(ChangeGroup.OTHER).get(0);

        assertNull(entry.type());
        assertEquals("WIP:something", entry.description());
    }

    @Test
    void typeIsCaseInsensitive() {
        Changelog log = build(commit("FEAT: shouting"));
        assertEquals("feat", log.groups().get(ChangeGroup.FEATURES).get(0).type());
    }

    @Test
    void theFullShaIsKeptAndAbbreviatedOnlyForDisplay() {
        ChangelogEntry entry = build(commit("feat: x")).groups().get(ChangeGroup.FEATURES).get(0);

        // the GitHub API needs the whole hash, so the entry keeps it
        assertEquals(40, entry.sha().length());
        assertEquals("0123456", entry.shortSha());
    }

    @Test
    void entriesStartWithoutAPullRequest() {
        ChangelogEntry entry = build(commit("feat: x")).groups().get(ChangeGroup.FEATURES).get(0);
        assertNull(entry.pullRequest(), "GitHub is only consulted when --github is passed");
    }

    @Test
    void rangeIsRecorded() {
        Changelog log = new ChangelogBuilder().build(List.of(), "v1.0", "v2.0");

        assertEquals("v1.0", log.fromRef());
        assertEquals("v2.0", log.toRef());
        assertEquals(0, log.totalEntries());
    }

    private static Changelog build(CommitInfo... commits) {
        return new ChangelogBuilder().build(List.of(commits), "v1.0", "v2.0");
    }

    private static CommitInfo commit(String subject) {
        return new CommitInfo(
                "0123456789abcdef0123456789abcdef01234567",
                "Dev One",
                "dev@example.com",
                LocalDateTime.parse("2026-08-03T10:00:00").atZone(ZoneId.of("America/Chicago")),
                subject,
                List.of());
    }
}
