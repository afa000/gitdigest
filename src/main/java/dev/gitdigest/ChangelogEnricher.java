package dev.gitdigest;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Attaches GitHub pull request data to changelog entries, several at a time.
 *
 * <p>One request per commit is unavoidable - that is the shape of the API - but
 * making them one after another is not. Each lookup is almost entirely spent
 * waiting on a socket, so the work is latency-bound, and latency-bound work is
 * what virtual threads are for: a task per commit costs a few hundred bytes
 * rather than a megabyte of stack, so the tasks can simply all exist, and the
 * only thing that needs rationing is how many of them are allowed to be talking
 * to GitHub at once.
 *
 * <p>That rationing is two separate concerns, kept as two separate objects. A
 * {@link Semaphore} caps concurrent requests, because an API is entitled to not
 * be shouted at; a {@link TokenBucket} smooths the moment the burst lands,
 * which is what trips GitHub's secondary limits. Neither one can conjure extra
 * hourly quota, and this class does not pretend otherwise.
 *
 * <p>Enrichment is decoration, not the point of the command, so every failure
 * degrades instead of aborting: if the rate limit runs out halfway through, the
 * entries already fetched keep their pull requests, the rest stay plain, and
 * the changelog still prints. Under parallelism that promise needs one extra
 * care - the failure is noticed on one thread and has to stop the others, which
 * is what {@code halted} is for.
 */
public class ChangelogEnricher {

    /**
     * Enough to hide the latency, not enough to be rude.
     *
     * <p>GitHub asks callers to stay under 100 concurrent requests. The useful
     * range is well below that anyway: the gain flattens out once the pipe is
     * full, and every extra thread is one more request in flight when the quota
     * runs out and the run has to stop.
     */
    public static final int DEFAULT_JOBS = 8;

    /**
     * GitHub's own published ceiling: 900 points a minute for the REST API,
     * and a read costs one point.
     *
     * <p>This is the number the speedup is really bounded by. Eight threads do
     * not make the tool eight times faster once the range is long enough to hit
     * the pacer - they make it as fast as GitHub is willing to be asked, which
     * is the correct answer rather than a disappointing one.
     */
    private static final double REQUESTS_PER_SECOND = 15.0;

    private final PullRequestSource client;
    private final GitHubRepo repo;
    private final PrintStream progress;
    private final int jobs;
    private final double requestsPerSecond;

    /** Set once the API has told us to stop asking, so we stop asking. */
    private final AtomicBoolean halted = new AtomicBoolean();

    public ChangelogEnricher(PullRequestSource client, GitHubRepo repo, PrintStream progress) {
        this(client, repo, progress, DEFAULT_JOBS);
    }

    public ChangelogEnricher(PullRequestSource client, GitHubRepo repo, PrintStream progress, int jobs) {
        this(client, repo, progress, jobs, REQUESTS_PER_SECOND);
    }

    /**
     * @param requestsPerSecond the pacing rate, so a test with a stub for a
     *                          server is not made to wait at the speed of a
     *                          real one
     */
    ChangelogEnricher(PullRequestSource client, GitHubRepo repo, PrintStream progress, int jobs,
            double requestsPerSecond) {
        if (jobs < 1) {
            throw new IllegalArgumentException("jobs must be at least 1");
        }
        this.client = client;
        this.repo = repo;
        this.progress = progress;
        this.jobs = jobs;
        this.requestsPerSecond = requestsPerSecond;
    }

    public Changelog enrich(Changelog changelog) {
        int total = changelog.totalEntries();
        if (total == 0) {
            return changelog;
        }

        progress.println("gitdigest: asking GitHub about " + total + " commit"
                + (total == 1 ? "" : "s") + " in " + repo
                + (jobs > 1 && total > 1 ? " (" + Math.min(jobs, total) + " at a time)" : "")
                + (client.isAuthenticated() ? "" : " (unauthenticated: 60 requests an hour)"));

        Semaphore permits = new Semaphore(jobs);
        TokenBucket pace = new TokenBucket(requestsPerSecond, jobs);

        Map<ChangeGroup, List<ChangelogEntry>> enriched = new EnumMap<>(ChangeGroup.class);
        try (ProgressReporter bar = new ProgressReporter(progress, "gitdigest: fetching pull requests", total);
                ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {

            // Submit everything first, then collect. Submitting and waiting in
            // the same pass would run the lookups one at a time with extra
            // ceremony, which is the bug this phase exists to remove.
            Map<ChangeGroup, List<Future<ChangelogEntry>>> pending = new EnumMap<>(ChangeGroup.class);
            for (Map.Entry<ChangeGroup, List<ChangelogEntry>> group : changelog.groups().entrySet()) {
                List<Future<ChangelogEntry>> futures = new ArrayList<>(group.getValue().size());
                for (ChangelogEntry entry : group.getValue()) {
                    futures.add(pool.submit(() -> lookUp(entry, permits, pace, bar)));
                }
                pending.put(group.getKey(), futures);
            }

            // Results arrive in whatever order GitHub answers, but they are read
            // back by position, which is what keeps the changelog in commit order.
            for (Map.Entry<ChangeGroup, List<Future<ChangelogEntry>>> group : pending.entrySet()) {
                List<ChangelogEntry> original = changelog.groups().get(group.getKey());
                List<ChangelogEntry> entries = new ArrayList<>(group.getValue().size());
                for (int i = 0; i < group.getValue().size(); i++) {
                    entries.add(settle(group.getValue().get(i), original.get(i), bar));
                }
                enriched.put(group.getKey(), List.copyOf(entries));
            }
        }
        return new Changelog(changelog.fromRef(), changelog.toRef(), Collections.unmodifiableMap(enriched));
    }

    /**
     * Reads one result, falling back to the plain entry if the task could not
     * produce one.
     *
     * @param fallback the un-enriched entry: a changelog missing a pull request
     *                 title is worse, but a changelog missing a commit is wrong
     */
    private ChangelogEntry settle(Future<ChangelogEntry> future, ChangelogEntry fallback, ProgressReporter bar) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            halted.set(true);
            return fallback;
        } catch (ExecutionException e) {
            // lookUp handles the failures it expects, so anything arriving here
            // is a surprise; report it once and keep the entry.
            reportOnce(bar, "gitdigest: a pull request lookup failed (" + e.getCause() + ").");
            return fallback;
        }
    }

    private ChangelogEntry lookUp(ChangelogEntry entry, Semaphore permits, TokenBucket pace, ProgressReporter bar) {
        if (halted.get()) {
            bar.step();
            return entry;
        }
        try {
            permits.acquire();
            try {
                // Checked here as well as on the way in, and cheaply: by the
                // time a permit comes free, an earlier request may have found
                // the quota gone, and there is no sense paying the pacer to
                // wait for a request that is not going to be sent.
                if (halted.get()) {
                    return entry;
                }
                pace.acquire();
                if (halted.get()) {
                    return entry;
                }
                List<PullRequest> found = client.pullRequestsForCommit(repo, entry.sha());
                return found.isEmpty() ? entry : entry.withPullRequest(found.get(0));
            } finally {
                permits.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            halted.set(true);
            return entry;
        } catch (GitHubException.RateLimited e) {
            reportOnce(bar, "gitdigest: " + e.getMessage()
                    + System.lineSeparator()
                    + "gitdigest: continuing without pull request data for the rest.");
            return entry;
        } catch (GitHubException e) {
            reportOnce(bar, "gitdigest: could not reach GitHub (" + e.getMessage() + ")."
                    + System.lineSeparator()
                    + "gitdigest: continuing without pull request data.");
            return entry;
        } finally {
            bar.step();
        }
    }

    /**
     * Prints the first failure, and stops the rest of the run.
     *
     * <p>The compare-and-set does both jobs at once, which is the point: with
     * eight requests in flight the same rate limit is discovered eight times,
     * and the user should be told about it once. Routing the message through
     * the counter rather than the stream keeps it from landing in the middle of
     * a half-drawn line.
     */
    private void reportOnce(ProgressReporter bar, String message) {
        if (halted.compareAndSet(false, true)) {
            bar.message(message);
        }
    }
}
