package dev.gitdigest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;

/**
 * Reads commit history from a local Git repository using JGit.
 * This is the ONLY class allowed to import org.eclipse.jgit.* types.
 */
public class RepoReader {

    /**
     * Walks the full history of the repo at {@code repoPath} (newest first)
     * and converts each commit into a {@link CommitInfo}.
     *
     * <p>Only HEAD's history is walked, not every branch — {@code log().all()}
     * would include all refs, at the cost of counting the same commit once per
     * branch that contains it.
     *
     * @throws org.eclipse.jgit.errors.RepositoryNotFoundException if the path is
     *         not a git repository. Deliberately not caught here: StatsCommand
     *         turns it into a friendly message and a non-zero exit code.
     * @throws org.eclipse.jgit.api.errors.NoHeadException if the repository has
     *         no commits yet, so HEAD cannot be resolved.
     */
    public List<CommitInfo> readCommits(Path repoPath) throws IOException, GitAPIException {
        return read(repoPath, null, null);
    }

    /**
     * Reads the commits reachable from {@code toRef} but not from
     * {@code fromRef} - the same set as "git log fromRef..toRef".
     *
     * <p>{@code fromRef} is exclusive, which is what a changelog wants: the
     * range v1.0..v2.0 describes what changed after v1.0 was released, so the
     * v1.0 commit itself does not belong in it. A null {@code fromRef} runs
     * back to the first commit, and a null {@code toRef} means HEAD.
     *
     * @throws IllegalArgumentException if a revision cannot be resolved
     */
    public List<CommitInfo> readRange(Path repoPath, String fromRef, String toRef)
            throws IOException, GitAPIException {
        return read(repoPath, fromRef, toRef == null ? "HEAD" : toRef);
    }

    private List<CommitInfo> read(Path repoPath, String fromRef, String toRef)
            throws IOException, GitAPIException {
        List<CommitInfo> commits = new ArrayList<>();
        try (Git git = Git.open(repoPath.toFile());
                RevWalk revWalk = new RevWalk(git.getRepository());
                DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {

            // The formatter computes diffs; DisabledOutputStream throws away the
            // patch text, because we only ever want the list of paths.
            diffFormatter.setRepository(git.getRepository());
            // Without this a rename reads as a delete plus an add, counting one
            // edit as changes to two files. Git detects renames by default too;
            // it costs extra similarity scoring per commit.
            diffFormatter.setDetectRenames(true);

            for (RevCommit commit : buildLog(git, fromRef, toRef).call()) {
                commits.add(toCommitInfo(commit, revWalk, diffFormatter));
            }
        }
        return List.copyOf(commits);
    }

    /**
     * The URL of the "origin" remote, if the repository has one.
     *
     * <p>Empty for a repository with no remote at all, which is a normal state
     * for a local-only project rather than a failure.
     */
    public Optional<String> readRemoteUrl(Path repoPath) throws IOException {
        try (Git git = Git.open(repoPath.toFile())) {
            String url = git.getRepository().getConfig().getString("remote", "origin", "url");
            return Optional.ofNullable(url).filter(value -> !value.isBlank());
        }
    }

    private static LogCommand buildLog(Git git, String fromRef, String toRef) throws IOException {
        LogCommand log = git.log();
        if (toRef == null) {
            return log;
        }
        Repository repo = git.getRepository();
        ObjectId to = resolve(repo, toRef);
        return fromRef == null ? log.add(to) : log.addRange(resolve(repo, fromRef), to);
    }

    /**
     * Resolves a branch, tag or hash to a commit.
     *
     * <p>The "^{commit}" suffix peels annotated tags, which resolve to a tag
     * object rather than to the commit the walk needs.
     */
    private static ObjectId resolve(Repository repo, String revision) throws IOException {
        ObjectId id = repo.resolve(revision + "^{commit}");
        if (id == null) {
            throw new IllegalArgumentException("unknown revision: " + revision);
        }
        return id;
    }

    /**
     * Converts one JGit commit into our own record, so no JGit type escapes
     * this class.
     *
     * <p>The timestamp keeps the author's original time zone rather than
     * converting to this machine's: "commits by hour" should describe the time
     * of day where the commit was written, not where the report is being run.
     */
    private static CommitInfo toCommitInfo(RevCommit commit, RevWalk revWalk, DiffFormatter diffFormatter)
            throws IOException {
        PersonIdent author = commit.getAuthorIdent();
        return new CommitInfo(
                commit.getName(),
                author.getName(),
                author.getEmailAddress(),
                author.getWhenAsInstant().atZone(author.getZoneId()),
                commit.getShortMessage(),
                filesChangedIn(commit, revWalk, diffFormatter));
    }

    /**
     * Paths touched by a commit, found by diffing it against its parent.
     *
     * <p>Merges are reported as touching nothing. A merge's diff against one
     * parent restates every change made on the other branch, so counting it
     * would tally those files a second time — which is why {@code git log
     * --stat} shows nothing for a merge by default.
     */
    private static List<String> filesChangedIn(RevCommit commit, RevWalk revWalk, DiffFormatter diffFormatter)
            throws IOException {
        if (commit.getParentCount() > 1) {
            return List.of();
        }

        // A null tree on the left makes the diff run against an empty tree, so
        // the very first commit reports the files it introduced rather than
        // nothing at all.
        RevTree parentTree = null;
        if (commit.getParentCount() == 1) {
            // Parents handed back by log() are unparsed placeholders: their
            // getTree() stays null until something reads the object itself.
            RevCommit parent = revWalk.parseCommit(commit.getParent(0).getId());
            parentTree = parent.getTree();
        }

        List<String> paths = new ArrayList<>();
        for (DiffEntry diff : diffFormatter.scan(parentTree, commit.getTree())) {
            paths.add(pathOf(diff));
        }
        return List.copyOf(paths);
    }

    /** A deleted file has no new path, so its old one is the meaningful name. */
    private static String pathOf(DiffEntry diff) {
        return diff.getChangeType() == DiffEntry.ChangeType.DELETE
                ? diff.getOldPath()
                : diff.getNewPath();
    }
}
