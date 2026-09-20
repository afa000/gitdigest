package dev.gitdigest;

import java.util.List;

/**
 * Where pull request data comes from.
 *
 * <p>{@link GitHubClient} is the only real implementation. The interface exists
 * so the enricher can be exercised at speed against a stub: the thing worth
 * testing about parallel enrichment is how it schedules, counts and degrades,
 * and none of that should need a socket to observe.
 */
public interface PullRequestSource {

    /** The pull requests a commit arrived through, newest first, possibly none. */
    List<PullRequest> pullRequestsForCommit(GitHubRepo repo, String sha);

    /** Whether a token was supplied, which decides the hourly allowance. */
    boolean isAuthenticated();
}
