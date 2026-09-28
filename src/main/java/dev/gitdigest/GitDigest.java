package dev.gitdigest;

import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "gitdigest",
        mixinStandardHelpOptions = true,
        versionProvider = Version.class,
        description = "Git analytics, changelogs, and AI release notes from your terminal.",
        subcommands = {StatsCommand.class, ChangelogCommand.class, NotesCommand.class})
public class GitDigest implements Callable<Integer> {

    @Override
    public Integer call() {
        System.out.println("gitdigest " + Version.current() + " - run with --help to see available options");
        return 0;
    }

    /**
     * Turns an unhandled failure into a line of text.
     *
     * <p>The commands catch what they expect. Something unexpected - a library
     * throwing a RuntimeException from four frames down - would otherwise reach
     * picocli's default handler and print a Java stack trace at whoever ran the
     * command, which tells a user nothing they can act on and makes the tool
     * look broken in a way it often is not.
     *
     * <p>The stack trace is not thrown away, only moved: GITDIGEST_DEBUG=1
     * brings it back, because when the one-liner is not enough the trace is
     * exactly what is wanted.
     */
    private static CommandLine.IExecutionExceptionHandler unexpectedFailureHandler() {
        return (exception, command, parseResult) -> {
            System.err.println("gitdigest: " + describe(exception));
            if (System.getenv("GITDIGEST_DEBUG") != null) {
                exception.printStackTrace(System.err);
            } else {
                System.err.println("  Set GITDIGEST_DEBUG=1 for the full stack trace.");
            }
            return 1;
        };
    }

    private static String describe(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? "failed with " + exception.getClass().getSimpleName()
                : message;
    }

    /**
     * Applies the settings main() runs with. Shared with the tests, so the
     * harness cannot quietly drift from the real entry point.
     */
    static CommandLine configure(CommandLine commandLine, CommandLine.Help.Ansi ansi) {
        return commandLine
                // So that "--format json" works as well as "--format JSON".
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setExecutionExceptionHandler(unexpectedFailureHandler())
                .setColorScheme(CommandLine.Help.defaultColorScheme(ansi));
    }

    public static void main(String[] args) {
        // picocli's own AUTO mode keys off TERM, so it colours help text even
        // when stdout is a file. Decide from the stream instead.
        int exitCode = configure(new CommandLine(new GitDigest()), Terminal.ansi()).execute(args);
        System.exit(exitCode);
    }
}
