package dev.gitdigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * What parallel enrichment has to keep true.
 *
 * <p>The stub stands in for GitHub so these can run in milliseconds and give
 * the same answer every time. The timing question - whether any of this is
 * actually faster - is a different kind of test and lives in
 * {@link EnrichmentBenchmark}.
 */
class ChangelogEnricherTest {

    private static final GitHubRepo REPO = new GitHubRepo("acme", "widgets");

    /**
     * Effectively no pacing. The bucket's own behaviour is covered by
     * {@link TokenBucketTest}; making these tests sit through GitHub's real
     * fifteen-a-second would buy nothing but minutes.
     */
    private static final double UNPACED = 100_000;

    /**
     * A source that answers instantly, counts what it was asked, and remembers
     * the most requests it was ever handling at once.
     */
    private static final class StubSource implements PullRequestSource {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger peakInFlight = new AtomicInteger();

        /** After this many calls every further lookup fails. -1 never fails. */
        int failAfter = -1;
        GitHubException failure = new GitHubException.RateLimited("GitHub rate limit reached.");
        /** Held long enough that overlapping requests actually overlap. */
        long holdMillis;

        @Override
        public List<PullRequest> pullRequestsForCommit(GitHubRepo repo, String sha) {
            int n = calls.incrementAndGet();
            peakInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                if (holdMillis > 0) {
                    Thread.sleep(holdMillis);
                }
                if (failAfter >= 0 && n > failAfter) {
                    throw failure;
                }
                return List.of(new PullRequest(n, "PR for " + sha, "someone",
                        "https://github.com/acme/widgets/pull/" + n));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GitHubException("interrupted", e);
            } finally {
                inFlight.decrementAndGet();
            }
        }

        @Override
        public boolean isAuthenticated() {
            return true;
        }
    }

    private static Changelog changelogOf(int features, int fixes) {
        Map<ChangeGroup, List<ChangelogEntry>> groups = new EnumMap<>(ChangeGroup.class);
        groups.put(ChangeGroup.FEATURES, entries("feat", features));
        groups.put(ChangeGroup.FIXES, entries("fix", fixes));
        return new Changelog("v1.0", "v2.0", groups);
    }

    private static List<ChangelogEntry> entries(String type, int count) {
        List<ChangelogEntry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // The sha doubles as the entry's identity, so order is checkable.
            String sha = String.format("%s%039d", type.charAt(0), i);
            entries.add(new ChangelogEntry(sha, type, null, false, type + " number " + i, "Ada", null));
        }
        return entries;
    }

    private static List<ChangelogEntry> allOf(Changelog changelog) {
        List<ChangelogEntry> all = new ArrayList<>();
        changelog.groups().values().forEach(all::addAll);
        return all;
    }

    @Test
    void everyEntryIsLookedUpExactlyOnce() {
        StubSource source = new StubSource();

        Changelog result = new ChangelogEnricher(source, REPO, quiet(), 8, UNPACED).enrich(changelogOf(30, 20));

        assertEquals(50, source.calls.get());
        assertEquals(50, result.totalEntries());
        assertTrue(allOf(result).stream().allMatch(e -> e.pullRequest() != null),
                "every entry should have come back with a pull request");
    }

    @Test
    void resultsGoBackWhereTheyCameFrom() {
        // Out-of-order completion is the whole risk of doing this in parallel:
        // the changelog has to stay in commit order regardless of who answered
        // first, and each entry has to keep its own pull request.
        StubSource source = new StubSource();
        source.holdMillis = 2;
        Changelog before = changelogOf(12, 8);

        Changelog after = new ChangelogEnricher(source, REPO, quiet(), 8, UNPACED).enrich(before);

        assertEquals(shas(before), shas(after));
        for (ChangelogEntry entry : allOf(after)) {
            assertNotNull(entry.pullRequest());
            assertEquals("PR for " + entry.sha(), entry.pullRequest().title());
        }
    }

    @Test
    void neverRunsMoreLookupsAtOnceThanAsked() {
        StubSource source = new StubSource();
        source.holdMillis = 15;

        new ChangelogEnricher(source, REPO, quiet(), 4, UNPACED).enrich(changelogOf(20, 20));

        assertTrue(source.peakInFlight.get() <= 4,
                "ran " + source.peakInFlight.get() + " lookups at once with --jobs 4");
        assertTrue(source.peakInFlight.get() > 1,
                "nothing actually ran in parallel");
    }

    @Test
    void oneJobMeansOneAtATime() {
        StubSource source = new StubSource();
        source.holdMillis = 5;

        Changelog result = new ChangelogEnricher(source, REPO, quiet(), 1, UNPACED).enrich(changelogOf(6, 4));

        assertEquals(1, source.peakInFlight.get());
        assertEquals(10, result.totalEntries());
    }

    @Test
    void stopsAskingOnceTheRateLimitIsGone() {
        StubSource source = new StubSource();
        source.failAfter = 5;
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        Changelog before = changelogOf(40, 40);

        Changelog after = new ChangelogEnricher(source, REPO, printTo(log), 4, UNPACED).enrich(before);

        assertEquals(80, after.totalEntries(), "no commit may be dropped by a failed lookup");
        assertEquals(shas(before), shas(after));
        assertTrue(source.calls.get() < 80,
                "kept asking after the limit was gone: " + source.calls.get() + " calls");
        assertTrue(allOf(after).stream().anyMatch(e -> e.pullRequest() == null),
                "the entries after the failure should have been left plain");
        assertEquals(1, countOf(log, "rate limit reached"),
                "four threads found the same limit; the user should hear about it once");
    }

    @Test
    void anUnreachableGitHubStillProducesAChangelog() {
        StubSource source = new StubSource();
        source.failAfter = 0;
        source.failure = new GitHubException("Could not reach GitHub: connection refused");
        ByteArrayOutputStream log = new ByteArrayOutputStream();

        Changelog after = new ChangelogEnricher(source, REPO, printTo(log), 8, UNPACED).enrich(changelogOf(10, 5));

        assertEquals(15, after.totalEntries());
        assertTrue(allOf(after).stream().allMatch(e -> e.pullRequest() == null));
        assertEquals(1, countOf(log, "could not reach GitHub"));
    }

    @Test
    void aCommitWithNoPullRequestIsLeftAlone() {
        PullRequestSource none = new PullRequestSource() {
            @Override
            public List<PullRequest> pullRequestsForCommit(GitHubRepo repo, String sha) {
                return List.of();
            }

            @Override
            public boolean isAuthenticated() {
                return false;
            }
        };

        Changelog after = new ChangelogEnricher(none, REPO, quiet(), 8, UNPACED).enrich(changelogOf(3, 3));

        assertEquals(6, after.totalEntries());
        allOf(after).forEach(entry -> assertNull(entry.pullRequest()));
    }

    @Test
    void anEmptyChangelogAsksNothingAndSaysNothing() {
        StubSource source = new StubSource();
        ByteArrayOutputStream log = new ByteArrayOutputStream();

        new ChangelogEnricher(source, REPO, printTo(log), 8, UNPACED)
                .enrich(new Changelog("v1.0", "v2.0", new EnumMap<>(ChangeGroup.class)));

        assertEquals(0, source.calls.get());
        assertEquals("", log.toString(StandardCharsets.UTF_8));
    }

    @Test
    void refusesAJobCountBelowOne() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChangelogEnricher(new StubSource(), REPO, quiet(), 0, UNPACED));
    }

    private static List<String> shas(Changelog changelog) {
        return allOf(changelog).stream().map(ChangelogEntry::sha).toList();
    }

    private static int countOf(ByteArrayOutputStream log, String needle) {
        String text = log.toString(StandardCharsets.UTF_8);
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    private static PrintStream printTo(ByteArrayOutputStream sink) {
        return new PrintStream(sink, true, StandardCharsets.UTF_8);
    }

    private static PrintStream quiet() {
        return printTo(new ByteArrayOutputStream());
    }
}
