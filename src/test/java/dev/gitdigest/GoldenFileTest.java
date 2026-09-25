package dev.gitdigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;

/**
 * Whole rendered outputs, compared against files checked into the tree.
 *
 * <p>The other tests assert that particular strings appear somewhere. This one
 * asserts the shape of the entire page - the blank lines, the ordering, the
 * heading levels - which is the part that quietly drifts when someone edits a
 * renderer, and the part no `contains` assertion would ever notice.
 *
 * <p>It works because the fixture is reproducible down to the commit hashes:
 * every author, message, timestamp and file body is fixed, and a git hash is a
 * function of exactly those. So the expected file can contain real short SHAs
 * instead of masked placeholders, and a hash that changes means the history
 * changed, which is worth failing over.
 *
 * <p>Run with {@code -Dgolden.update=true} to rewrite the expected files after
 * an intentional change, then read the diff before committing it. A golden test
 * that is updated without being read is just an expensive way to write
 * {@code assertTrue(true)}.
 */
class GoldenFileTest {

    private static final Path GOLDEN_DIRECTORY = Path.of("src", "test", "resources", "golden");

    @TempDir
    Path directory;

    /** A history with one of everything a changelog has to render. */
    private TestRepo fixture() throws Exception {
        TestRepo repo = TestRepo.at(directory);
        repo.commit("feat: add pagination to the search results", "src/search.java")
                .commit("fix: stop the login timeout on slow networks", "src/auth.java")
                .tag("v1.0")
                .commitAs("Grace Hopper", "grace@example.com",
                        "feat(api)!: drop the deprecated v1 endpoints", "src/api.java")
                .commit("fix(cli): report the right exit code on a bad path", "src/cli.java")
                .commit("chore: bump the gradle wrapper", "gradle.properties")
                .commit("docs: explain the --jobs flag", "README.md")
                .tag("v2.0");
        return repo;
    }

    private String render(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            int code = new CommandLine(new GitDigest())
                    .setCaseInsensitiveEnumValuesAllowed(true)
                    .setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF))
                    .execute(args);
            assertEquals(0, code, "the command under a golden test should succeed");
        } finally {
            System.setOut(original);
        }
        // Line endings are normalised because the renderer uses println, which
        // is CRLF on Windows and LF elsewhere. The file on disk would otherwise
        // depend on who last ran the suite.
        return out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private void matchesGolden(String name, String actual) throws Exception {
        Path expected = GOLDEN_DIRECTORY.resolve(name);
        if (Boolean.getBoolean("golden.update") || !Files.exists(expected)) {
            Files.createDirectories(GOLDEN_DIRECTORY);
            Files.writeString(expected, actual, StandardCharsets.UTF_8);
            assertTrue(Boolean.getBoolean("golden.update"),
                    "wrote a missing golden file at " + expected + " - read it, then re-run");
            return;
        }
        assertEquals(Files.readString(expected, StandardCharsets.UTF_8).replace("\r\n", "\n"), actual,
                "rendered output no longer matches " + expected
                        + "\nIf the change was intended, re-run with -Dgolden.update=true and read the diff.");
    }

    @Test
    void changelogMarkdown() throws Exception {
        try (TestRepo repo = fixture()) {
            matchesGolden("changelog.md",
                    render("changelog", directory.toString(), "--from", "v1.0", "--format", "markdown"));
        }
    }

    @Test
    void changelogTable() throws Exception {
        try (TestRepo repo = fixture()) {
            matchesGolden("changelog.txt",
                    render("changelog", directory.toString(), "--from", "v1.0"));
        }
    }

    @Test
    void releaseNotesOffline() throws Exception {
        // The page a stranger with no API key gets. Worth pinning whole: it is
        // the output most likely to be read by someone judging the project.
        try (TestRepo repo = fixture()) {
            matchesGolden("notes.md",
                    render("notes", directory.toString(), "--from", "v1.0", "--offline"));
        }
    }

    @Test
    void statsMarkdown() throws Exception {
        try (TestRepo repo = fixture()) {
            matchesGolden("stats.md",
                    render("stats", directory.toString(), "--format", "markdown"));
        }
    }
}
