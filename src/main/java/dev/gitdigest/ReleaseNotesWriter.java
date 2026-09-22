package dev.gitdigest;

import java.io.PrintStream;

/**
 * Something that can turn a changelog into release notes.
 *
 * <p>Two implementations, and the command should not care which it has: Claude
 * writes the good ones, and a template writes the ones that do not need a key,
 * a network or a cent. Behind one interface, "no API key" stops being a
 * failure mode and becomes a choice of writer.
 */
public interface ReleaseNotesWriter {

    /**
     * Writes the notes to {@code out}, a piece at a time where it can.
     *
     * @param out where the notes go; kept separate from any progress or
     *            warning output so that redirecting stdout yields only notes
     */
    void write(Changelog changelog, NotesCommand.Tone tone, PrintStream out);

    /** A short phrase naming the source, for the line that says what happened. */
    String describe();
}
