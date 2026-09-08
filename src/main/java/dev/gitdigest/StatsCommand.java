package dev.gitdigest;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
        name = "stats",
        mixinStandardHelpOptions = true,
        description = "Show commit statistics for a repository.")
public class StatsCommand implements Callable<Integer> {

    @Parameters(
            index = "0",
            defaultValue = ".",
            description = "Path to a git repository (default: current directory).")
    Path repoPath;

    @Option(
            names = "--since",
            paramLabel = "<date>",
            description = "Only commits on or after this date, as YYYY-MM-DD.")
    LocalDate since;

    @Option(
            names = "--until",
            paramLabel = "<date>",
            description = "Only commits on or before this date, as YYYY-MM-DD.")
    LocalDate until;

    @Option(
            names = "--author",
            paramLabel = "<text>",
            description = "Only commits whose author name or email contains this text.")
    String author;

    @Option(
            names = "--format",
            paramLabel = "<format>",
            description = "Output format: ${COMPLETION-CANDIDATES}. Default: ${DEFAULT-VALUE}.")
    OutputFormat format = OutputFormat.TABLE;

    @Override
    public Integer call() {
        Path where = repoPath.toAbsolutePath().normalize();
        try {
            List<CommitInfo> commits = new RepoReader().readCommits(repoPath);
            List<CommitInfo> selected = new CommitFilter(since, until, author).apply(commits);
            RepoStats stats = new StatsCalculator().calculate(selected);
            StatsRenderer.forFormat(format).print(stats, System.out);
            return 0;
        } catch (NoHeadException e) {
            // A repository with no commits is empty, not broken: report zero
            // stats and exit 0, the way `wc -l` succeeds on an empty file.
            StatsRenderer.forFormat(format).print(new StatsCalculator().calculate(List.of()), System.out);
            return 0;
        } catch (RepositoryNotFoundException e) {
            // Must be caught before IOException: it is a subclass of one.
            System.err.println("gitdigest: not a git repository: " + where);
            return 1;
        } catch (IOException | GitAPIException e) {
            System.err.println("gitdigest: could not read repository: " + where);
            System.err.println("  " + e.getMessage());
            return 1;
        }
    }
}
