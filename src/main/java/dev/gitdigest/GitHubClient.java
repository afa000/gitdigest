package dev.gitdigest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A small, well-behaved client for the part of the GitHub REST API this tool
 * needs.
 *
 * <p>Well-behaved means four things: it authenticates when it can, it
 * revalidates with ETags so an unchanged response is not sent twice, it follows
 * Link headers instead of guessing page numbers, and it stops and explains
 * itself when the rate limit is gone rather than hammering a closed door.
 */
public class GitHubClient implements AutoCloseable {

    private static final String API = "https://api.github.com";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    /** Longer than this and waiting is ruder than reporting the problem. */
    private static final long MAX_BACKOFF_SECONDS = 60;

    private final HttpClient http;
    private final HttpCache cache;
    private final String token;
    private final ObjectMapper mapper = new ObjectMapper();

    public GitHubClient(HttpClient http, HttpCache cache, String token) {
        this.http = http;
        this.cache = cache;
        this.token = token;
    }

    /** Reads the token from GITHUB_TOKEN, so it is never written down in code. */
    public static GitHubClient fromEnvironment() {
        return fromEnvironment(HttpCache.inUserHome());
    }

    public static GitHubClient fromEnvironment(HttpCache cache) {
        String token = System.getenv("GITHUB_TOKEN");
        if (token == null || token.isBlank()) {
            token = System.getenv("GH_TOKEN");
        }
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return new GitHubClient(http, cache, token == null || token.isBlank() ? null : token);
    }

    public boolean isAuthenticated() {
        return token != null;
    }

    /**
     * The pull requests a commit arrived through, newest first.
     *
     * <p>Usually one, occasionally none - a commit pushed straight to a branch
     * belongs to no pull request at all, which is not an error.
     */
    public List<PullRequest> pullRequestsForCommit(GitHubRepo repo, String sha) {
        String url = API + "/repos/" + repo.owner() + "/" + repo.name() + "/commits/" + sha + "/pulls";
        List<PullRequest> found = new ArrayList<>();
        for (JsonNode node : getArray(url)) {
            found.add(toPullRequest(node));
        }
        return List.copyOf(found);
    }

    private static PullRequest toPullRequest(JsonNode node) {
        return new PullRequest(
                node.path("number").asInt(),
                node.path("title").asText(""),
                node.path("user").path("login").asText(""),
                node.path("html_url").asText(""));
    }

    /**
     * Fetches a JSON array, following Link headers until the pages run out.
     *
     * <p>The next URL comes from the response rather than from a page counter
     * we invent: GitHub owns the paging scheme, and following what it sends is
     * the only version that keeps working when the scheme changes.
     */
    private List<JsonNode> getArray(String url) {
        List<JsonNode> all = new ArrayList<>();
        String next = url;
        while (next != null) {
            Page page = get(next);
            JsonNode root = parse(page.body());
            if (root.isArray()) {
                root.forEach(all::add);
            }
            next = nextLink(page.link());
        }
        return all;
    }

    private JsonNode parse(String body) {
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            throw new GitHubException("GitHub sent a response that is not valid JSON", e);
        }
    }

    /** One fetched page: its body, and the Link header that may point onward. */
    private record Page(String body, String link) {
    }

    private Page get(String url) {
        Optional<HttpCache.Entry> cached = cache.get(url);
        HttpResponse<String> response = send(url, cached.map(HttpCache.Entry::etag).orElse(null));

        int status = response.statusCode();
        String link = response.headers().firstValue("link").orElse(null);

        if (status == 304 && cached.isPresent()) {
            // Revalidated: the copy we hold is still current, and GitHub sent
            // no body. This saves the transfer, not the quota - a 304 was
            // measured to still cost one request against the limit.
            return new Page(cached.get().body(), link);
        }
        if (status == 200) {
            String body = response.body();
            response.headers().firstValue("etag").ifPresent(etag -> cache.put(url, etag, body));
            return new Page(body, link);
        }
        throw failureFor(status, response);
    }

    private GitHubException failureFor(int status, HttpResponse<String> response) {
        if (status == 401) {
            return new GitHubException("GitHub rejected the token in GITHUB_TOKEN (401).");
        }
        if (status == 404) {
            return new GitHubException("GitHub has no such repository or commit (404). "
                    + "A private repository needs GITHUB_TOKEN to be set.");
        }
        if (status == 403 || status == 429) {
            return rateLimitFailure(response);
        }
        return new GitHubException("GitHub returned HTTP " + status + ".");
    }

    private GitHubException rateLimitFailure(HttpResponse<String> response) {
        String remaining = response.headers().firstValue("x-ratelimit-remaining").orElse("");
        if (!"0".equals(remaining)) {
            return new GitHubException("GitHub refused the request (HTTP " + response.statusCode() + ").");
        }
        String advice = isAuthenticated()
                ? "Wait for the window to reset."
                : "Set GITHUB_TOKEN to raise the limit from 60 to 5000 requests an hour.";
        return new GitHubException.RateLimited(
                "GitHub rate limit reached" + resetHint(response) + ". " + advice);
    }

    private static String resetHint(HttpResponse<String> response) {
        return response.headers().firstValue("x-ratelimit-reset")
                .map(value -> {
                    try {
                        return "; it resets at " + Instant.ofEpochSecond(Long.parseLong(value));
                    } catch (NumberFormatException e) {
                        return "";
                    }
                })
                .orElse("");
    }

    /**
     * Sends the request, pausing once if GitHub asks for a short wait.
     *
     * <p>Retry-After signals a secondary limit, which clears in seconds; the
     * primary limit is handled by reporting rather than sleeping, because it
     * can be an hour away.
     */
    private HttpResponse<String> send(String url, String etag) {
        HttpResponse<String> response = sendOnce(url, etag);
        Optional<Long> retryAfter = response.headers().firstValue("retry-after")
                .map(value -> {
                    try {
                        return Long.parseLong(value.trim());
                    } catch (NumberFormatException e) {
                        return -1L;
                    }
                })
                .filter(seconds -> seconds > 0 && seconds <= MAX_BACKOFF_SECONDS);

        if (retryAfter.isEmpty()) {
            return response;
        }
        try {
            System.err.println("gitdigest: GitHub asked for a " + retryAfter.get()
                    + "s pause; waiting, then retrying once.");
            Thread.sleep(Duration.ofSeconds(retryAfter.get()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitHubException("Interrupted while waiting out a GitHub rate limit.", e);
        }
        return sendOnce(url, etag);
    }

    private HttpResponse<String> sendOnce(String url, String etag) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "gitdigest");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (etag != null) {
            request.header("If-None-Match", etag);
        }
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new GitHubException("Could not reach GitHub: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitHubException("Interrupted while calling GitHub.", e);
        }
    }

    /**
     * Pulls the rel="next" URL out of a Link header.
     *
     * <p>Shape: {@code <https://api.github.com/x?page=2>; rel="next", <...>; rel="last"}
     * Only the section after the closing angle bracket is inspected, so a URL
     * that happens to contain the word "next" cannot be mistaken for the link.
     */
    static String nextLink(String linkHeader) {
        if (linkHeader == null || linkHeader.isBlank()) {
            return null;
        }
        for (String part : linkHeader.split(",")) {
            String section = part.trim();
            int open = section.indexOf('<');
            int close = section.indexOf('>');
            if (open < 0 || close <= open) {
                continue;
            }
            String rel = section.substring(close + 1).replace(" ", "");
            if (rel.contains("rel=" + '"' + "next" + '"') || rel.contains("rel=next")) {
                return section.substring(open + 1, close);
            }
        }
        return null;
    }

    @Override
    public void close() {
        http.close();
    }
}
