package dev.gitdigest;

import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Renders stats as JSON so the tool can be piped into jq or read by a script.
 *
 * <p>RepoStats is a record of plain maps, so Jackson serializes it directly;
 * enum and integer keys become the strings "MONDAY" and "19".
 */
public class StatsJsonRenderer implements StatsRenderer {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void print(RepoStats stats, PrintStream out) {
        try {
            // Serializing to a String rather than to the stream keeps Jackson
            // from closing System.out when it finishes.
            out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(stats));
        } catch (IOException e) {
            throw new UncheckedIOException("could not write stats as JSON", e);
        }
    }
}
