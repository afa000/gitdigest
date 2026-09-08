package dev.gitdigest;

import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "gitdigest",
        mixinStandardHelpOptions = true,
        version = "gitdigest 0.1.0",
        description = "Git analytics, changelogs, and AI release notes from your terminal.",
        subcommands = {StatsCommand.class, ChangelogCommand.class, NotesCommand.class})
public class GitDigest implements Callable<Integer> {

    @Override
    public Integer call() {
        System.out.println("gitdigest 0.1.0 — run with --help to see available options");
        return 0;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new GitDigest())
                // So that "--format json" works as well as "--format JSON".
                .setCaseInsensitiveEnumValuesAllowed(true)
                // picocli's own AUTO mode keys off TERM, so it colours help text
                // even when stdout is a file. Decide from the stream instead.
                .setColorScheme(CommandLine.Help.defaultColorScheme(Terminal.ansi()))
                .execute(args);
        System.exit(exitCode);
    }
}
