package dev.gitdigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpCacheTest {

    @TempDir
    Path tempDir;

    @Test
    void storesAndReadsBackAnEntry() {
        HttpCache cache = new HttpCache(tempDir);
        cache.put("https://api.github.com/a", "\"etag-1\"", "{\"ok\":true}");

        Optional<HttpCache.Entry> found = cache.get("https://api.github.com/a");

        assertTrue(found.isPresent());
        assertEquals("\"etag-1\"", found.get().etag());
        assertEquals("{\"ok\":true}", found.get().body());
    }

    @Test
    void differentUrlsDoNotCollide() {
        HttpCache cache = new HttpCache(tempDir);
        cache.put("https://api.github.com/a", "e1", "first");
        cache.put("https://api.github.com/b", "e2", "second");

        assertEquals("first", cache.get("https://api.github.com/a").orElseThrow().body());
        assertEquals("second", cache.get("https://api.github.com/b").orElseThrow().body());
    }

    @Test
    void anUnknownUrlIsSimplyAbsent() {
        assertTrue(new HttpCache(tempDir).get("https://api.github.com/never-seen").isEmpty());
    }

    @Test
    void aResponseWithoutAnEtagIsNotStored() {
        HttpCache cache = new HttpCache(tempDir);
        cache.put("https://api.github.com/a", null, "body");
        cache.put("https://api.github.com/b", "  ", "body");

        // nothing to revalidate with later, so there is no point keeping it
        assertTrue(cache.get("https://api.github.com/a").isEmpty());
        assertTrue(cache.get("https://api.github.com/b").isEmpty());
    }

    @Test
    void aCorruptEntryIsIgnoredRatherThanThrown() throws IOException {
        HttpCache cache = new HttpCache(tempDir);
        cache.put("https://api.github.com/a", "e1", "first");

        // simulate a half-written or hand-edited file
        Path onDisk = Files.list(tempDir).filter(f -> f.toString().endsWith(".json")).findFirst().orElseThrow();
        Files.writeString(onDisk, "{ this is not json");

        assertTrue(cache.get("https://api.github.com/a").isEmpty(),
                "a damaged cache entry must not break the command");
    }

    @Test
    void writesLandInsideTheGivenDirectory() {
        Path nested = tempDir.resolve("does/not/exist/yet");
        HttpCache cache = new HttpCache(nested);
        cache.put("https://api.github.com/a", "e1", "body");

        assertEquals("body", cache.get("https://api.github.com/a").orElseThrow().body());
    }
}
