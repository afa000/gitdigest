package dev.gitdigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The writer that has to work when nothing else does.
 *
 * <p>This is the one a stranger with no API key sees, so it gets held to the
 * same standard as the written notes on everything a template can be held to:
 * breaking changes surfaced, noise dropped, contributors credited, links live.
 */
class TemplateNotesWriterTest {

    private static String write(Changelog changelog, NotesCommand.Tone tone) {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        new TemplateNotesWriter().write(changelog, tone, new PrintStream(sink, true, StandardCharsets.UTF_8));
        return sink.toString(StandardCharsets.UTF_8);
    }

    private static Changelog changelogOf(Map<ChangeGroup, List<ChangelogEntry>> groups) {
        return new Changelog("v1.0", "v2.0", new EnumMap<>(groups));
    }

    private static ChangelogEntry entry(String type, String scope, boolean breaking, String description) {
        return new ChangelogEntry("0".repeat(40), type, scope, breaking, description, "Ada Lovelace", null);
    }

    private static Map<ChangeGroup, List<ChangelogEntry>> group(ChangeGroup key, ChangelogEntry... entries) {
        Map<ChangeGroup, List<ChangelogEntry>> groups = new EnumMap<>(ChangeGroup.class);
        groups.put(key, List.of(entries));
        return groups;
    }

    @Test
    void producesHeadingsAndBullets() {
        String notes = write(changelogOf(group(ChangeGroup.FEATURES,
                entry("feat", null, false, "add pagination"))), NotesCommand.Tone.FORMAL);

        assertTrue(notes.startsWith("## Changes from v1.0 to v2.0"));
        assertTrue(notes.contains("### Features"));
        assertTrue(notes.contains("- Add pagination"), "a bullet reads as a sentence, so it starts like one");
    }

    @Test
    void breakingChangesGetTheirOwnSectionAtTheTop() {
        String notes = write(changelogOf(group(ChangeGroup.FEATURES,
                entry("feat", null, false, "add pagination"),
                entry("feat", "api", true, "drop the v1 endpoints"))), NotesCommand.Tone.FORMAL);

        int breakingAt = notes.indexOf("### Breaking changes");
        int featuresAt = notes.indexOf("### Features");
        assertTrue(breakingAt >= 0, "a breaking change must not be left in with the rest");
        assertTrue(breakingAt < featuresAt, "someone deciding whether to upgrade reads the top of the page");
        assertTrue(notes.contains("**Breaking:**"));
    }

    @Test
    void aBreakingChangeIsListedOnceNotTwice() {
        // It has its own section; leaving it in its type group as well prints
        // the same change twice with nothing to say they are the same one.
        String notes = write(changelogOf(group(ChangeGroup.FEATURES,
                entry("feat", "api", true, "drop the v1 endpoints"))), NotesCommand.Tone.FORMAL);

        assertEquals(1, countOf(notes, "rop the v1 endpoints"), "listed twice in: " + notes);
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    @Test
    void noBreakingChangesMeansNoEmptySection() {
        String notes = write(changelogOf(group(ChangeGroup.FIXES,
                entry("fix", null, false, "fix a timeout"))), NotesCommand.Tone.FORMAL);

        assertFalse(notes.contains("Breaking"));
    }

    @Test
    void plumbingCommitsAreLeftOut() {
        String notes = write(changelogOf(group(ChangeGroup.OTHER,
                entry("chore", null, false, "bump the gradle wrapper"),
                entry("ci", null, false, "cache the build"),
                entry("docs", null, false, "document the --jobs flag"))), NotesCommand.Tone.FORMAL);

        assertFalse(notes.contains("bump the gradle wrapper"), "nobody upgrades for a wrapper bump");
        assertFalse(notes.contains("cache the build"));
        assertTrue(notes.contains("Document the --jobs flag"), "docs are a change a user can feel");
    }

    @Test
    void aBreakingChoreIsStillKept() {
        // The type says it is plumbing; the "!" says someone has to act on it.
        // The second one wins.
        String notes = write(changelogOf(group(ChangeGroup.OTHER,
                entry("chore", null, true, "drop support for java 21"))), NotesCommand.Tone.FORMAL);

        assertTrue(notes.contains("Drop support for java 21"));
    }

    @Test
    void pullRequestsBecomeLinks() {
        ChangelogEntry entry = entry("fix", null, false, "fix a timeout")
                .withPullRequest(new PullRequest(123, "Fix login timeout", "contributor",
                        "https://github.com/acme/widgets/pull/123"));

        String notes = write(changelogOf(group(ChangeGroup.FIXES, entry)), NotesCommand.Tone.FORMAL);

        assertTrue(notes.contains("[#123](https://github.com/acme/widgets/pull/123)"));
        assertTrue(notes.contains("@contributor"));
        assertTrue(notes.contains("### Contributors"));
    }

    @Test
    void toneChangesTheProseNotTheFacts() {
        Map<ChangeGroup, List<ChangelogEntry>> groups = group(ChangeGroup.FEATURES,
                entry("feat", null, false, "add pagination"));

        String formal = write(changelogOf(groups), NotesCommand.Tone.FORMAL);
        String casual = write(changelogOf(groups), NotesCommand.Tone.CASUAL);

        assertTrue(formal.contains("comprises 1 commit"));
        assertTrue(casual.contains("brings 1 commit"));
        for (String notes : List.of(formal, casual)) {
            assertTrue(notes.contains("- Add pagination"));
            assertTrue(notes.contains("1 contributor"), "singular, because there is one of them");
        }
    }

    @Test
    void aRangeOfOnlyNoiseStillProducesAValidPage() {
        // Degrading has to degrade all the way down: a heading and a count with
        // no sections is a correct answer, and an exception is not.
        String notes = write(changelogOf(group(ChangeGroup.OTHER,
                entry("chore", null, false, "bump versions"))), NotesCommand.Tone.FORMAL);

        assertTrue(notes.startsWith("## Changes from v1.0 to v2.0"));
        assertTrue(notes.contains("1 commit"));
        assertFalse(notes.contains("### Other"));
    }

    @Test
    void anOpenEndedRangeIsCalledARelease() {
        Changelog changelog = new Changelog(null, "v1.0", group(ChangeGroup.FEATURES,
                entry("feat", null, false, "the first thing")));

        assertTrue(write(changelog, NotesCommand.Tone.FORMAL).startsWith("## Release v1.0"));
    }

    @Test
    void severalContributorsAreAllCredited() {
        List<ChangelogEntry> entries = new ArrayList<>();
        entries.add(new ChangelogEntry("a".repeat(40), "feat", null, false, "one", "Ada Lovelace", null));
        entries.add(new ChangelogEntry("b".repeat(40), "feat", null, false, "two", "Grace Hopper", null));
        Map<ChangeGroup, List<ChangelogEntry>> groups = new EnumMap<>(ChangeGroup.class);
        groups.put(ChangeGroup.FEATURES, entries);

        String notes = write(changelogOf(groups), NotesCommand.Tone.FORMAL);

        assertTrue(notes.contains("Ada Lovelace, Grace Hopper"));
        assertTrue(notes.contains("2 contributors"));
    }
}
