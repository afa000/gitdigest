package dev.gitdigest;

import java.io.IOException;
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

@Command(
        name = "changelog",
        mixinStandardHelpOptions = true,
        description = "List the commits between two revisions, grouped by type.")
public class ChangelogCommand implements Callable<Integer> {

    @Parameters(
            index = "0",
            defaultValue = ".",
            description = "Path to a git repository (default: current directory).")
    Path repoPath;

    @Option(
            names = "--from",
            paramLabel = "<rev>",
            description = "Starting tag, branch or commit, excluded from the range. "
                    + "Omit to start at the first commit.")
    String fromRef;

    @Option(
            names = "--to",
            paramLabel = "<rev>",
            defaultValue = "HEAD",
            description = "Ending tag, branch or commit, included. Default: ${DEFAULT-VALUE}.")
    String toRef;

    @Option(
            names = "--author",
            paramLabel = "<text>",
            description = "Only commits whose author name or email contains this text.")
    String author;

    @Option(
            names = "--github",
            description = "Look up each commit's pull request on GitHub. Off by default: "
                    + "it costs one request per commit, and an unauthenticated caller only "
                    + "gets 60 an hour. Set GITHUB_TOKEN to raise that to 5000.")
    boolean useGitHub;

    @Option(
            names = "--jobs",
            paramLabel = "<n>",
            description = "How many GitHub lookups to run at once with --github. "
                    + "Default: ${DEFAULT-VALUE}. Use 1 for one at a time.")
    int jobs = ChangelogEnricher.DEFAULT_JOBS;

    @Option(
            names = "--format",
            paramLabel = "<format>",
            description = "Output format: ${COMPLETION-CANDIDATES}. Default: ${DEFAULT-VALUE}.")
    OutputFormat format = OutputFormat.TABLE;

    /**
     * Adds pull request data, or explains why it could not and carries on.
     *
     * <p>Progress and warnings go to stderr so that piping stdout to jq or to a
     * file still yields clean output.
     */
    private Changelog enrich(Changelog changelog) throws IOException {
        Optional<GitHubRepo> repo = new RepoReader().readRemoteUrl(repoPath).flatMap(GitHubRepo::parse);
        if (repo.isEmpty()) {
            System.err.println("gitdigest: --github needs a GitHub 'origin' remote; skipping enrichment.");
            return changelog;
        }
        try (GitHubClient client = GitHubClient.fromEnvironment()) {
            return new ChangelogEnricher(client, repo.get(), System.err, jobs).enrich(changelog);
        }
    }

    @Override
    public Integer call() {
        Path where = repoPath.toAbsolutePath().normalize();
        if (jobs < 1) {
            System.err.println("gitdigest: --jobs must be at least 1.");
            return 2;
        }
        try {
            List<CommitInfo> commits = new RepoReader().readRange(repoPath, fromRef, toRef);
            List<CommitInfo> selected = new CommitFilter(null, null, author).apply(commits);
            Changelog changelog = new ChangelogBuilder().build(selected, fromRef, toRef);
            if (useGitHub) {
                changelog = enrich(changelog);
            }
            ChangelogRenderer.forFormat(format).print(changelog, System.out);
            return 0;
        } catch (NoHeadException e) {
            // Empty, not broken - the same call stats makes, and the contract
            // the README states for the whole tool. An empty changelog is a
            // true answer about a repository with nothing in it.
            System.err.println("gitdigest: repository has no commits yet: " + where);
            ChangelogRenderer.forFormat(format)
                    .print(new ChangelogBuilder().build(List.of(), fromRef, toRef), System.out);
            return 0;
        } catch (IllegalArgumentException e) {
            // Raised by RepoReader when --from or --to names nothing that exists.
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
}
