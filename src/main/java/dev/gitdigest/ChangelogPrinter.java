package dev.gitdigest;

import java.io.PrintStream;
import java.util.Locale;

import picocli.CommandLine.Help.Ansi;

public class ChangelogPrinter implements ChangelogRenderer {

    private final boolean color;

    public ChangelogPrinter() {
        this(Terminal.supportsColor());
    }

    /** Visible for testing: pin the styling so output is deterministic. */
    ChangelogPrinter(boolean color) {
        this.color = color;
    }

    @Override
    public void print(Changelog changelog, PrintStream out) {
        out.printf(Locale.ROOT, "Changelog %s - %d change%s%n",
                ChangelogRenderer.rangeLabel(changelog),
                changelog.totalEntries(),
                changelog.totalEntries() == 1 ? "" : "s");

        if (changelog.totalEntries() == 0) {
            out.println();
            out.println("No commits in this range.");
            return;
        }

        changelog.groups().forEach((group, entries) -> {
            out.println();
            out.println(heading(group.label()));
            for (ChangelogEntry entry : entries) {
                out.printf(Locale.ROOT, "  %s  %s (%s)%n",
                        entry.shortSha(), ChangelogRenderer.describe(entry),
                        ChangelogRenderer.credit(entry));
            }
        });
    }

    /** Section headings are the only thing coloured; the data stays plain. */
    private String heading(String text) {
        return color ? Ansi.ON.string("@|bold " + text + "|@") : text;
    }
}
