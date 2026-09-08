package dev.gitdigest;

/**
 * One line of a changelog.
 *
 * @param sha         the full commit hash; renderers abbreviate it, while the
 *                    GitHub API needs it whole
 * @param type        conventional-commit type such as "feat", or null if the
 *                    subject did not follow the convention
 * @param scope       the optional parenthesised scope, or null
 * @param breaking    whether the subject was marked with "!"
 * @param description the subject with any type and scope prefix removed
 * @param authorName  who wrote it
 * @param pullRequest the pull request it arrived through, or null if it was
 *                    pushed directly or GitHub was not consulted
 */
public record ChangelogEntry(
        String sha,
        String type,
        String scope,
        boolean breaking,
        String description,
        String authorName,
        PullRequest pullRequest) {

    /** The hash at the length people actually read. */
    public String shortSha() {
        return sha.length() <= 7 ? sha : sha.substring(0, 7);
    }

    public ChangelogEntry withPullRequest(PullRequest found) {
        return new ChangelogEntry(sha, type, scope, breaking, description, authorName, found);
    }
}
