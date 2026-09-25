package dev.gitdigest;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Turns a commit range into release notes someone would actually read.
 *
 * <p>Two writers sit behind this command. Claude writes the good version and
 * streams it in as it goes; a template assembles a plainer one from the same
 * data with no key, no network and no cost. The command picks between them and
 * says which it used, because notes that were written by a model and notes that
 * were assembled from commit subjects are different things and the person
 * reading them should know which they have.
 */
@Command(
        name = "notes",
        mixinStandardHelpOptions = true,
        description = "Write release notes for a commit range.")
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

    @Option(
            names = "--github",
            description = "Look up each commit's pull request first, so the notes can use PR "
                    + "titles and credit contributors by handle. Costs one request per commit.")
    boolean useGitHub;

    @Option(
            names = "--offline",
            description = "Skip the model and assemble the notes from the commits. This is also "
                    + "what happens on its own when no API key is configured.")
    boolean offline;

    /** Voice the generated notes should be written in. */
    public enum Tone {
        FORMAL,
        CASUAL;

        /** The lower-case name, for prose rather than for a switch. */
        public String label() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    @Override
    public Integer call() {
        Path where = repoPath.toAbsolutePath().normalize();
        try {
            List<CommitInfo> commits = new RepoReader().readRange(repoPath, fromRef, toRef);
            Changelog changelog = new ChangelogBuilder().build(commits, fromRef, toRef);

            if (changelog.totalEntries() == 0) {
                // Not an error: a range with nothing in it is a true answer to
                // the question that was asked.
                System.err.println("gitdigest: nothing to write about between "
                        + (fromRef == null ? "the first commit" : fromRef) + " and " + toRef + ".");
                return 0;
            }
            if (useGitHub) {
                changelog = enrich(changelog);
            }
            return writeNotes(changelog);
        } catch (NoHeadException e) {
            // Consistent with an empty range, a few lines above, which already
            // exits 0: there is nothing to write about either way.
            System.err.println("gitdigest: repository has no commits yet: " + where);
            return 0;
        } catch (IllegalArgumentException e) {
            System.err.println("gitdigest: " + e.getMessage());
            return 1;
        } catch (RepositoryNotFoundException e) {
            System.err.println("gitdigest: not a git repository: " + where);
            return 1;
        } catch (IOException | GitAPIException e) {
            System.err.println("gitdigest: could not read repository: " + where);
            System.err.println("  " + e.getMessage());
            return 1;
        }
    }

    /**
     * Writes the notes with the best writer available, degrading if it fails.
     *
     * <p>The fallback is the whole design, not a safety net bolted on: someone
     * who has just cloned this tool has no API key, and they should still get
     * release notes rather than an error telling them to go and buy one.
     */
    private Integer writeNotes(Changelog changelog) {
        if (offline) {
            return writeWithTemplate(changelog);
        }

        Optional<ClaudeNotesWriter> claude = ClaudeNotesWriter.fromEnvironment(System.err);
        if (claude.isEmpty()) {
            System.err.println("gitdigest: " + NotesException.Reason.CREDENTIALS.summary() + ".");
            advise(NotesException.Reason.CREDENTIALS);
            System.err.println("  Assembling the notes from the commits instead.");
            return writeWithTemplate(changelog);
        }

        PrintStream out = Terminal.utf8Out();
        try (ClaudeNotesWriter writer = claude.get()) {
            writer.write(changelog, tone, out);
            System.err.println("gitdigest: notes " + writer.describe() + ".");
            return 0;
        } catch (NotesException e) {
            if (e.isPartial()) {
                // Half a page is already on stdout. Anything else printed there
                // would be read as part of the notes, so the report goes to
                // stderr and the exit code carries the bad news.
                System.err.println("gitdigest: the model stopped partway through - " + e.getMessage() + ".");
                System.err.println("  The notes above are incomplete. Retry, or use --offline.");
                return 1;
            }
            System.err.println("gitdigest: " + e.getMessage() + ".");
            advise(e.reason());
            System.err.println("  Assembling the notes from the commits instead.");
            return writeWithTemplate(changelog);
        }
    }

    /** The one line of advice that is actually actionable for this failure. */
    private static void advise(NotesException.Reason reason) {
        switch (reason) {
            case CREDENTIALS -> System.err.println("  Set ANTHROPIC_API_KEY for notes written by Claude.");
            case RATE_LIMIT -> System.err.println("  Wait for the window to reset, or use --offline.");
            case NETWORK -> System.err.println("  Check the connection, or use --offline.");
            case OTHER -> {
                // Nothing useful to suggest; the message already said what
                // happened, and inventing advice would only waste a line.
            }
        }
    }

    private Integer writeWithTemplate(Changelog changelog) {
        ReleaseNotesWriter writer = new TemplateNotesWriter();
        writer.write(changelog, tone, Terminal.utf8Out());
        System.err.println("gitdigest: notes " + writer.describe() + ".");
        return 0;
    }

    /** Adds pull request titles and handles, or explains why it could not. */
    private Changelog enrich(Changelog changelog) throws IOException {
        Optional<GitHubRepo> repo = new RepoReader().readRemoteUrl(repoPath).flatMap(GitHubRepo::parse);
        if (repo.isEmpty()) {
            System.err.println("gitdigest: --github needs a GitHub 'origin' remote; skipping enrichment.");
            return changelog;
        }
        try (GitHubClient client = GitHubClient.fromEnvironment()) {
            return new ChangelogEnricher(client, repo.get(), System.err).enrich(changelog);
        }
    }
}
