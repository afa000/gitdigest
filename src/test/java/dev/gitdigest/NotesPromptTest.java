package dev.gitdigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * What the model is actually told.
 *
 * <p>A prompt is code that happens to be prose, and it fails the same way code
 * does - silently, when someone edits a sentence and drops the clause that was
 * carrying the weight. These pin down the parts that are load-bearing: that the
 * data all arrives, and that the instructions that keep the output honest are
 * still in there.
 */
class NotesPromptTest {

    private static Changelog changelog(String from, String to, ChangeGroup group, ChangelogEntry... entries) {
        Map<ChangeGroup, List<ChangelogEntry>> groups = new EnumMap<>(ChangeGroup.class);
        groups.put(group, List.of(entries));
        return new Changelog(from, to, groups);
    }

    private static ChangelogEntry entry(String type, String scope, boolean breaking, String description) {
        return new ChangelogEntry("0".repeat(40), type, scope, breaking, description, "Ada Lovelace", null);
    }

    @Test
    void everyCommitReachesThePrompt() {
        Changelog changelog = changelog("v1.0", "v2.0", ChangeGroup.FEATURES,
                entry("feat", null, false, "add pagination"),
                entry("feat", "cli", false, "add a --jobs flag"));

        String prompt = NotesPrompt.user(changelog);

        assertTrue(prompt.contains("add pagination"));
        assertTrue(prompt.contains("add a --jobs flag"));
        assertTrue(prompt.contains("(cli)"), "a scope tells the model where a change landed");
        assertTrue(prompt.contains("v1.0 to v2.0"));
        assertTrue(prompt.contains("Commits: 2"));
    }

    @Test
    void breakingChangesAreMarkedInTheData() {
        // The instructions ask for these to lead; that only works if the data
        // says which ones they are.
        Changelog changelog = changelog("v1.0", "v2.0", ChangeGroup.FEATURES,
                entry("feat", "api", true, "drop the v1 endpoints"));

        assertTrue(NotesPrompt.user(changelog).contains("BREAKING"));
    }

    @Test
    void pullRequestTitlesAndHandlesAreOffered() {
        ChangelogEntry plain = entry("fix", null, false, "fix timeout");
        ChangelogEntry withPr = plain.withPullRequest(
                new PullRequest(123, "Fix login timeout on slow networks", "contributor",
                        "https://github.com/acme/widgets/pull/123"));

        String prompt = NotesPrompt.user(changelog("v1.0", "v2.0", ChangeGroup.FIXES, withPr));

        assertTrue(prompt.contains("#123"));
        assertTrue(prompt.contains("Fix login timeout on slow networks"),
                "the PR title is usually better prose than the commit subject");
        assertTrue(prompt.contains("@contributor"));
        assertTrue(prompt.contains("Contributors: @contributor"),
                "a handle should be preferred over the commit author name");
    }

    @Test
    void aRedundantPullRequestTitleIsNotRepeated() {
        ChangelogEntry entry = entry("fix", null, false, "fix timeout")
                .withPullRequest(new PullRequest(7, "fix timeout", "someone", "https://example.com/7"));

        String line = NotesPrompt.user(changelog("v1.0", "v2.0", ChangeGroup.FIXES, entry));

        assertEquals(1, countOf(line, "fix timeout"), "the same words twice is just tokens");
    }

    @Test
    void eachContributorIsNamedOnce() {
        Changelog changelog = changelog("v1.0", "v2.0", ChangeGroup.FEATURES,
                entry("feat", null, false, "one"),
                entry("feat", null, false, "two"),
                entry("feat", null, false, "three"));

        // Three commits by one person is one contributor. Repeating the name
        // would read as three different people who happen to be called Ada.
        assertEquals("Contributors: Ada Lovelace", lineStartingWith(NotesPrompt.user(changelog), "Contributors: "));
    }

    @Test
    void anOpenEndedRangeSaysSo() {
        Changelog changelog = changelog(null, "HEAD", ChangeGroup.OTHER,
                entry(null, null, false, "initial commit"));

        assertTrue(NotesPrompt.user(changelog).contains("everything up to HEAD"));
    }

    @Test
    void theToneChangesTheInstructionsAndNothingElse() {
        String formal = NotesPrompt.system(NotesCommand.Tone.FORMAL);
        String casual = NotesPrompt.system(NotesCommand.Tone.CASUAL);

        assertNotEquals(formal, casual);
        assertTrue(formal.contains("Third person"));
        assertTrue(casual.contains("Second person"));
        // The rules that keep the notes truthful are not up for negotiation by
        // whoever picked a tone.
        for (String prompt : List.of(formal, casual)) {
            assertTrue(prompt.contains("Never invent a change"), "the anti-fabrication rule must survive both tones");
            assertTrue(prompt.contains("Output Markdown only"));
            assertTrue(prompt.contains("breaking changes first"));
        }
    }

    @Test
    void neitherToneAsksForHype() {
        assertTrue(NotesPrompt.system(NotesCommand.Tone.FORMAL).contains("no marketing language"));
        assertTrue(NotesPrompt.system(NotesCommand.Tone.CASUAL).contains("no hype"));
    }

    @Test
    void theSystemHalfCarriesNoCommitData() {
        // It is the half that should be byte-identical between runs; a commit
        // subject leaking into it would mean nothing ever cached.
        String prompt = NotesPrompt.system(NotesCommand.Tone.FORMAL);

        assertFalse(prompt.contains("v1.0"));
        assertFalse(prompt.contains("Commits:"));
    }

    private static String lineStartingWith(String text, String prefix) {
        return text.lines().filter(line -> line.startsWith(prefix)).findFirst().orElse("");
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }
}
