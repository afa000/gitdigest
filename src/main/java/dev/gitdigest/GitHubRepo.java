package dev.gitdigest;

import java.util.Locale;
import java.util.Optional;

/**
 * The owner and name of a repository on GitHub.
 *
 * @param owner the user or organisation, e.g. "afa000"
 * @param name  the repository name, e.g. "gitdigest"
 */
public record GitHubRepo(String owner, String name) {

    private static final String HOST = "github.com";
    private static final String SSH_PREFIX = "git@" + HOST + ":";
    private static final String GIT_SUFFIX = ".git";

    /**
     * Pulls the owner and name out of a git remote URL.
     *
     * <p>Handles the three shapes git hands out - HTTPS, scp-style SSH, and
     * ssh:// - and returns empty for anything not hosted on GitHub, so a GitLab
     * or self-hosted remote degrades quietly instead of producing nonsense.
     */
    public static Optional<GitHubRepo> parse(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            return Optional.empty();
        }

        String url = remoteUrl.trim();
        if (url.endsWith(GIT_SUFFIX)) {
            url = url.substring(0, url.length() - GIT_SUFFIX.length());
        }

        String path;
        if (url.toLowerCase(Locale.ROOT).startsWith(SSH_PREFIX)) {
            path = url.substring(SSH_PREFIX.length());
        } else {
            int host = url.toLowerCase(Locale.ROOT).indexOf(HOST + "/");
            if (host < 0) {
                return Optional.empty();
            }
            path = url.substring(host + HOST.length() + 1);
        }

        // Anything deeper than owner/name is not a repository root.
        String[] parts = path.split("/");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new GitHubRepo(parts[0], parts[1]));
    }

    @Override
    public String toString() {
        return owner + "/" + name;
    }
}
