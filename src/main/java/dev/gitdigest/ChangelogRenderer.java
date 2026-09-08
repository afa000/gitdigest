package dev.gitdigest;

import java.io.PrintStream;

public interface ChangelogRenderer {

    void print(Changelog changelog, PrintStream out);

    static ChangelogRenderer forFormat(OutputFormat format) {
        return switch (format) {
            case TABLE -> new ChangelogPrinter();
            case JSON -> new ChangelogJsonRenderer();
            case MARKDOWN -> new ChangelogMarkdownRenderer();
        };
    }

    /**
     * Builds the human-readable half of an entry, shared by every format.
     *
     * <p>When GitHub supplied a pull request its title wins: it was written to
     * be read by other people, whereas a commit subject is often shorthand the
     * author wrote for themselves.
     */
    static String describe(ChangelogEntry entry) {
        StringBuilder text = new StringBuilder();
        if (entry.breaking()) {
            text.append("BREAKING ");
        }
        if (entry.scope() != null && !entry.scope().isBlank()) {
            text.append(entry.scope()).append(": ");
        }
        PullRequest pr = entry.pullRequest();
        return text.append(pr != null && !pr.title().isBlank() ? pr.title() : entry.description()).toString();
    }

    /** Trailing credit: "#123 (@login)" when GitHub had something to say. */
    static String credit(ChangelogEntry entry) {
        PullRequest pr = entry.pullRequest();
        if (pr == null) {
            return entry.authorName();
        }
        String login = pr.authorLogin();
        return "#" + pr.number() + (login == null || login.isBlank() ? "" : ", @" + login);
    }

    /** The range being described, for a heading. */
    static String rangeLabel(Changelog changelog) {
        return changelog.fromRef() == null
                ? changelog.toRef()
                : changelog.fromRef() + ".." + changelog.toRef();
    }
}
