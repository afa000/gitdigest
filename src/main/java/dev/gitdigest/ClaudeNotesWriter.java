package dev.gitdigest;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.NoCredentialsException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.JsonNode;

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
     * Claude Opus 5.5, the current Opus model - and cheaper than Opus 5 was.
     *
     * <p>Worth the choice for this job: the difference between adequate and
     * good release notes is entirely in judgement - which six commits are one
     * story, which twenty are not worth a line - and that is what a strong
     * model buys.
     */
    private static final String MODEL = "claude-opus-5-5";

    /**
     * Server-side fallbacks, in the form that lets the API pick the substitute.
     *
     * <p>Opus 5.5's safety classifiers can decline a request, and commit
     * messages are arbitrary text - a security fix described plainly can look
     * like something else. Without this, a decline drops the user to the
     * offline notes, or leaves half a page on screen. With it, the API re-runs
     * the request on another model in the same stream. "default" rather than a
     * named model, because the right substitute depends on why the request was
     * declined, and a pinned one is a migration owed when it is retired.
     *
     * <p>Sent as a raw body property: the SDK version pinned here predates the
     * typed builder for it, and the version that has one would add some 15 MB
     * to the jar for the sake of a single field.
     */
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

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

    /** Every model that wrote part of the notes, in order - usually just one. */
    private final Set<String> writtenBy = new LinkedHashSet<>();

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

    /**
     * Who wrote the notes, which after a fallback is not the model asked.
     *
     * <p>Read from the stream rather than assumed: claiming "claude-opus-5-5"
     * for text another model wrote is the same kind of quiet misreport this
     * class goes out of its way to avoid everywhere else.
     */
    @Override
    public String describe() {
        if (writtenBy.isEmpty() || writtenBy.equals(Set.of(MODEL))) {
            return "written by " + MODEL;
        }
        return "written by " + String.join(", then ", writtenBy) + " (the fallback for " + MODEL + ")";
    }

    @Override
    public void write(Changelog changelog, NotesCommand.Tone tone, PrintStream out) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(MAX_TOKENS)
                .system(NotesPrompt.system(tone))
                .addUserMessage(NotesPrompt.user(changelog))
                // Opus 5.5 always thinks; effort is the only dial. Medium is
                // already its default, but it is set here so a model change
                // cannot move it silently - Opus 5 defaulted to high, and for a
                // page of release notes the top of the range buys nothing a
                // reader would notice.
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.MEDIUM).build())
                .addBeta(FALLBACK_BETA)
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build();

        progress.println("gitdigest: asking " + MODEL + " for release notes (" + tone.label() + ")...");

        boolean started = false;
        boolean ended = false;
        String stopReason = null;
        try (StreamResponse<BetaRawMessageStreamEvent> stream = client.beta().messages().createStreaming(params)) {
            for (BetaRawMessageStreamEvent event : (Iterable<BetaRawMessageStreamEvent>) stream.stream()::iterator) {
                if (event.messageStop().isPresent()) {
                    ended = true;
                }
                // A decline before any output is retried before the stream
                // opens, so message_start already names the model that writes.
                // A decline partway is marked by a fallback block instead, and
                // the new model carries on from the text already printed.
                event.messageStart().ifPresent(start -> writtenBy.add(start.message().model().asString()));
                fallbackModelOf(event).ifPresent(writtenBy::add);
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
            // a refusal ends up in someone's RELEASE_NOTES.md. Fallbacks make a
            // refusal rarer, not impossible: if the fallback model is itself
            // rate-limited, the API returns the refusal instead.
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

    /**
     * The model a fallback block hands over to, if this event starts one.
     *
     * <p>The pinned SDK has no type for the block, so it arrives as raw JSON.
     */
    private static Optional<String> fallbackModelOf(BetaRawMessageStreamEvent event) {
        return event.contentBlockStart()
                .flatMap(start -> start.contentBlock()._json())
                .map(json -> json.convert(JsonNode.class))
                .filter(block -> "fallback".equals(block.path("type").asText()))
                .map(block -> block.path("to").path("model").asText())
                .filter(model -> !model.isBlank());
    }

    /** Why the model stopped, when an event says so. */
    private static Optional<String> stopReasonOf(BetaRawMessageStreamEvent event) {
        return event.messageDelta()
                .flatMap(messageDelta -> messageDelta.delta().stopReason())
                .map(reason -> reason.asString());
    }

    /** The text of a content delta, or empty for every other kind of event. */
    private static Optional<String> textOf(BetaRawMessageStreamEvent event) {
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
