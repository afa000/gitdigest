package dev.gitdigest;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Attaches GitHub pull request data to changelog entries.
 *
 * <p>One request per commit, which is honest but slow; Phase 4 is where that
 * gets parallelised.
 *
 * <p>Enrichment is decoration, not the point of the command, so every failure
 * here degrades instead of aborting: if the rate limit runs out halfway
 * through, the entries fetched so far keep their pull requests, the rest stay
 * plain, and the changelog still prints.
 */
public class ChangelogEnricher {

    private final GitHubClient client;
    private final GitHubRepo repo;
    private final PrintStream progress;

    /** Set once the API has told us to stop asking, so we ask only once more. */
    private boolean halted;

    public ChangelogEnricher(GitHubClient client, GitHubRepo repo, PrintStream progress) {
        this.client = client;
        this.repo = repo;
        this.progress = progress;
    }

    public Changelog enrich(Changelog changelog) {
        int total = changelog.totalEntries();
        if (total == 0) {
            return changelog;
        }

        progress.println("gitdigest: asking GitHub about " + total + " commit"
                + (total == 1 ? "" : "s") + " in " + repo
                + (client.isAuthenticated() ? "" : " (unauthenticated: 60 requests an hour)"));

        Map<ChangeGroup, List<ChangelogEntry>> enriched = new EnumMap<>(ChangeGroup.class);
        for (Map.Entry<ChangeGroup, List<ChangelogEntry>> group : changelog.groups().entrySet()) {
            List<ChangelogEntry> entries = new ArrayList<>();
            for (ChangelogEntry entry : group.getValue()) {
                entries.add(halted ? entry : lookUp(entry));
            }
            enriched.put(group.getKey(), List.copyOf(entries));
        }
        return new Changelog(changelog.fromRef(), changelog.toRef(), Collections.unmodifiableMap(enriched));
    }

    private ChangelogEntry lookUp(ChangelogEntry entry) {
        try {
            List<PullRequest> found = client.pullRequestsForCommit(repo, entry.sha());
            return found.isEmpty() ? entry : entry.withPullRequest(found.get(0));
        } catch (GitHubException.RateLimited e) {
            progress.println("gitdigest: " + e.getMessage());
            progress.println("gitdigest: continuing without pull request data for the rest.");
            halted = true;
            return entry;
        } catch (GitHubException e) {
            progress.println("gitdigest: could not reach GitHub (" + e.getMessage() + ").");
            progress.println("gitdigest: continuing without pull request data.");
            halted = true;
            return entry;
        }
    }
}
