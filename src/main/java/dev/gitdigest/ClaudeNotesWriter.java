package dev.gitdigest;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.NoCredentialsException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RawMessageStreamEvent;

/**
 * Release notes written by Claude, streamed into the terminal as they arrive.
 *
 * <p>Streaming is not decoration here. Notes for a real range take long enough
 * that a tool which printed nothing until the end would look hung, and the
 * model thinks before it writes, so the pause comes first and the text comes
 * fast. Printing each fragment as it lands turns that into something a person
 * is happy to watch.
 *
 * <p>Failure is a first-class path, not an afterthought - see
 * {@link NotesException#isPartial()} for the distinction that matters.
 */
public class ClaudeNotesWriter implements ReleaseNotesWriter, AutoCloseable {

    /**
     * Anthropic's most capable widely available model.
     *
     * <p>Worth the choice for this job: the difference between adequate and
     * good release notes is entirely in judgement - which six commits are one
     * story, which twenty are not worth a line - and that is what the better
     * model buys.
     */
    private static final String MODEL = "claude-opus-5";

    /**
     * Far more than release notes need, which is the point.
     *
     * <p>This is a ceiling, not a reservation - tokens are billed as they are
     * generated, so a generous cap costs nothing and a tight one costs a whole
     * rerun. It also has to cover the thinking, which counts against the same
     * limit and is invisible in the output, so sizing this to the prose alone
     * would cut the page off mid-sentence on exactly the long ranges where the
     * notes are worth having.
     */
    private static final long MAX_TOKENS = 32_000L;

    private final AnthropicClient client;
    private final PrintStream progress;

    public ClaudeNotesWriter(AnthropicClient client, PrintStream progress) {
        this.client = client;
        this.progress = progress;
    }

    /**
     * A writer, if this machine looks like it has credentials for one.
     *
     * <p>Deliberately not just a check of {@code ANTHROPIC_API_KEY}: the SDK
     * also accepts an auth token and a logged-in profile on disk, and treating
     * an unset variable as "no credentials" would send a profile user down the
     * offline path for no reason.
     *
     * <p>It is a guess, not a guarantee - the point is only to avoid a network
     * round-trip that is certain to come back 401, so that someone who has
     * never set a key gets an instant, sensible answer instead of a stack of
     * API error JSON. A key that exists but is wrong is still caught later, by
     * the request itself.
     */
    public static Optional<ClaudeNotesWriter> fromEnvironment(PrintStream progress) {
        if (!credentialsLookPresent()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ClaudeNotesWriter(AnthropicOkHttpClient.fromEnv(), progress));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Mirrors the order the SDK resolves credentials in, as far as it can.
     *
     * <p>Every source the SDK accepts has to be listed here, because a source
     * this method does not know about is a machine that can reach the API
     * being told it has no credentials - a silent downgrade to the offline
     * writer, which is precisely the outcome the check exists to avoid. Erring
     * towards "probably present" is safe: a wrong guess costs one request.
     */
    private static boolean credentialsLookPresent() {
        return isSet("ANTHROPIC_API_KEY")
                || isSet("ANTHROPIC_AUTH_TOKEN")
                || isSet("ANTHROPIC_PROFILE")
                || federationConfigured()
                || profileDirectory().isPresent();
    }

    /** Workload identity federation, which is how a CI job authenticates. */
    private static boolean federationConfigured() {
        return isSet("ANTHROPIC_FEDERATION_RULE_ID")
                && isSet("ANTHROPIC_ORGANIZATION_ID")
                && isSet("ANTHROPIC_SERVICE_ACCOUNT_ID")
                && (isSet("ANTHROPIC_IDENTITY_TOKEN_FILE") || isSet("ANTHROPIC_IDENTITY_TOKEN"));
    }

    /**
     * Where "ant auth login" leaves its profile, if it is there.
     *
     * <p>The location is per-platform, and getting it wrong is worse than not
     * checking at all: a missed profile sends someone who does have working
     * credentials down the offline path and tells them they have none.
     */
    private static Optional<Path> profileDirectory() {
        // An explicit setting wins, as it does for the CLI and the SDK.
        String configured = System.getenv("ANTHROPIC_CONFIG_DIR");
        if (configured != null && !configured.isBlank()) {
            return existing(Path.of(configured));
        }
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            Optional<Path> windows = existing(Path.of(appData, "Anthropic"));
            if (windows.isPresent()) {
                return windows;
            }
        }
        return existing(Path.of(System.getProperty("user.home"), ".config", "anthropic"));
    }

    private static Optional<Path> existing(Path directory) {
        return Files.isDirectory(directory) ? Optional.of(directory) : Optional.empty();
    }

    private static boolean isSet(String variable) {
        String value = System.getenv(variable);
        return value != null && !value.isBlank();
    }

    @Override
    public String describe() {
        return "written by " + MODEL;
    }

    @Override
    public void write(Changelog changelog, NotesCommand.Tone tone, PrintStream out) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(MAX_TOKENS)
                .system(NotesPrompt.system(tone))
                .addUserMessage(NotesPrompt.user(changelog))
                // Opus 5 thinks by default, and for a writing task of this size
                // the top of the effort range buys nothing a reader would
                // notice while costing tokens and a longer silence.
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
                .build();

        progress.println("gitdigest: asking " + MODEL + " for release notes (" + tone.label() + ")...");

        boolean started = false;
        boolean ended = false;
        String stopReason = null;
        try (StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(params)) {
            for (RawMessageStreamEvent event : (Iterable<RawMessageStreamEvent>) stream.stream()::iterator) {
                if (event.messageStop().isPresent()) {
                    ended = true;
                }
                Optional<String> reason = stopReasonOf(event);
                if (reason.isPresent()) {
                    stopReason = reason.get();
                }
                Optional<String> text = textOf(event);
                if (text.isEmpty()) {
                    // Thinking deltas and the block start/stop bookkeeping. The
                    // model's reasoning is not release notes and does not go to
                    // stdout; only what it decided to write does.
                    continue;
                }
                started = true;
                out.print(text.get());
                // Flushed per fragment: buffering would collect the whole
                // response and defeat the reason for streaming it.
                out.flush();
            }
            if (!started) {
                throw new NotesException(NotesException.Reason.OTHER, "the model returned no text", false, null);
            }
            // A dropped connection does not raise here - the iterator simply
            // runs out, which is indistinguishable from a finished answer
            // unless the terminating event is checked for. Without this, a
            // truncated page of notes would be reported as a success.
            if (!ended) {
                throw failure(NotesException.Reason.NETWORK, "the stream ended early", true, null, out);
            }
            // An allowlist rather than a list of known-bad reasons. "end_turn"
            // is the only way this request finishes cleanly, and anything else
            // - the output limit, a refusal, something added to the API after
            // this was written - means what is on screen is not the notes that
            // were asked for. Guessing which unknown reasons are benign is how
            // a refusal ends up in someone's RELEASE_NOTES.md.
            if (stopReason != null && !"end_turn".equals(stopReason)) {
                throw failure(NotesException.Reason.OTHER,
                        "the model stopped with " + stopReason, started, null, out);
            }
            out.println();
        } catch (NotesException e) {
            throw e;
        } catch (NoCredentialsException | UnauthorizedException | PermissionDeniedException e) {
            throw failure(NotesException.Reason.CREDENTIALS, null, started, e, out);
        } catch (RateLimitException e) {
            throw failure(NotesException.Reason.RATE_LIMIT, null, started, e, out);
        } catch (AnthropicIoException e) {
            throw failure(NotesException.Reason.NETWORK, rootMessage(e), started, e, out);
        } catch (RuntimeException e) {
            throw failure(NotesException.Reason.OTHER, rootMessage(e), started, e, out);
        }
    }

    /**
     * Closes off a half-written page before reporting.
     *
     * <p>Without the newline the warning would be appended to whatever
     * sentence the model was in the middle of.
     */
    private static NotesException failure(NotesException.Reason reason, String detail, boolean started,
            Throwable cause, PrintStream out) {
        if (started) {
            out.println();
        }
        return new NotesException(reason, detail, started, cause);
    }

    /** Why the model stopped, when an event says so. */
    private static Optional<String> stopReasonOf(RawMessageStreamEvent event) {
        return event.messageDelta()
                .flatMap(messageDelta -> messageDelta.delta().stopReason())
                .map(reason -> reason.asString());
    }

    /** The text of a content delta, or empty for every other kind of event. */
    private static Optional<String> textOf(RawMessageStreamEvent event) {
        return event.contentBlockDelta()
                .flatMap(delta -> delta.delta().text())
                .map(textDelta -> textDelta.text());
    }

    /**
     * The innermost explanation, since the SDK's wrapper messages say less
     * about what went wrong than the cause they carry.
     */
    private static String rootMessage(Throwable error) {
        Throwable deepest = error;
        while (deepest.getCause() != null && deepest.getCause() != deepest) {
            deepest = deepest.getCause();
        }
        String message = deepest.getMessage();
        if (message == null || message.isBlank()) {
            return deepest.getClass().getSimpleName();
        }
        // API error bodies are JSON and run to several lines; the first line is
        // the part a person reading a terminal can use.
        String firstLine = message.lines().findFirst().orElse(message).trim();
        return firstLine.length() > 160 ? firstLine.substring(0, 157) + "..." : firstLine;
    }

    @Override
    public void close() {
        client.close();
    }
}
