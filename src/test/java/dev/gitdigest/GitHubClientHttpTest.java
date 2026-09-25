package dev.gitdigest;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * How the GitHub client behaves on the wire.
 *
 * <p>Everything this class does well is invisible from its return values: it
 * follows Link headers instead of counting pages, it revalidates with an ETag
 * so an unchanged response is not sent twice, and it tells a spent rate limit
 * apart from a plain refusal. None of that can be checked by looking at a list
 * of pull requests - it has to be checked by looking at the requests.
 *
 * <p>That is what WireMock is here for rather than a hand-rolled server. These
 * tests are about sequences (a 200 carrying an ETag, then a conditional
 * request that must come back 304) and about asserting what was *sent*, which
 * is the part a plain HttpServer makes awkward.
 */
class GitHubClientHttpTest {

    @RegisterExtension
    static final WireMockExtension SERVER = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static final GitHubRepo REPO = new GitHubRepo("acme", "widgets");
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String PULLS = "/repos/acme/widgets/commits/" + SHA + "/pulls";

    @TempDir
    Path cacheDirectory;

    private GitHubClient client(String token) {
        return new GitHubClient(HttpClient.newHttpClient(), new HttpCache(cacheDirectory), token,
                SERVER.baseUrl());
    }

    private static String pullRequestJson(int number, String title) {
        return "[{\"number\":" + number + ",\"title\":\"" + title + "\","
                + "\"user\":{\"login\":\"contributor\"},"
                + "\"html_url\":\"https://github.com/acme/widgets/pull/" + number + "\"}]";
    }

    // --- pagination -------------------------------------------------------

    @Test
    void followsLinkHeadersUntilThePagesRunOut() {
        // The next URL comes from the response, not from a page counter we
        // invent. A client that guessed "?page=2" would still pass a test that
        // only looked at the returned list.
        SERVER.stubFor(get(urlPathEqualTo(PULLS)).withQueryParam("page", absent())
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Link", "<" + SERVER.baseUrl() + PULLS + "?page=2>; rel=\"next\"")
                        .withBody(pullRequestJson(1, "First"))));
        SERVER.stubFor(get(urlPathEqualTo(PULLS)).withQueryParam("page", equalTo("2"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(pullRequestJson(2, "Second"))));

        try (GitHubClient client = client("token")) {
            List<PullRequest> found = client.pullRequestsForCommit(REPO, SHA);

            assertEquals(List.of(1, 2), found.stream().map(PullRequest::number).toList());
        }
        SERVER.verify(2, getRequestedFor(urlPathEqualTo(PULLS)));
    }

    @Test
    void aSinglePageIsOneRequest() {
        SERVER.stubFor(get(urlPathEqualTo(PULLS)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(pullRequestJson(7, "Only"))));

        try (GitHubClient client = client("token")) {
            assertEquals(1, client.pullRequestsForCommit(REPO, SHA).size());
        }
        SERVER.verify(1, getRequestedFor(urlPathEqualTo(PULLS)));
    }

    @Test
    void aCommitThatBelongsToNoPullRequestIsNotAnError() {
        // Pushed straight to a branch. Normal, not a failure.
        SERVER.stubFor(get(urlPathEqualTo(PULLS)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("[]")));

        try (GitHubClient client = client("token")) {
            assertEquals(List.of(), client.pullRequestsForCommit(REPO, SHA));
        }
    }

    // --- conditional requests --------------------------------------------

    @Test
    void revalidatesWithTheEtagAndReusesTheCachedBody() {
        SERVER.stubFor(get(urlPathEqualTo(PULLS)).withHeader("If-None-Match", absent())
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("ETag", "\"abc123\"")
                        .withBody(pullRequestJson(42, "Cached title"))));
        SERVER.stubFor(get(urlPathEqualTo(PULLS)).withHeader("If-None-Match", equalTo("\"abc123\""))
                // 304, and deliberately no body at all - the point of the cache
                // is that the client can answer without one.
                .willReturn(aResponse().withStatus(304)));

        try (GitHubClient first = client("token")) {
            assertEquals("Cached title", first.pullRequestsForCommit(REPO, SHA).get(0).title());
        }
        try (GitHubClient second = client("token")) {
            assertEquals("Cached title", second.pullRequestsForCommit(REPO, SHA).get(0).title(),
                    "a 304 should be answered from the cache, not turned into an empty result");
        }

        SERVER.verify(1, getRequestedFor(urlPathEqualTo(PULLS)).withHeader("If-None-Match", absent()));
        SERVER.verify(1, getRequestedFor(urlPathEqualTo(PULLS))
                .withHeader("If-None-Match", equalTo("\"abc123\"")));
    }

    @Test
    void aResponseWithNoEtagIsNotCached() {
        // There would be nothing to revalidate against, so the second run has
        // to ask again rather than serve something it cannot check.
        SERVER.stubFor(get(urlPathEqualTo(PULLS)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(pullRequestJson(1, "Uncacheable"))));

        try (GitHubClient first = client("token")) {
            first.pullRequestsForCommit(REPO, SHA);
        }
        try (GitHubClient second = client("token")) {
            second.pullRequestsForCommit(REPO, SHA);
        }

        SERVER.verify(2, getRequestedFor(urlPathEqualTo(PULLS)).withHeader("If-None-Match", absent()));
    }

    // --- authentication ---------------------------------------------------

    @Test
    void sendsTheTokenWhenThereIsOne() {
        SERVER.stubFor(get(urlPathEqualTo(PULLS)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("[]")));

        try (GitHubClient client = client("ghp_secret")) {
            assertTrue(client.isAuthenticated());
            client.pullRequestsForCommit(REPO, SHA);
        }

        SERVER.verify(getRequestedFor(urlPathEqualTo(PULLS))
                .withHeader("Authorization", equalTo("Bearer ghp_secret"))
                .withHeader("X-GitHub-Api-Version", equalTo("2022-11-28"))
                .withHeader("User-Agent", equalTo("gitdigest")));
    }

    @Test
    void sendsNoAuthorizationHeaderWhenThereIsNoToken() {
        SERVER.stubFor(get(urlPathEqualTo(PULLS)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("[]")));

        try (GitHubClient client = client(null)) {
            assertTrue(!client.isAuthenticated());
            client.pullRequestsForCommit(REPO, SHA);
        }

        SERVER.verify(getRequestedFor(urlPathEqualTo(PULLS)).withHeader("Authorization", absent()));
    }

    // --- failures, which have to be told apart ---------------------------

    @Test
    void aSpentRateLimitIsDistinctAndSaysWhenItResets() {
        SERVER.stubFor(get(urlPathEqualTo(PULLS)).willReturn(aResponse().withStatus(403)
                .withHeader("x-ratelimit-remaining", "0")
                .withHeader("x-ratelimit-reset", "1800000000")));

        try (GitHubClient client = client(null)) {
            GitHubException.RateLimited failure = assertThrows(GitHubException.RateLimited.class,
                    () -> client.pullRequestsForCommit(REPO, SHA));

            assertTrue(failure.getMessage().contains("resets at"), failure.getMessage());
            assertTrue(failure.getMessage().contains("GITHUB_TOKEN"),
                    "an unauthenticated caller can fix this now, and should be told how");
        }
    }

    @Test
    void anAuthenticatedRateLimitIsToldToWaitNotToSetAToken() {
        SERVER.stubFor(get(urlPathEqualTo(PULLS)).willReturn(aResponse().withStatus(403)
                .withHeader("x-ratelimit-remaining", "0")));

        try (GitHubClient client = client("token")) {
            GitHubException.RateLimited failure = assertThrows(GitHubException.RateLimited.class,
                    () -> client.pullRequestsForCommit(REPO, SHA));

            assertTrue(failure.getMessage().contains("Wait"), failure.getMessage());
        }
    }

    @Test
    void aForbiddenResponseWithQuotaLeftIsNotARateLimit() {
        // Same status code, different problem. Calling this a rate limit would
        // stop the whole run and tell the user to wait for nothing.
        SERVER.stubFor(get(urlPathEqualTo(PULLS)).willReturn(aResponse().withStatus(403)
                .withHeader("x-ratelimit-remaining", "4999")));

        try (GitHubClient client = client("token")) {
            GitHubException failure = assertThrows(GitHubException.class,
                    () -> client.pullRequestsForCommit(REPO, SHA));

            assertTrue(!(failure instanceof GitHubException.RateLimited), failure.getMessage());
        }
    }

    @Test
    void aRejectedTokenIsReportedAsSuch() {
        SERVER.stubFor(get(urlPathEqualTo(PULLS)).willReturn(aResponse().withStatus(401)));

        try (GitHubClient client = client("bad")) {
            GitHubException failure = assertThrows(GitHubException.class,
                    () -> client.pullRequestsForCommit(REPO, SHA));

            assertTrue(failure.getMessage().contains("GITHUB_TOKEN"), failure.getMessage());
        }
    }

    @Test
    void aMissingRepositoryMentionsPrivateRepositoriesNeedingAToken() {
        SERVER.stubFor(get(urlPathEqualTo(PULLS)).willReturn(aResponse().withStatus(404)));

        try (GitHubClient client = client(null)) {
            GitHubException failure = assertThrows(GitHubException.class,
                    () -> client.pullRequestsForCommit(REPO, SHA));

            assertTrue(failure.getMessage().contains("404"), failure.getMessage());
            assertTrue(failure.getMessage().contains("private"), failure.getMessage());
        }
    }

    @Test
    void malformedJsonIsReportedAsSuchRatherThanCrashing() {
        SERVER.stubFor(get(urlPathEqualTo(PULLS)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{ not json")));

        try (GitHubClient client = client("token")) {
            GitHubException failure = assertThrows(GitHubException.class,
                    () -> client.pullRequestsForCommit(REPO, SHA));

            assertTrue(failure.getMessage().contains("not valid JSON"), failure.getMessage());
        }
    }

    @Test
    void waitsOutAShortRetryAfterAndTriesOnceMore() {
        // A secondary limit, which clears in seconds - unlike the primary one,
        // which can be an hour away and is reported rather than slept through.
        SERVER.stubFor(get(urlPathEqualTo(PULLS)).inScenario("retry")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(403).withHeader("Retry-After", "1"))
                .willSetStateTo("second try"));
        SERVER.stubFor(get(urlPathEqualTo(PULLS)).inScenario("retry")
                .whenScenarioStateIs("second try")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(pullRequestJson(9, "Worth the wait"))));

        try (GitHubClient client = client("token")) {
            assertEquals("Worth the wait", client.pullRequestsForCommit(REPO, SHA).get(0).title());
        }
        SERVER.verify(2, getRequestedFor(urlPathEqualTo(PULLS)));
    }

    @Test
    void anAbsurdRetryAfterIsNotSleptThrough() {
        // Waiting an hour is ruder than reporting the problem.
        SERVER.stubFor(get(urlPathEqualTo(PULLS)).willReturn(aResponse().withStatus(403)
                .withHeader("Retry-After", "3600")
                .withHeader("x-ratelimit-remaining", "0")));

        try (GitHubClient client = client("token")) {
            assertThrows(GitHubException.RateLimited.class, () -> client.pullRequestsForCommit(REPO, SHA));
        }
        SERVER.verify(1, getRequestedFor(urlPathEqualTo(PULLS)));
    }
}
