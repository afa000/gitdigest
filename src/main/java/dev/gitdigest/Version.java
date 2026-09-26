package dev.gitdigest;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import picocli.CommandLine.IVersionProvider;

/**
 * The version this binary was built as.
 *
 * <p>Read from a resource the build generates, rather than written into the
 * source. A version string typed into the code is correct exactly until
 * someone tags a release and forgets one of the places it appears, and the
 * result - a binary that cheerfully reports the previous version - is the kind
 * of wrong that survives for months because nothing fails.
 */
public final class Version implements IVersionProvider {

    /** When running from somewhere the generated resource is not on the path. */
    private static final String UNKNOWN = "unknown";

    /** Public because picocli constructs the version provider reflectively. */
    public Version() {
    }

    public static String current() {
        try (InputStream resource = Version.class.getResourceAsStream("version.properties")) {
            if (resource == null) {
                return UNKNOWN;
            }
            Properties properties = new Properties();
            properties.load(resource);
            return properties.getProperty("version", UNKNOWN);
        } catch (IOException e) {
            // Not knowing the version is not a reason to refuse to run.
            return UNKNOWN;
        }
    }

    @Override
    public String[] getVersion() {
        return new String[] {"gitdigest " + current()};
    }
}
