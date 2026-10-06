package dev.gitdigest;

public class GitHubException extends RuntimeException {

    public GitHubException(String message) {
        super(message);
    }

    public GitHubException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * The API refused the request because the rate limit is spent.
     *
     * <p>Kept separate because the advice differs: waiting will fix this one,
     * and an unauthenticated caller can fix it now by setting GITHUB_TOKEN.
     */
    public static class RateLimited extends GitHubException {
        public RateLimited(String message) {
            super(message);
        }
    }

    /**
     * GitHub does not have this commit - usually because it has not been
     * pushed yet.
     *
     * <p>Kept separate because, unlike every other failure here, it says
     * nothing about the next request: the commit after it may well be on
     * GitHub, so it must not stop the run.
     */
    public static class UnknownCommit extends GitHubException {
        public UnknownCommit(String message) {
            super(message);
        }
    }
}
