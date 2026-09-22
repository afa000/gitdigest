package dev.gitdigest;

/**
 * The model could not write the notes.
 *
 * <p>Carries the two things the caller needs to decide what to do: how far the
 * output got, and what kind of problem it was.
 */
public class NotesException extends RuntimeException {

    /**
     * Why it failed, at the level of detail that changes the advice.
     *
     * <p>Not a copy of the HTTP status. A 401 and a 403 are different to the
     * API and identical to someone who has not set a key, while a rate limit
     * and an unreachable host both mean "try later" for opposite reasons.
     */
    public enum Reason {
        /** No credentials, or the ones present were rejected. */
        CREDENTIALS("no usable Anthropic credentials"),
        /** The account's allowance is spent. */
        RATE_LIMIT("the API rate limit is spent"),
        /** The API could not be reached, or did not answer in time. */
        NETWORK("the API could not be reached"),
        /** Anything else, including an empty response. */
        OTHER("the request failed");

        private final String summary;

        Reason(String summary) {
            this.summary = summary;
        }

        public String summary() {
            return summary;
        }
    }

    private final Reason reason;
    private final boolean partial;

    public NotesException(Reason reason, String detail, boolean partial, Throwable cause) {
        super(detail == null || detail.isBlank() ? reason.summary() : reason.summary() + ": " + detail, cause);
        this.reason = reason;
        this.partial = partial;
    }

    public NotesException(Reason reason, boolean partial) {
        this(reason, null, partial, null);
    }

    public Reason reason() {
        return reason;
    }

    /**
     * Whether any notes had already reached the terminal when this was thrown.
     *
     * <p>It decides what the caller may do about it. Nothing written yet means
     * the run can quietly fall back to the offline writer and the user still
     * gets complete notes. Half a page already printed means it cannot:
     * starting over would print a second, differently-worded set of notes
     * underneath the first, and the only honest move left is to say that what
     * is on screen is incomplete.
     */
    public boolean isPartial() {
        return partial;
    }
}
