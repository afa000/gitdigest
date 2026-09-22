package dev.gitdigest;

import java.io.Console;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import picocli.CommandLine.Help.Ansi;

/**
 * What the terminal on the other end can actually handle.
 *
 * <p>Both questions here have the same shape: the nice output is only nice if
 * something is there to render it. Piped into a file or another program, colour
 * codes become literal garbage in the data, so they have to go.
 */
public final class Terminal {

    private static final String BLOCK_UNICODE = "\u2588";
    private static final String BLOCK_ASCII = "#";

    private Terminal() {
    }

    /**
     * True when stdout is a real terminal and the user has not opted out.
     *
     * <p>Console.isTerminal is the part that matters: since Java 22
     * System.console() returns a Console even when output is redirected, so a
     * null check alone would report colour support for a file.
     *
     * <p>NO_COLOR is honoured because it is the convention other CLIs follow.
     */
    public static boolean supportsColor() {
        if (System.getenv("NO_COLOR") != null) {
            return false;
        }
        Console console = System.console();
        return console != null && console.isTerminal();
    }

    /**
     * True when this run has a human watching, best effort.
     *
     * <p>Used to decide whether a progress counter should animate. The check is
     * the same one colour uses, and it is approximate for the same reason: the
     * JDK will say whether a console is attached, but not which of the three
     * standard streams is still pointed at it. Erring towards "not a terminal"
     * costs an animation; erring the other way writes carriage returns into
     * someone's log file.
     */
    public static boolean isInteractive() {
        Console console = System.console();
        return console != null && console.isTerminal();
    }

    public static Ansi ansi() {
        return supportsColor() ? Ansi.ON : Ansi.OFF;
    }

    /**
     * Standard output, as UTF-8, whatever the JVM thinks the console is.
     *
     * <p>Same Windows problem as {@link #barCharacter()}, from the other end.
     * A redirected stdout reports Cp1252, so anything outside it - an em dash,
     * a curly quote, an accented name - is written as "?" and the file is
     * quietly wrong. Rendering our own tables, that is avoidable by choosing
     * ASCII; for prose written by a model, or a contributor's name, it is not.
     *
     * <p>Only the streams carrying text we did not choose need this. The
     * caller keeps ownership of System.out and must not close the wrapper.
     */
    public static PrintStream utf8Out() {
        return new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
    }

    /**
     * The bar character this stdout can encode.
     *
     * <p>Windows is the case that matters: even with the console set to UTF-8,
     * a JVM whose stdout is a pipe reports Cp1252, which turns every block into
     * a literal "?".
     */
    public static String barCharacter() {
        String encoding = System.getProperty("stdout.encoding");
        if (encoding == null) {
            encoding = System.getProperty("native.encoding");
        }
        try {
            if (encoding != null && Charset.forName(encoding).newEncoder().canEncode(BLOCK_UNICODE)) {
                return BLOCK_UNICODE;
            }
        } catch (IllegalArgumentException e) {
            // Unknown or malformed charset name: ASCII is always safe.
        }
        return BLOCK_ASCII;
    }
}
