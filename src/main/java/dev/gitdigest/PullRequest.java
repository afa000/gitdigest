package dev.gitdigest;

/**
 * The parts of a GitHub pull request a changelog cares about.
 *
 * @param number      the "#123" number
 * @param title       the PR title, usually better prose than the commit subject
 * @param authorLogin the GitHub login of whoever opened it, without the "@"
 * @param url         the PR's web page
 */
public record PullRequest(int number, String title, String authorLogin, String url) {
}
