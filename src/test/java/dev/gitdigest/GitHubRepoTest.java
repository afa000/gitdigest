package dev.gitdigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class GitHubRepoTest {

    @Test
    void readsTheThreeShapesGitHands() {
        GitHubRepo expected = new GitHubRepo("afa000", "gitdigest");

        assertEquals(Optional.of(expected), GitHubRepo.parse("https://github.com/afa000/gitdigest.git"));
        assertEquals(Optional.of(expected), GitHubRepo.parse("https://github.com/afa000/gitdigest"));
        assertEquals(Optional.of(expected), GitHubRepo.parse("git@github.com:afa000/gitdigest.git"));
        assertEquals(Optional.of(expected), GitHubRepo.parse("ssh://git@github.com/afa000/gitdigest.git"));
    }

    @Test
    void ignoresSurroundingWhitespace() {
        assertEquals(
                Optional.of(new GitHubRepo("afa000", "gitdigest")),
                GitHubRepo.parse("  https://github.com/afa000/gitdigest.git  "));
    }

    @Test
    void nonGitHubRemotesAreSkippedRatherThanGuessedAt() {
        assertTrue(GitHubRepo.parse("https://gitlab.com/afa000/gitdigest.git").isEmpty());
        assertTrue(GitHubRepo.parse("git@bitbucket.org:afa000/gitdigest.git").isEmpty());
        assertTrue(GitHubRepo.parse("/srv/git/local-only.git").isEmpty());
    }

    @Test
    void missingOrMalformedInputIsEmpty() {
        assertTrue(GitHubRepo.parse(null).isEmpty());
        assertTrue(GitHubRepo.parse("").isEmpty());
        assertTrue(GitHubRepo.parse("   ").isEmpty());
        assertTrue(GitHubRepo.parse("https://github.com/afa000").isEmpty(), "owner alone is not a repository");
        assertTrue(GitHubRepo.parse("https://github.com/afa000/gitdigest/tree/main").isEmpty(),
                "a deep link is not a repository root");
    }

    @Test
    void printsAsOwnerSlashName() {
        assertEquals("afa000/gitdigest", new GitHubRepo("afa000", "gitdigest").toString());
    }
}
