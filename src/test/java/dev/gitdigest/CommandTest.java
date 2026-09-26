package dev.gitdigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;

/**
 * The commands themselves, run the way a user runs them.
 *
 * <p>Everything below this level has been tested in pieces. What has not is
 * the wiring: that a flag reaches the code that honours it, that stdout
 * carries only the report while warnings go to stderr, and above all that the
 * exit codes are the ones the README promises - which is the part a script
 * depends on and the part no unit test touches.
 */
class CommandTest {

    @TempDir
    Path directory;

    /** One run of the CLI: its exit code and the two streams, kept apart. */
    private record Run(int exitCode, String out, String err) {
    }

    private Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int code = new CommandLine(new GitDigest())
                    .setCaseInsensitiveEnumValuesAllowed(true)
                    // Matching main(): without it picocli's AUTO mode colours
                    // help text, and the harness would be testing something
                    // the real entry point never produces.
                    .setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF))
                    .execute(args);
            return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            // Restored in a finally block: a test that throws while stdout is
            // redirected would otherwise silence the whole rest of the suite.
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private String repo() {
        return directory.toString();
    }

    private TestRepo history() throws Exception {
        TestRepo repo = TestRepo.at(directory);
        repo.commit("feat: add pagination", "src/page.java")
                .commit("fix: stop the timeout", "src/net.java")
                .tag("v1.0")
                .commitAs("Grace Hopper", "grace@example.com", "feat(api)!: drop the v1 endpoints", "src/api.java")
                .commit("chore: bump the wrapper", "gradle.properties");
        return repo;
    }

    // --- exit codes, which are the contract a script relies on -------------

    @Test
    void aGoodRunExitsZero() throws Exception {
        try (TestRepo repo = history()) {
            assertEquals(0, run("stats", repo()).exitCode());
        }
    }

    @Test
    void aPathThatIsNotARepositoryExitsOneWithOneLine() {
        Run result = run("stats", repo());

        assertEquals(1, result.exitCode());
        assertTrue(result.err().contains("not a git repository"), result.err());
        assertTrue(result.out().isBlank(), "nothing should reach stdout on a failure: " + result.out());
    }

    @Test
    void anUnknownRevisionExitsOneAndNamesIt() throws Exception {
        try (TestRepo repo = history()) {
            Run result = run("changelog", repo(), "--from", "v9.9");

            assertEquals(1, result.exitCode());
            assertTrue(result.err().contains("v9.9"), result.err());
        }
    }

    @Test
    void argumentsThatDoNotParseExitTwo() {
        // Two, not one: the README distinguishes "you asked for something
        // impossible" from "you typed something I cannot read".
        assertEquals(2, run("stats", repo(), "--format", "hieroglyphs").exitCode());
        assertEquals(2, run("notplausibly-a-command").exitCode());
    }

    @Test
    void anEmptyRepositoryIsEmptyNotBrokenForEveryCommand() throws Exception {
        // The README promises exit 0 for a repository with no commits, on the
        // reasoning that `wc -l` succeeds on an empty file. That has to hold
        // for the whole tool: the same repository answering 0 to one command
        // and 1 to another is not a contract, it is an accident.
        try (TestRepo unusedButOpen = TestRepo.at(directory)) {
            Run stats = run("stats", repo());
            Run changelog = run("changelog", repo());
            Run notes = run("notes", repo(), "--offline");

            assertEquals(0, stats.exitCode(), stats.err());
            assertEquals(0, changelog.exitCode(), changelog.err());
            assertEquals(0, notes.exitCode(), notes.err());

            // stats needs no explanation - a report of zeros is the answer,
            // and it is on screen. The other two produce nothing visible, and
            // silent empty output is indistinguishable from a broken run.
            assertTrue(stats.out().contains("0"), stats.out());
            assertTrue(changelog.err().contains("no commits"), changelog.err());
            assertTrue(notes.err().contains("no commits"), notes.err());
        }
    }

    // --- stdout carries the report, stderr carries everything else ---------

    @Test
    void jsonOutputIsValidJsonWithNothingElseInIt() throws Exception {
        try (TestRepo repo = history()) {
            Run result = run("stats", repo(), "--format", "json");

            assertEquals(0, result.exitCode());
            JsonNode parsed = new ObjectMapper().readTree(result.out());
            assertEquals(4, parsed.path("totalCommits").asInt(), result.out());
        }
    }

    @Test
    void caseInsensitiveFormatsAreAccepted() throws Exception {
        try (TestRepo repo = history()) {
            assertEquals(0, run("stats", repo(), "--format", "JSON").exitCode());
        }
    }

    // --- flags reach the code that honours them ---------------------------

    @Test
    void theAuthorFilterActuallyFilters() throws Exception {
        try (TestRepo repo = history()) {
            Run mine = run("changelog", repo(), "--author", "Grace");

            assertEquals(0, mine.exitCode());
            assertTrue(mine.out().contains("drop the v1 endpoints"), mine.out());
            assertFalse(mine.out().contains("add pagination"), "Ada's commits should have been filtered out");
        }
    }

    @Test
    void theRangeFlagsActuallyNarrowTheRange() throws Exception {
        try (TestRepo repo = history()) {
            Run since = run("changelog", repo(), "--from", "v1.0");

            assertTrue(since.out().contains("drop the v1 endpoints"));
            assertFalse(since.out().contains("add pagination"), "v1.0 is excluded from its own range");
        }
    }

    @Test
    void conventionalPrefixesAreGroupedInTheOutput() throws Exception {
        try (TestRepo repo = history()) {
            String out = run("changelog", repo()).out();

            assertTrue(out.contains("Features"), out);
            assertTrue(out.contains("Bug Fixes"), out);
        }
    }

    // --- notes, offline, which is the path with no key --------------------

    @Test
    void notesRunOfflineWithoutAKeyOrANetwork() throws Exception {
        try (TestRepo repo = history()) {
            Run result = run("notes", repo(), "--offline");

            assertEquals(0, result.exitCode());
            assertTrue(result.out().startsWith("## "), result.out());
            assertTrue(result.err().contains("assembled from the commit history"),
                    "the user should be told which writer produced this: " + result.err());
        }
    }

    @Test
    void notesSayWhichWriterProducedThemOnStderrNotInTheNotes() throws Exception {
        try (TestRepo repo = history()) {
            Run result = run("notes", repo(), "--offline");

            assertFalse(result.out().contains("assembled from"),
                    "provenance in the notes themselves would end up in RELEASE_NOTES.md");
        }
    }

    @Test
    void theToneFlagReachesTheWriter() throws Exception {
        try (TestRepo repo = history()) {
            String formal = run("notes", repo(), "--offline", "--tone", "formal").out();
            String casual = run("notes", repo(), "--offline", "--tone", "casual").out();

            assertNotEquals(formal, casual);
            assertTrue(formal.contains("comprises"), formal);
            assertTrue(casual.contains("brings"), casual);
        }
    }

    @Test
    void anEmptyRangeIsNotAnError() throws Exception {
        // Asking what changed since the current commit is a fair question with
        // a boring answer, not a failure.
        try (TestRepo repo = history()) {
            Run result = run("notes", repo(), "--from", "HEAD", "--offline");

            assertEquals(0, result.exitCode());
            assertTrue(result.err().contains("nothing to write about"), result.err());
        }
    }

    @Test
    void jobsBelowOneIsRejectedAsAUsageError() throws Exception {
        try (TestRepo repo = history()) {
            assertEquals(2, run("changelog", repo(), "--jobs", "0").exitCode());
        }
    }

    // --- help and version, which are part of the interface ----------------

    @Test
    void everyCommandHasHelp() {
        for (String command : new String[] {"stats", "changelog", "notes"}) {
            Run result = run(command, "--help");

            assertEquals(0, result.exitCode(), command);
            assertTrue(result.out().contains("Usage: gitdigest " + command), command + ": " + result.out());
        }
    }

    @Test
    void versionIsReported() {
        Run result = run("--version");

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("gitdigest"), result.out());
        assertFalse(result.out().contains("unknown"),
                "the version resource the build generates should be on the classpath: " + result.out());
    }

    @Test
    void whatTheToolPrintsAboutItselfIsAscii() {
        // Text we choose, unlike a model's prose or a contributor's name, so
        // the cheap fix is available: stay inside ASCII and it cannot be
        // mangled by whatever charset stdout reports when redirected.
        for (String args : new String[] {"", "--version"}) {
            String out = args.isEmpty() ? run().out() : run(args).out();
            assertTrue(out.chars().allMatch(c -> c < 128),
                    "non-ASCII in output that does not need it: " + out);
        }
    }
}
