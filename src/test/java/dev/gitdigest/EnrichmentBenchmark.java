package dev.gitdigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Measures what Phase 4 was for, and prints the number that goes in the README.
 *
 * <p>Run it with {@code .\gradlew benchmark}. It is tagged out of the normal
 * test run on purpose: it takes about fifteen seconds, most of it deliberately
 * spent asleep, and a timing figure belongs in a report rather than in the
 * feedback loop of every build.
 *
 * <p>The server is local but answers slowly, at a latency picked to match what
 * api.github.com actually returns. Measuring against the real API would be
 * neither repeatable nor kind, and measuring against an instant server would
 * measure nothing at all: the speedup here is entirely the latency that
 * overlapping requests no longer spend waiting one at a time.
 *
 * <p>Both runs go through the real client and the real enricher; the only thing
 * that differs is {@code --jobs}. A benchmark that compared two different code
 * paths would be measuring the benchmark.
 */
@Tag("benchmark")
class EnrichmentBenchmark {

    /** Roughly what a /commits/{sha}/pulls call costs over the internet. */
    private static final int LATENCY_MILLIS = 200;
    private static final int COMMITS = 40;
    private static final int PARALLEL_JOBS = ChangelogEnricher.DEFAULT_JOBS;

    private static final GitHubRepo REPO = new GitHubRepo("acme", "widgets");

    private HttpServer server;
    private AtomicInteger served;
    private String baseUrl;

    @TempDir
    Path cacheDir;

    @BeforeEach
    void startSlowServer() throws IOException {
        served = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // The server must be able to answer concurrently, or it would become
        // the bottleneck and the measurement would be of this test's own queue.
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(LATENCY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            int number = served.incrementAndGet();
            byte[] body = ("[{\"number\":" + number + ",\"title\":\"A pull request\","
                    + "\"user\":{\"login\":\"someone\"},"
                    + "\"html_url\":\"https://github.com/acme/widgets/pull/" + number + "\"}]")
                    .getBytes(StandardCharsets.UTF_8);
            // No ETag, so nothing is cached and the second run pays full price
            // like the first one. Caching is a separate win, measured elsewhere.
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void parallelFetchingIsFaster() {
        Changelog work = changelogOf(COMMITS);

        Duration serial = timeEnrichment(work, 1);
        Duration parallel = timeEnrichment(work, PARALLEL_JOBS);

        double speedup = (double) serial.toMillis() / parallel.toMillis();
        System.out.printf(
                "%n  %d commits at %dms latency%n"
                + "    --jobs 1  %6.2fs%n"
                + "    --jobs %-2d %6.2fs%n"
                + "    speedup   %6.2fx%n%n",
                COMMITS, LATENCY_MILLIS,
                serial.toMillis() / 1000.0,
                PARALLEL_JOBS, parallel.toMillis() / 1000.0,
                speedup);

        // Deliberately below the figure this actually reaches. The ceiling is
        // the pacer, not the thread count, and a machine under load should not
        // turn a real improvement into a red build.
        assertTrue(speedup >= 2.0, "expected parallel fetching to be at least 2x faster, got " + speedup + "x");
    }

    private Duration timeEnrichment(Changelog work, int jobs) {
        try (GitHubClient client = new GitHubClient(
                HttpClient.newHttpClient(), new HttpCache(cacheDir.resolve("j" + jobs)), null, baseUrl)) {
            long start = System.nanoTime();
            Changelog result = new ChangelogEnricher(client, REPO, quiet(), jobs).enrich(work);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            // A fast run that skipped half the work would not be a fast run.
            assertEquals(COMMITS, result.totalEntries());
            assertTrue(result.groups().get(ChangeGroup.FEATURES).stream().allMatch(e -> e.pullRequest() != null),
                    "--jobs " + jobs + " did not enrich every entry");
            return elapsed;
        }
    }

    private static Changelog changelogOf(int count) {
        List<ChangelogEntry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add(new ChangelogEntry(String.format("%040d", i), "feat", null, false,
                    "thing number " + i, "Ada", null));
        }
        Map<ChangeGroup, List<ChangelogEntry>> groups = new EnumMap<>(ChangeGroup.class);
        groups.put(ChangeGroup.FEATURES, List.copyOf(entries));
        return new Changelog("v1.0", "v2.0", groups);
    }

    private static PrintStream quiet() {
        return new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8);
    }
}
