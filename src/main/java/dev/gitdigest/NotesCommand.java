package dev.gitdigest;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Placeholder for the AI release-notes command.
 *
 * <p>The flags are declared now so that "gitdigest notes --help" already
 * describes the real shape of the command; the behaviour arrives in Phase 5.
 */
@Command(
        name = "notes",
        mixinStandardHelpOptions = true,
        description = "Generate release notes from a commit range (not implemented yet).")
public class NotesCommand implements Callable<Integer> {

    @Parameters(
            index = "0",
            defaultValue = ".",
            description = "Path to a git repository (default: current directory).")
    Path repoPath;

    @Option(names = "--from", paramLabel = "<rev>", description = "Starting revision, excluded.")
    String fromRef;

    @Option(names = "--to", paramLabel = "<rev>", defaultValue = "HEAD",
            description = "Ending revision, included. Default: ${DEFAULT-VALUE}.")
    String toRef;

    @Option(names = "--tone", paramLabel = "<tone>",
            description = "Writing style: ${COMPLETION-CANDIDATES}. Default: ${DEFAULT-VALUE}.")
    Tone tone = Tone.FORMAL;

    /** Voice the generated notes should be written in. */
    public enum Tone {
        FORMAL,
        CASUAL
    }

    @Override
    public Integer call() {
        System.err.println("gitdigest: 'notes' is not implemented yet - it arrives in Phase 5.");
        System.err.println("  Meanwhile, 'gitdigest changelog --from <rev> --to <rev>' lists the same range.");
        return 1;
    }
}
