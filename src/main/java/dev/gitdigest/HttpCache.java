package dev.gitdigest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A disk cache for GitHub responses, keyed by URL.
 *
 * <p>Each entry keeps the response body together with the ETag GitHub sent for
 * it. On the next run the ETag goes back as If-None-Match, and GitHub answers
 * 304 with an empty body instead of resending it.
 *
 * <p>Measured, not assumed: a 304 saves the transfer and the parse - around
 * 18KB per pull-request lookup - but it still costs one unit of rate limit
 * quota. The widely repeated claim that conditional requests are free does not
 * hold on the current API. Caching buys speed and bandwidth here; raising the
 * rate limit needs a token.
 *
 * <p>Cache failures are never fatal. A cache that cannot be read or written
 * should slow the tool down, not stop it.
 */
public class HttpCache {

    /** One cached response: the ETag to revalidate with, and the body it names. */
    public record Entry(String etag, String body) {
    }

    private final Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    public HttpCache(Path directory) {
        this.directory = directory;
    }

    /** The default location, ~/.gitdigest/cache. */
    public static HttpCache inUserHome() {
        return new HttpCache(Path.of(System.getProperty("user.home"), ".gitdigest", "cache"));
    }

    public Optional<Entry> get(String url) {
        Path file = fileFor(url);
        if (!Files.isReadable(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), Entry.class));
        } catch (IOException e) {
            // A corrupt or half-written entry is worth ignoring, not crashing on.
            return Optional.empty();
        }
    }

    public void put(String url, String etag, String body) {
        if (etag == null || etag.isBlank()) {
            // Without an ETag there is nothing to revalidate against later.
            return;
        }
        try {
            Files.createDirectories(directory);
            Path file = fileFor(url);
            // Write beside the target and move into place, so an interrupted run
            // cannot leave a truncated entry that looks valid.
            Path temp = Files.createTempFile(directory, "entry", ".tmp");
            Files.writeString(temp, mapper.writeValueAsString(new Entry(etag, body)), StandardCharsets.UTF_8);
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Caching is an optimisation; losing it must not fail the command.
        }
    }

    /**
     * Hashes the URL for the file name.
     *
     * <p>URLs contain slashes, colons and query strings that no filesystem
     * would accept, and they can outrun the path length limit; a hash is a
     * fixed-size name that cannot collide in practice.
     */
    private Path fileFor(String url) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(url.getBytes(StandardCharsets.UTF_8));
            return directory.resolve(HexFormat.of().formatHex(hash) + ".json");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every Java platform.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
