package dev.gitdigest;

import java.io.PrintStream;

public interface StatsRenderer {

    void print(RepoStats stats, PrintStream out);

    static StatsRenderer forFormat(OutputFormat format) {
        return switch (format) {
            case TABLE -> new StatsPrinter();
            case JSON -> new StatsJsonRenderer();
            case MARKDOWN -> new StatsMarkdownRenderer();
        };
    }
}
