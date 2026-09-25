package dev.gitdigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.List;

import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The only class that talks to JGit, tested against real repositories.
 *
 * <p>Nearly every assertion here corresponds to a claim RepoReader makes in a
 * comment - that a merge touches nothing, that an annotated tag is peeled,
 * that a rename counts once, that the author's own time zone survives. A
 * comment asserting behaviour is a promise; these are what make it one.
 */
class RepoReaderTest {

    @TempDir
    Path directory;

    private final RepoReader reader = new RepoReader();

    private List<String> subjectsOf(List<CommitInfo> commits) {
        return commits.stream().map(CommitInfo::subject).toList();
    }

    @Test
    void readsCommitsNewestFirst() throws Exception {
        try (TestRepo repo = TestRepo.at(directory)) {
            repo.commit("first").commit("second").commit("third");
        }

        List<CommitInfo> commits = reader.readCommits(directory);

        assertEquals(List.of("third", "second", "first"), subjectsOf(commits));
    }

    @Test
    void readsTheAuthorAndTheFilesTouched() throws Exception {
        try (TestRepo repo = TestRepo.at(directory)) {
            repo.commitAs("Grace Hopper", "grace@example.com", "add the compiler",
                    "src/compiler.java", "README.md");
        }

        CommitInfo commit = reader.readCommits(directory).get(0);

        assertEquals("Grace Hopper", commit.authorName());
        assertEquals("grace@example.com", commit.authorEmail());
        assertEquals(List.of("README.md", "src/compiler.java"), commit.filesChanged().stream().sorted().toList());
        assertEquals(40, commit.sha().length());
    }

    @Test
    void keepsTheAuthorsOwnTimeZone() throws Exception {
        // A commit written at 09:00 in Tokyo was written in the morning. If
        // this machine's zone were applied instead, "commits by hour" would
        // describe where the report is run rather than where the work happened.
        ZonedDateTime tokyoMorning = ZonedDateTime.parse("2026-03-04T09:00:00+09:00");
        try (TestRepo repo = TestRepo.at(directory)) {
            repo.at(tokyoMorning).commit("early start");
        }

        CommitInfo commit = reader.readCommits(directory).get(0);

        assertEquals(9, commit.when().getHour());
        assertEquals(tokyoMorning.toInstant(), commit.when().toInstant());
    }

    @Test
    void theFirstCommitReportsWhatItIntroduced() throws Exception {
        // It has no parent to diff against. Diffing against nothing at all
        // would report an empty file list for the commit that created the
        // repository.
        try (TestRepo repo = TestRepo.at(directory)) {
            repo.commit("initial", "a.txt", "b.txt");
        }

        assertEquals(2, reader.readCommits(directory).get(0).filesChanged().size());
    }

    @Test
    void aRenameCountsAsOneFileNotTwo() throws Exception {
        try (TestRepo repo = TestRepo.at(directory)) {
            repo.commit("add it", "old-name.txt");
            repo.rename("old-name.txt", "new-name.txt");
            repo.commitStaged(TestRepo.DEFAULT_NAME, TestRepo.DEFAULT_EMAIL, "rename it");
        }

        CommitInfo rename = reader.readCommits(directory).get(0);

        assertEquals(1, rename.filesChanged().size(),
                "a rename read as a delete plus an add counts one edit as two files: "
                        + rename.filesChanged());
        assertEquals("new-name.txt", rename.filesChanged().get(0));
    }

    @Test
    void aDeletedFileIsNamedByThePathItHad() throws Exception {
        try (TestRepo repo = TestRepo.at(directory)) {
            repo.commit("add it", "doomed.txt", "keeper.txt");
            repo.delete("doomed.txt");
            repo.commitStaged(TestRepo.DEFAULT_NAME, TestRepo.DEFAULT_EMAIL, "remove it");
        }

        assertEquals(List.of("doomed.txt"), reader.readCommits(directory).get(0).filesChanged());
    }

    @Test
    void aMergeIsReportedAsTouchingNothing() throws Exception {
        // Its diff against one parent restates everything done on the other
        // branch, so counting it would tally those files a second time.
        try (TestRepo repo = TestRepo.at(directory)) {
            repo.commit("base", "base.txt");
            repo.branch("feature").commit("on the branch", "feature.txt");
            repo.checkout("main").merge("feature");
        }

        List<CommitInfo> commits = reader.readCommits(directory);
        CommitInfo merge = commits.get(0);

        assertTrue(merge.subject().startsWith("Merge"), "expected the merge first, got " + merge.subject());
        assertEquals(List.of(), merge.filesChanged());
    }

    @Test
    void onlyHeadsHistoryIsWalked() throws Exception {
        // log().all() would include every ref, counting a commit once per
        // branch that contains it.
        try (TestRepo repo = TestRepo.at(directory)) {
            repo.commit("on main", "main.txt");
            repo.branch("side").commit("only on the side branch", "side.txt");
            repo.checkout("main");
        }

        assertEquals(List.of("on main"), subjectsOf(reader.readCommits(directory)));
    }

    @Test
    void aRangeExcludesItsStartAndIncludesItsEnd() throws Exception {
        // git log v1.0..v2.0: what changed *after* v1.0 shipped.
        try (TestRepo repo = TestRepo.at(directory)) {
            repo.commit("before").tag("v1.0");
            repo.commit("during").commit("also during").tag("v2.0");
            repo.commit("after");
        }

        List<CommitInfo> range = reader.readRange(directory, "v1.0", "v2.0");

        assertEquals(List.of("also during", "during"), subjectsOf(range));
    }

    @Test
    void anAnnotatedTagResolvesToItsCommit() throws Exception {
        // An annotated tag is a tag object, not a commit. Without peeling it
        // the walk has nothing it can start from.
        try (TestRepo repo = TestRepo.at(directory)) {
            repo.commit("before").annotatedTag("v1.0");
            repo.commit("after");
        }

        assertEquals(List.of("after"), subjectsOf(reader.readRange(directory, "v1.0", "HEAD")));
    }

    @Test
    void anOpenEndedRangeRunsBackToTheFirstCommit() throws Exception {
        try (TestRepo repo = TestRepo.at(directory)) {
            repo.commit("first").commit("second").tag("v1.0");
        }

        assertEquals(2, reader.readRange(directory, null, "v1.0").size());
    }

    @Test
    void aNullEndMeansHead() throws Exception {
        try (TestRepo repo = TestRepo.at(directory)) {
            repo.commit("first").commit("second");
        }

        assertEquals(subjectsOf(reader.readCommits(directory)),
                subjectsOf(reader.readRange(directory, null, null)));
    }

    @Test
    void anUnknownRevisionIsRejectedByName() throws Exception {
        try (TestRepo repo = TestRepo.at(directory)) {
            repo.commit("only commit");
        }

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> reader.readRange(directory, "v9.9", "HEAD"));

        assertTrue(failure.getMessage().contains("v9.9"),
                "the message should name the revision the user typed: " + failure.getMessage());
    }

    @Test
    void aPathThatIsNotARepositoryIsRejected() {
        assertThrows(RepositoryNotFoundException.class, () -> reader.readCommits(directory));
    }

    @Test
    void aRepositoryWithNoCommitsIsRejectedAsHeadless() throws Exception {
        try (TestRepo unusedButOpen = TestRepo.at(directory)) {
            assertThrows(NoHeadException.class, () -> reader.readCommits(directory));
        }
    }

    @Test
    void readsTheOriginRemote() throws Exception {
        try (TestRepo repo = TestRepo.at(directory)) {
            repo.commit("first").remote("https://github.com/acme/widgets.git");
        }

        assertEquals("https://github.com/acme/widgets.git", reader.readRemoteUrl(directory).orElseThrow());
    }

    @Test
    void noRemoteIsEmptyRatherThanAFailure() throws Exception {
        // A local-only project is a normal state, not a broken one.
        try (TestRepo repo = TestRepo.at(directory)) {
            repo.commit("first");
        }

        assertTrue(reader.readRemoteUrl(directory).isEmpty());
    }

    @Test
    void readingDoesNotLeaveTheRepositoryLocked() throws Exception {
        // JGit holds file handles until the Git object is closed, and on
        // Windows an unclosed handle stops the directory being deleted - which
        // shows up as a TempDir cleanup failure rather than as a clear error.
        try (TestRepo repo = TestRepo.at(directory)) {
            repo.commit("first");
        }

        reader.readCommits(directory);
        reader.readRemoteUrl(directory);

        assertFalse(subjectsOf(reader.readCommits(directory)).isEmpty());
    }

    @Test
    void handlesAHistoryLongerThanOnePage() throws Exception {
        try (TestRepo repo = TestRepo.at(directory)) {
            for (int i = 0; i < 50; i++) {
                repo.commit("commit number " + i);
            }
        }

        List<CommitInfo> commits = reader.readCommits(directory);

        assertEquals(50, commits.size());
        assertEquals("commit number 49", commits.get(0).subject());
    }
}
