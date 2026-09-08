package dev.gitdigest;

import java.io.Console;
import java.nio.charset.Charset;

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

    public static Ansi ansi() {
        return supportsColor() ? Ansi.ON : Ansi.OFF;
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
