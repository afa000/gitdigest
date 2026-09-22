package dev.gitdigest;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Release notes assembled from the changelog, with no model involved.
 *
 * <p>This is what runs when there is no API key, when {@code --offline} is
 * passed, or when the API call fails partway. It is not trying to be as good
 * as the written version and does not pretend to be: it groups, it deduplicates
 * the obvious noise, it puts breaking changes where they cannot be missed, and
 * it credits people. What it cannot do is decide that six commits are one
 * story, which is the entire reason the other writer exists.
 *
 * <p>Designing for this case first is the point. A tool that needs a paid
 * credential to produce any output at all is a tool most people never see
 * working, and "install this, export a key, then it works" is a worse first
 * impression than slightly duller notes.
 */
public class TemplateNotesWriter implements ReleaseNotesWriter {

    /** Commits that describe the repository's plumbing rather than its behaviour. */
    private static final Set<String> NOISE_TYPES = Set.of("chore", "style", "ci", "build");

    @Override
    public String describe() {
        return "assembled from the commit history";
    }

    @Override
    public void write(Changelog changelog, NotesCommand.Tone tone, PrintStream out) {
        out.println("## " + heading(changelog));
        out.println();
        out.println(summaryLine(changelog, tone));

        List<ChangelogEntry> breaking = breakingIn(changelog);
        if (!breaking.isEmpty()) {
            out.println();
            out.println("### Breaking changes");
            out.println();
            breaking.forEach(entry -> out.println(bullet(entry)));
        }

        for (Map.Entry<ChangeGroup, List<ChangelogEntry>> group : changelog.groups().entrySet()) {
            List<ChangelogEntry> entries = worthListing(group.getValue());
            if (entries.isEmpty()) {
                continue;
            }
            out.println();
            out.println("### " + group.getKey().label());
            out.println();
            entries.forEach(entry -> out.println(bullet(entry)));
        }

        Set<String> contributors = contributorsOf(changelog);
        if (!contributors.isEmpty()) {
            out.println();
            out.println("### Contributors");
            out.println();
            out.println(String.join(", ", contributors));
        }
    }

    private static String heading(Changelog changelog) {
        return changelog.fromRef() == null
                ? "Release " + changelog.toRef()
                : "Changes from " + changelog.fromRef() + " to " + changelog.toRef();
    }

    private static String summaryLine(Changelog changelog, NotesCommand.Tone tone) {
        int commits = changelog.totalEntries();
        int people = contributorsOf(changelog).size();
        String commitCount = commits + (commits == 1 ? " commit" : " commits");
        String peopleCount = people + (people == 1 ? " contributor" : " contributors");

        return tone == NotesCommand.Tone.CASUAL
                ? "This release brings " + commitCount + " from " + peopleCount + "."
                : "This release comprises " + commitCount + " from " + peopleCount + ".";
    }

    private static List<ChangelogEntry> breakingIn(Changelog changelog) {
        List<ChangelogEntry> breaking = new ArrayList<>();
        changelog.groups().values()
                .forEach(group -> group.stream().filter(ChangelogEntry::breaking).forEach(breaking::add));
        return breaking;
    }

    /**
     * Drops the commits nobody upgrades for, and the ones already shown.
     *
     * <p>Breaking changes are excluded here because they have their own
     * section above. A "chore!" is still kept - not by this filter, but by
     * that section, which takes it whatever its type. Listing it in both
     * places would print the same change twice with nothing to say they are
     * the same one.
     */
    private static List<ChangelogEntry> worthListing(List<ChangelogEntry> entries) {
        return entries.stream()
                .filter(entry -> !entry.breaking())
                .filter(entry -> entry.type() == null || !NOISE_TYPES.contains(entry.type()))
                .toList();
    }

    private static String bullet(ChangelogEntry entry) {
        StringBuilder line = new StringBuilder("- ");
        if (entry.breaking()) {
            line.append("**Breaking:** ");
        }
        if (entry.scope() != null) {
            line.append("**").append(entry.scope()).append(":** ");
        }
        line.append(capitalise(entry.description()));

        PullRequest pr = entry.pullRequest();
        if (pr != null) {
            line.append(" ([#").append(pr.number()).append("](").append(pr.url()).append(')');
            if (!pr.authorLogin().isBlank()) {
                line.append(", @").append(pr.authorLogin());
            }
            line.append(')');
        }
        return line.toString();
    }

    /** Commit subjects are conventionally lower case; headings-worth lines are not. */
    private static String capitalise(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

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
