package dev.gitdigest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class GitDigestTest {

    @Test
    void versionFlagExitsZero() {
        int exitCode = new CommandLine(new GitDigest()).execute("--version");
        assertEquals(0, exitCode);
    }

    @Test
    void noArgsExitsZero() {
        int exitCode = new CommandLine(new GitDigest()).execute();
        assertEquals(0, exitCode);
    }
}
