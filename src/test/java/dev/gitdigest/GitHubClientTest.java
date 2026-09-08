package dev.gitdigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Link header parsing, which is the part of the client that is pure enough to
 * test without a server. The HTTP behaviour itself gets a WireMock test in
 * Phase 6.
 */
class GitHubClientTest {

    @Test
    void findsTheNextPage() {
        String header = "<https://api.github.com/x?page=2>; rel=\"next\", "
                + "<https://api.github.com/x?page=9>; rel=\"last\"";

        assertEquals("https://api.github.com/x?page=2", GitHubClient.nextLink(header));
    }

    @Test
    void theLastPageHasNoNext() {
        String header = "<https://api.github.com/x?page=8>; rel=\"prev\", "
                + "<https://api.github.com/x?page=1>; rel=\"first\"";

        assertNull(GitHubClient.nextLink(header));
    }

    @Test
    void aUrlContainingTheWordNextIsNotMistakenForTheLink() {
        // "next" appears inside the URL of the prev link; only the rel counts
        String header = "<https://api.github.com/repos/o/next/commits?page=1>; rel=\"prev\"";

        assertNull(GitHubClient.nextLink(header));
    }

    @Test
    void handlesAMissingOrEmptyHeader() {
        assertNull(GitHubClient.nextLink(null));
        assertNull(GitHubClient.nextLink(""));
        assertNull(GitHubClient.nextLink("   "));
    }

    @Test
    void toleratesOddSpacingAndOrdering() {
        String header = "<https://api.github.com/x?page=5>;rel=\"last\","
                + "  <https://api.github.com/x?page=3>;   rel=\"next\"";

        assertEquals("https://api.github.com/x?page=3", GitHubClient.nextLink(header));
    }
}
