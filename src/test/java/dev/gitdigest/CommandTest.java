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
        return runCommand(new GitDigest(), args);
    }

    private Run runCommand(Object command, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            // Configured by the same method main() uses, with colour off as it
            // is whenever stdout is not a terminal.
            // Built only after the redirect: picocli captures System.out when
            // a CommandLine is constructed, not when it prints.
            int code = GitDigest.configure(new CommandLine(command), CommandLine.Help.Ansi.OFF).execute(args);
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

    // --- real-world repositories, which are not all ordinary clones -------

    @Test
    void aPartialCloneIsRefusedWithAnActionableMessageByEveryCommand() throws Exception {
        // Found by running the tool against picocli's history. A clone made
        // with --filter=blob:none has the commits but not the file contents,
        // and JGit gets several frames into a diff before noticing, then
        // reports "Error while parsing attributes" - which names neither the
        // cause nor anything the user could do.
        try (TestRepo repo = history()) {
            repo.git().getRepository().getConfig()
                    .setString("remote", "origin", "partialclonefilter", "blob:none");
            repo.git().getRepository().getConfig().save();

            for (String[] args : new String[][] {
                    {"stats", repo()}, {"changelog", repo()}, {"notes", repo(), "--offline"}}) {
                Run result = run(args);

                assertEquals(1, result.exitCode(), args[0]);
                assertTrue(result.err().contains("partial clone"), args[0] + ": " + result.err());
                assertTrue(result.err().contains("--filter"), args[0] + ": " + result.err());
                // --refetch alone re-applies the filter, so the advice has to
                // unset it first or following it changes nothing.
                assertTrue(result.err().contains("git config --unset remote.origin.partialclonefilter"),
                        args[0] + ": " + result.err());
                assertFalse(result.err().contains("stack trace"),
                        args[0] + " treated an actionable message as a crash: " + result.err());
                assertFalse(result.err().contains("org.eclipse.jgit"),
                        args[0] + " leaked a stack trace: " + result.err());
            }
        }
    }

    /** Stands in for a library failing somewhere nobody anticipated. */
    @CommandLine.Command(name = "boom")
    static class Boom implements java.util.concurrent.Callable<Integer> {
        @Override
        public Integer call() {
            throw new IllegalStateException("the library gave up");
        }
    }

    @Test
    void anUnexpectedFailureIsOneLineNotAStackTrace() {
        // The commands catch what they expect; this is the net under the rest.
        // A library throwing from four frames down should not print a Java
        // stack trace at someone who typed a command.
        Run result = runCommand(new Boom());

        assertEquals(1, result.exitCode());
        assertFalse(result.err().contains("\tat "), "a stack trace reached the user: " + result.err());
        assertTrue(result.err().startsWith("gitdigest: the library gave up"), result.err());
        assertTrue(result.err().contains("GITDIGEST_DEBUG"), result.err());
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
