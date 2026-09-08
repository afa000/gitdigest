package dev.gitdigest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** How an entry reads once GitHub has, or has not, supplied a pull request. */
class ChangelogRendererTest {

    private static final PullRequest PR = new PullRequest(
            123, "Fix login timeout", "contributor", "https://github.com/o/r/pull/123");

    @Test
    void theTableShowsThePullRequestNumberAndLogin() {
        String out = render(new ChangelogPrinter(false), entry(PR));

        assertTrue(out.contains("#123"), out);
        assertTrue(out.contains("@contributor"), out);
        assertTrue(out.contains("Fix login timeout"), out);
    }

    @Test
    void markdownLinksToThePullRequest() {
        String out = render(new ChangelogMarkdownRenderer(), entry(PR));

        assertTrue(out.contains("[#123](https://github.com/o/r/pull/123)"), out);
        assertTrue(out.contains("@contributor"), out);
    }

    @Test
    void thePullRequestTitleWinsOverTheCommitSubject() {
        // the PR title was written for other people to read; the commit subject
        // was shorthand the author wrote for themselves
        String out = render(new ChangelogPrinter(false), entry(PR));

        assertTrue(out.contains("Fix login timeout"), out);
        assertTrue(!out.contains("fix tmout"), out);
    }

    @Test
    void withoutAPullRequestItFallsBackToTheHashAndAuthor() {
        String table = render(new ChangelogPrinter(false), entry(null));
        assertTrue(table.contains("fix tmout"), table);
        assertTrue(table.contains("Dev One"), table);

        String markdown = render(new ChangelogMarkdownRenderer(), entry(null));
        assertTrue(markdown.contains("`0123456`"), markdown);
        assertTrue(markdown.contains("Dev One"), markdown);
        assertTrue(!markdown.contains("[#"), markdown);
    }

    private static ChangelogEntry entry(PullRequest pr) {
        return new ChangelogEntry(
                "0123456789abcdef0123456789abcdef01234567",
                "fix", null, false, "fix tmout", "Dev One", pr);
    }

    private static String render(ChangelogRenderer renderer, ChangelogEntry entry) {
        Changelog log = new Changelog("v1.0", "v2.0", Map.of(ChangeGroup.FIXES, List.of(entry)));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        renderer.print(log, new PrintStream(buffer, true, StandardCharsets.UTF_8));
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
