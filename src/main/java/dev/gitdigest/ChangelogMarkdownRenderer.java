package dev.gitdigest;

import java.io.PrintStream;
import java.util.Locale;

public class ChangelogMarkdownRenderer implements ChangelogRenderer {

    @Override
    public void print(Changelog changelog, PrintStream out) {
        out.printf(Locale.ROOT, "# Changelog%n%n");
        out.printf(Locale.ROOT, "`%s` - %d change%s%n",
                ChangelogRenderer.rangeLabel(changelog),
                changelog.totalEntries(),
                changelog.totalEntries() == 1 ? "" : "s");

        if (changelog.totalEntries() == 0) {
            out.println();
            out.println("_No commits in this range._");
            return;
        }

        changelog.groups().forEach((group, entries) -> {
            out.printf(Locale.ROOT, "%n## %s%n%n", group.label());
            for (ChangelogEntry entry : entries) {
                out.printf(Locale.ROOT, "- %s (%s)%n",
                        ChangelogRenderer.describe(entry), reference(entry));
            }
        });
    }

    /**
     * The trailing reference: a real link to the pull request when GitHub gave
     * us one, and the bare hash otherwise.
     */
    private static String reference(ChangelogEntry entry) {
        PullRequest pr = entry.pullRequest();
        if (pr == null) {
            return "`" + entry.shortSha() + "`, " + entry.authorName();
        }
        String login = pr.authorLogin();
        String credit = login == null || login.isBlank() ? "" : ", @" + login;
        return "[#" + pr.number() + "](" + pr.url() + ")" + credit;
    }
}
