package dev.gitdigest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.TimeZone;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;

/**
 * A real Git repository, built in a temporary directory, one commit at a time.
 *
 * <p>Everything up to here has been tested against model objects assembled by
 * hand, which proves the aggregation and proves nothing about the layer that
 * produces it. `RepoReader` is the one class that talks to JGit, and the only
 * honest way to test it is against a repository that actually exists.
 *
 * <p>Scripting one in the test rather than checking a fixture into the tree
 * keeps it deterministic and legible: the history a test depends on is written
 * in the test, a few lines above the assertion, instead of being an opaque
 * directory someone has to go and inspect. Committing a `.git` directory into
 * a `.git` directory is also its own kind of trouble.
 *
 * <p>Authors and timestamps are always set explicitly. A commit that inherits
 * them from whoever runs the suite is a test that passes on one machine.
 */
final class TestRepo implements AutoCloseable {

    /** Fixed so that "who wrote this" is a property of the test, not the runner. */
    static final String DEFAULT_NAME = "Ada Lovelace";
    static final String DEFAULT_EMAIL = "ada@example.com";

    /** Fixed so that date and hour assertions do not drift with the calendar. */
    static final ZonedDateTime DEFAULT_TIME = ZonedDateTime.parse("2026-03-04T14:30:00+00:00");

    private final Path root;
    private final Git git;
    private ZonedDateTime clock = DEFAULT_TIME;

    private TestRepo(Path root, Git git) {
        this.root = root;
        this.git = git;
    }

    static TestRepo at(Path directory) throws GitAPIException {
        Git git = Git.init()
                .setDirectory(directory.toFile())
                // Named explicitly: the default branch name depends on the
                // JGit version and on the user's git config.
                .setInitialBranch("main")
                .call();
        return new TestRepo(directory, git);
    }

    Path path() {
        return root;
    }

    Git git() {
        return git;
    }

    /** Moves the clock on, so commits land in a known order. */
    TestRepo at(ZonedDateTime when) {
        this.clock = when;
        return this;
    }

    TestRepo commit(String message) throws GitAPIException, IOException {
        return commit(message, fileNameFor(message));
    }

    TestRepo commit(String message, String... files) throws GitAPIException, IOException {
        return commitAs(DEFAULT_NAME, DEFAULT_EMAIL, message, files);
    }

    TestRepo commitAs(String name, String email, String message, String... files)
            throws GitAPIException, IOException {
        for (String file : files) {
            write(file, "content of " + file + " at " + message);
        }
        return commitStaged(name, email, message);
    }

    /** Commits whatever is currently staged, for deletes and renames. */
    TestRepo commitStaged(String name, String email, String message) throws GitAPIException {
        git.add().addFilepattern(".").call();
        // Without setUpdate the removal of a tracked file is not staged, so a
        // delete would be committed as no change at all.
        git.add().addFilepattern(".").setUpdate(true).call();
        PersonIdent who = identity(name, email, clock);
        git.commit().setMessage(message).setAuthor(who).setCommitter(who).call();
        clock = clock.plusHours(1);
        return this;
    }

    TestRepo write(String file, String content) throws IOException {
        Path target = root.resolve(file);
        Files.createDirectories(target.getParent() == null ? root : target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
        return this;
    }

    TestRepo delete(String file) throws GitAPIException, IOException {
        Files.delete(root.resolve(file));
        git.rm().addFilepattern(file).call();
        return this;
    }

    /** A rename as git sees it: the old path gone, the same content at a new one. */
    TestRepo rename(String from, String to) throws GitAPIException, IOException {
        String content = Files.readString(root.resolve(from), StandardCharsets.UTF_8);
        Files.delete(root.resolve(from));
        git.rm().addFilepattern(from).call();
        write(to, content);
        return this;
    }

    TestRepo tag(String name) throws GitAPIException {
        git.tag().setName(name).setAnnotated(false).call();
        return this;
    }

    /**
     * An annotated tag, which is a different object to a lightweight one.
     *
     * <p>It resolves to a tag object rather than to a commit, which is the
     * case RepoReader peels with "^{commit}" - and the case that breaks if
     * anyone removes that suffix.
     */
    TestRepo annotatedTag(String name) throws GitAPIException {
        git.tag().setName(name).setAnnotated(true).setMessage("release " + name)
                .setTagger(identity(DEFAULT_NAME, DEFAULT_EMAIL, clock)).call();
        return this;
    }

    TestRepo branch(String name) throws GitAPIException {
        git.checkout().setCreateBranch(true).setName(name).call();
        return this;
    }

    TestRepo checkout(String name) throws GitAPIException {
        git.checkout().setName(name).call();
        return this;
    }

    TestRepo merge(String branch) throws GitAPIException, IOException {
        PersonIdent who = identity(DEFAULT_NAME, DEFAULT_EMAIL, clock);
        git.merge()
                .include(git.getRepository().resolve(branch))
                // Forced so the merge is a real merge commit with two parents,
                // rather than a fast-forward that leaves no merge to test.
                .setFastForward(org.eclipse.jgit.api.MergeCommand.FastForwardMode.NO_FF)
                .setMessage("Merge " + branch)
                .setCommit(false)
                .call();
        git.commit().setMessage("Merge " + branch).setAuthor(who).setCommitter(who).call();
        clock = clock.plusHours(1);
        return this;
    }

    TestRepo remote(String url) throws IOException {
        StoredConfig config = git.getRepository().getConfig();
        config.setString("remote", "origin", "url", url);
        config.save();
        return this;
    }

    Repository repository() {
        return git.getRepository();
    }

    private static PersonIdent identity(String name, String email, ZonedDateTime when) {
        return new PersonIdent(name, email, Date.from(when.toInstant()),
                TimeZone.getTimeZone(when.getZone()));
    }

    /** A file name derived from the message, so each commit touches its own. */
    private static String fileNameFor(String message) {
        String slug = message.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return (slug.isBlank() ? "file" : slug) + ".txt";
    }

    @Override
    public void close() {
        git.close();
    }
}
