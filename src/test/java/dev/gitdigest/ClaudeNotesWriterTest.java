package dev.gitdigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The streaming path, exercised against a local server that speaks the API's
 * wire format.
 *
 * <p>Pointing the real SDK at a fake endpoint tests the parts that would
 * otherwise only ever be tested by a run that costs money: that a stream of
 * deltas is reassembled in order, that thinking is not mistaken for output,
 * that an interrupted stream is reported as interrupted rather than quietly
 * truncated, and that each kind of failure is classified into the one the
 * command knows how to act on.
 */
class ClaudeNotesWriterTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> requestBody = new AtomicReference<>("");

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /** Serves one canned Server-Sent Events body. */
    private void respondWithStream(String sse) {
        server.createContext("/", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            byte[] body = sse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }

    private void respondWithStatus(int status, String body) {
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    private static String event(String type, String json) {
        return "event: " + type + "\ndata: " + json + "\n\n";
    }

    private static String messageStart() {
        return event("message_start", """
                {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant",\
                "model":"claude-opus-5","content":[],"stop_reason":null,"stop_sequence":null,\
                "usage":{"input_tokens":10,"output_tokens":1}}}""");
    }

    private static String textBlockStart() {
        return event("content_block_start",
                """
                {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""");
    }

    private static String textDelta(String text) {
        return event("content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"" + text + "\"}}");
    }

    private static String thinkingDelta(String text) {
        return event("content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"" + text + "\"}}");
    }

    private static String closingEvents() {
        return event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
                + event("message_delta", """
                {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},\
                "usage":{"output_tokens":12}}""")
                + event("message_stop", "{\"type\":\"message_stop\"}");
    }

    private AnthropicClient client() {
        return AnthropicOkHttpClient.builder()
                .apiKey("sk-ant-test")
                .baseUrl(baseUrl)
                // Retries would turn a deliberate 429 into three of them and
                // make the test wait out the backoff for no added confidence.
                .maxRetries(0)
                .build();
    }

    private String write(ByteArrayOutputStream sink) {
        PrintStream out = new PrintStream(sink, true, StandardCharsets.UTF_8);
        try (ClaudeNotesWriter writer = new ClaudeNotesWriter(client(), quiet())) {
            writer.write(changelog(), NotesCommand.Tone.FORMAL, out);
        }
        // println() ends the page with the platform's line separator, which on
        // Windows is CRLF. That is right for a terminal and wrong for a string
        // comparison written on a different machine.
        return sink.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private NotesException writeExpectingFailure(ByteArrayOutputStream sink) {
        PrintStream out = new PrintStream(sink, true, StandardCharsets.UTF_8);
        try (ClaudeNotesWriter writer = new ClaudeNotesWriter(client(), quiet())) {
            return assertThrows(NotesException.class,
                    () -> writer.write(changelog(), NotesCommand.Tone.FORMAL, out));
        }
    }

    @Test
    void reassemblesTheDeltasInOrder() {
        respondWithStream(messageStart() + textBlockStart()
                + textDelta("## Release v2.0") + textDelta("\\n\\n") + textDelta("It is faster now.")
                + closingEvents());
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        String notes = write(sink);

        assertEquals("## Release v2.0\n\nIt is faster now.\n", notes);
    }

    @Test
    void thinkingIsNotOutput() {
        // The model reasons before it writes. That reasoning is not release
        // notes and must never reach stdout.
        respondWithStream(messageStart() + textBlockStart()
                + thinkingDelta("Let me group these commits...")
                + textDelta("## Release v2.0")
                + closingEvents());

        String notes = write(new ByteArrayOutputStream());

        assertFalse(notes.contains("Let me group"));
        assertEquals("## Release v2.0\n", notes);
    }

    @Test
    void theRequestCarriesThePromptAndTheModel() {
        respondWithStream(messageStart() + textBlockStart() + textDelta("ok") + closingEvents());

        write(new ByteArrayOutputStream());

        String sent = requestBody.get();
        assertTrue(sent.contains("claude-opus-5"), "the model should be the one this class documents");
        assertTrue(sent.contains("Never invent a change"), "the system half should travel with the request");
        assertTrue(sent.contains("add pagination"), "the commits should travel with the request");
        assertTrue(sent.contains("\"stream\":true"));
    }

    @Test
    void aStreamThatStopsPartwayIsReportedAsPartial() {
        // No message_stop: the connection ended mid-answer. Half a page of
        // notes is already on screen, so the run cannot silently restart - and
        // it must not pretend the notes are complete either.
        respondWithStream(messageStart() + textBlockStart()
                + textDelta("## Release v2.0") + textDelta("\\n\\nIt is fast"));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        NotesException failure = writeExpectingFailure(sink);

        assertEquals(NotesException.Reason.NETWORK, failure.reason());
        assertTrue(failure.isPartial(), "text had already been printed");
        assertTrue(sink.toString(StandardCharsets.UTF_8).startsWith("## Release v2.0"),
                "what did arrive should be left on screen");
    }

    @Test
    void hittingTheOutputLimitCountsAsIncomplete() {
        // The stream is well-formed and ends properly; it is the notes that
        // are cut off. Reporting success here would hand someone a page whose
        // last section is missing without telling them.
        respondWithStream(messageStart() + textBlockStart() + textDelta("## Release v2.0")
                + event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
                + event("message_delta", """
                {"type":"message_delta","delta":{"stop_reason":"max_tokens","stop_sequence":null},                "usage":{"output_tokens":8000}}""")
                + event("message_stop", "{\"type\":\"message_stop\"}"));

        NotesException failure = writeExpectingFailure(new ByteArrayOutputStream());

        assertTrue(failure.isPartial());
        assertTrue(failure.getMessage().contains("max_tokens"), failure.getMessage());
    }

    @Test
    void aRefusalIsNotMistakenForNotes() {
        // A refusal arrives as a perfectly healthy HTTP 200 with text in it.
        // Taken at face value it would be written to the terminal, announced
        // as "notes written by claude-opus-5", and exit 0 - straight into
        // someone's RELEASE_NOTES.md.
        respondWithStream(messageStart() + textBlockStart()
                + textDelta("I can't help with that.")
                + event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
                + event("message_delta", """
                {"type":"message_delta","delta":{"stop_reason":"refusal","stop_sequence":null},\
                "usage":{"output_tokens":8}}""")
                + event("message_stop", "{\"type\":\"message_stop\"}"));

        NotesException failure = writeExpectingFailure(new ByteArrayOutputStream());

        assertTrue(failure.getMessage().contains("refusal"), failure.getMessage());
    }

    @Test
    void aStreamWithNoTextIsNotPartial() {
        // Nothing was printed, so the caller is free to fall back and produce a
        // complete set of notes the other way.
        respondWithStream(messageStart() + textBlockStart() + closingEvents());

        NotesException failure = writeExpectingFailure(new ByteArrayOutputStream());

        assertFalse(failure.isPartial());
        assertEquals(NotesException.Reason.OTHER, failure.reason());
    }

    @Test
    void aRejectedKeyIsACredentialsProblem() {
        respondWithStatus(401,
                "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"bad key\"}}");

        NotesException failure = writeExpectingFailure(new ByteArrayOutputStream());

        assertEquals(NotesException.Reason.CREDENTIALS, failure.reason());
        assertFalse(failure.isPartial());
    }

    @Test
    void aSpentAllowanceIsARateLimit() {
        respondWithStatus(429,
                "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}");

        assertEquals(NotesException.Reason.RATE_LIMIT,
                writeExpectingFailure(new ByteArrayOutputStream()).reason());
    }

    @Test
    void anUnreachableApiIsANetworkProblem() {
        server.stop(0);

        NotesException failure = writeExpectingFailure(new ByteArrayOutputStream());

        assertEquals(NotesException.Reason.NETWORK, failure.reason());
        assertFalse(failure.isPartial());
    }

    @Test
    void theFailureMessageStaysReadable() {
        // API error bodies are multi-line JSON with a request id. A terminal
        // warning is one line or it is not read.
        respondWithStatus(500,
                "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"" + "x".repeat(400) + "\"}}");

        String message = writeExpectingFailure(new ByteArrayOutputStream()).getMessage();

        assertTrue(message.length() <= 200, "message was " + message.length() + " characters");
        assertFalse(message.contains("\n"));
    }

    private static Changelog changelog() {
        Map<ChangeGroup, List<ChangelogEntry>> groups = new EnumMap<>(ChangeGroup.class);
        groups.put(ChangeGroup.FEATURES, List.of(new ChangelogEntry(
                "0".repeat(40), "feat", null, false, "add pagination", "Ada Lovelace", null)));
        return new Changelog("v1.0", "v2.0", groups);
    }

    private static PrintStream quiet() {
        return new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8);
    }
}
