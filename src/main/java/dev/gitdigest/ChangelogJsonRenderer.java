package dev.gitdigest;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;

import com.fasterxml.jackson.databind.ObjectMapper;

public class ChangelogJsonRenderer implements ChangelogRenderer {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void print(Changelog changelog, PrintStream out) {
        try {
            // Serializing to a String rather than to the stream keeps Jackson
            // from closing System.out when it finishes.
            out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(changelog));
        } catch (IOException e) {
            throw new UncheckedIOException("could not write changelog as JSON", e);
        }
    }
}
