package dev.gitdigest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Where text we did not choose - a model's prose, a contributor's name - is
 * written, and in what encoding.
 */
class TerminalTest {

    /** S with a caron, as in "Strba": outside ASCII, and outside code page 437. */
    private static final String S_CARON = "Š";

    @Test
    void redirectedOutputIsUtf8WhateverSystemOutEncodes() {
        // System.out set to Latin-1 stands in for Windows reporting Cp1252 on
        // a pipe: neither can encode the character, so if textOut deferred to
        // System.out's encoder the bytes would be a single "?".
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(bytes, true, StandardCharsets.ISO_8859_1));
            PrintStream out = Terminal.textOut(false);
            out.print(S_CARON);
            out.flush();
        } finally {
            System.setOut(original);
        }

        assertArrayEquals(S_CARON.getBytes(StandardCharsets.UTF_8), bytes.toByteArray());
    }

    @Test
    void aTerminalGetsSystemOutAsIsSoTheConsoleDecodesWhatItWasSent() {
        // Forcing UTF-8 here is the bug this replaces: a console on code page
        // 437 decoded each accented letter as two box-drawing characters.
        assertSame(System.out, Terminal.textOut(true));
    }
}
