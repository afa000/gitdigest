package dev.gitdigest;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a changelog into the two halves of a Claude request.
 *
 * <p>Split into a system half and a user half on purpose. The system half is
 * the same bytes on every run, which is what a cached prefix needs to be; the
 * commit data goes in the user half, where it is allowed to change. Only the
 * tone varies the system text, and there are two of those.
 *
 * <p>The class is pure, so the prompt can be tested for what it actually says
 * without spending a token on it. Prompt construction is the part of an LLM
 * integration most likely to quietly rot, and the part easiest to pin down.
 */
final class NotesPrompt {

    private NotesPrompt() {
    }

    /**
     * The instructions, which do not depend on the commits.
     *
     * <p>Most of this is about what not to do. A model handed a commit list
     * will happily open with "Here are the release notes for v2.0!", invent a
     * theme the commits do not support, and pad thin sections to look even;
     * release notes are read by people deciding whether to upgrade, and every
     * one of those habits costs them time.
     */
    static String system(NotesCommand.Tone tone) {
        return """
                You are writing release notes for a software project, from its \
                commit history.

                Rules:
                - Output Markdown only. No preamble, no sign-off, no commentary \
                about the task.
                - Start with a level-2 heading naming the release.
                - Lead with what changed for the people using the software, not \
                with how it was implemented.
                - Group related commits into a single entry when they describe \
                one change. Drop pure noise: merge commits, formatting, \
                version bumps, typo fixes in comments.
                - Call out breaking changes first, under their own heading, \
                whenever any are marked.
                - Never invent a change that is not in the data. If the range \
                is thin, the notes are short. That is a correct answer.
                - Keep pull request numbers as #123 so they stay linkable, and \
                credit contributors by their handle where one is given.
                """
                + "\n" + toneRule(tone);
    }

    private static String toneRule(NotesCommand.Tone tone) {
        return switch (tone) {
            case FORMAL -> """
                    Tone: measured and professional. Third person, complete \
                    sentences, no exclamation marks, no marketing language.\
                    """;
            case CASUAL -> """
                    Tone: warm and direct, the way a maintainer talks to users \
                    in a changelog. Second person is fine, contractions are \
                    fine. Still no hype: enthusiasm is not a substitute for \
                    telling someone what changed.\
                    """;
        };
    }

    /** The commits themselves, rendered compactly enough to leave room to think. */
    static String user(Changelog changelog) {
        StringBuilder text = new StringBuilder();
        text.append("Release: ").append(describeRange(changelog)).append('\n');
        text.append("Commits: ").append(changelog.totalEntries()).append('\n');

        Set<String> contributors = contributorsOf(changelog);
        if (!contributors.isEmpty()) {
            text.append("Contributors: ").append(String.join(", ", contributors)).append('\n');
        }

        for (Map.Entry<ChangeGroup, List<ChangelogEntry>> group : changelog.groups().entrySet()) {
            if (group.getValue().isEmpty()) {
                continue;
            }
            text.append('\n').append(group.getKey().label()).append(":\n");
            for (ChangelogEntry entry : group.getValue()) {
                text.append(line(entry)).append('\n');
            }
        }
        return text.toString();
    }

    private static String describeRange(Changelog changelog) {
        return changelog.fromRef() == null
                ? "everything up to " + changelog.toRef()
                : changelog.fromRef() + " to " + changelog.toRef();
    }

    /**
     * One commit, with everything the model could reasonably use and nothing
     * it could not: no hashes, no dates, no file lists.
     */
    private static String line(ChangelogEntry entry) {
        StringBuilder line = new StringBuilder("- ");
        if (entry.breaking()) {
            line.append("BREAKING ");
        }
        if (entry.scope() != null) {
            line.append('(').append(entry.scope()).append(") ");
        }
        line.append(entry.description());

        PullRequest pr = entry.pullRequest();
        if (pr != null) {
            line.append(" [#").append(pr.number());
            if (!pr.title().isBlank() && !pr.title().equals(entry.description())) {
                line.append(" \"").append(pr.title()).append('"');
            }
            if (!pr.authorLogin().isBlank()) {
                line.append(" by @").append(pr.authorLogin());
            }
            line.append(']');
        } else if (entry.authorName() != null && !entry.authorName().isBlank()) {
            line.append(" (").append(entry.authorName()).append(')');
        }
        return line.toString();
    }

    /**
     * Who to credit, preferring GitHub handles over commit names.
     *
     * <p>Ordered and de-duplicated: the same person commits many times, and a
     * list that repeats them is worse than useless to a model being asked who
     * contributed.
     */
    private static Set<String> contributorsOf(Changelog changelog) {
        Set<String> names = new LinkedHashSet<>();
        for (List<ChangelogEntry> group : changelog.groups().values()) {
            for (ChangelogEntry entry : group) {
                PullRequest pr = entry.pullRequest();
                if (pr != null && !pr.authorLogin().isBlank()) {
                    names.add("@" + pr.authorLogin());
                } else if (entry.authorName() != null && !entry.authorName().isBlank()) {
                    names.add(entry.authorName());
                }
            }
        }
        return names;
    }
}
