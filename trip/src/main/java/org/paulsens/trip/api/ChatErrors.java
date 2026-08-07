package org.paulsens.trip.api;

/**
 * The wire error codes for the chat API. Defined once, here, because the client's behaviour is keyed off them and
 * a typo or a synonym is indistinguishable from a bug at the far end.
 *
 * <p>The distinction that matters most is {@link #LEFT_CHANNEL} versus {@link #REMOVED_FROM_CHANNEL}. Both are a
 * 403 and both stop the poll, but a person who <em>chose</em> to leave gets a Rejoin button and a person an admin
 * <em>removed</em> must not — only an admin can undo that. Collapsing them into one code makes the correct UI
 * impossible to build, so they stay separate all the way to the browser.
 *
 * <p>Client contract, in one place:
 * <ul>
 *   <li>{@link #NOT_AUTHENTICATED} (401) — stop polling; offer sign-in. Recoverable.</li>
 *   <li>{@link #NOT_A_TRIP_MEMBER} (403) — stop polling permanently; replace the composer.</li>
 *   <li>{@link #LEFT_CHANNEL} (403) — stop polling; show Rejoin.</li>
 *   <li>{@link #REMOVED_FROM_CHANNEL} (403) — stop polling; <b>no</b> Rejoin.</li>
 *   <li>{@link #CHANNEL_ARCHIVED} — 200 on read, 403 on send; hide the composer.</li>
 *   <li>{@link #MUTED} (403 on send only) — reads continue; never phrase it as a rate limit.</li>
 *   <li>{@link #RATE_LIMITED} (429) — keep polling, keep the text, show the countdown. Not terminal.</li>
 * </ul>
 */
public final class ChatErrors {

    public static final String NOT_AUTHENTICATED = "NOT_AUTHENTICATED";
    public static final String NOT_A_TRIP_MEMBER = "NOT_A_TRIP_MEMBER";
    public static final String LEFT_CHANNEL = "LEFT_CHANNEL";
    public static final String REMOVED_FROM_CHANNEL = "REMOVED_FROM_CHANNEL";
    public static final String CHANNEL_ARCHIVED = "CHANNEL_ARCHIVED";
    /** The trip has chat turned off entirely. Terminal for a client: stop polling, there is nothing to come back to. */
    public static final String CHAT_DISABLED = "CHAT_DISABLED";
    public static final String MUTED = "MUTED";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String SLOW_MODE = "SLOW_MODE";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String BAD_CHANNEL = "BAD_CHANNEL";
    public static final String MESSAGE_EMPTY = "MESSAGE_EMPTY";
    public static final String MESSAGE_TOO_LONG = "MESSAGE_TOO_LONG";
    /**
     * An attachment reference the send cannot honour: over the per-message cap, or a key that was never
     * staged by this person for this trip (expired, tampered, or a restart emptied the staging registry).
     * Not terminal: the composer keeps the text, tells the person, and they re-attach.
     */
    public static final String BAD_ATTACHMENT = "BAD_ATTACHMENT";
    public static final String CSRF = "CSRF_REQUIRED";
    public static final String UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";
    public static final String NOT_ACCEPTABLE = "NOT_ACCEPTABLE";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String REACTIONS_DISABLED = "REACTIONS_DISABLED";
    public static final String BAD_EMOJI = "BAD_EMOJI";
    public static final String EDIT_DISABLED = "EDIT_DISABLED";
    /**
     * Distinct from {@link #FORBIDDEN} on purpose: the client's remedy differs. A closed window is permanent and
     * the edit affordance should disappear; a plain refusal (someone else's message) should never have offered one.
     */
    public static final String EDIT_WINDOW_CLOSED = "EDIT_WINDOW_CLOSED";
    public static final String STORE_FAILED = "STORE_FAILED";
    public static final String INTERNAL = "INTERNAL";

    private ChatErrors() {
    }

    /** Maps a {@code ChatCommands.SendResult} code onto the wire code for this API. */
    public static String forSendResult(final String resultCode) {
        if (resultCode == null) {
            return INTERNAL;
        }
        return switch (resultCode) {
            case "muted" -> MUTED;
            case "archived" -> CHANNEL_ARCHIVED;
            case "rate_limit", "burst", "sustained", "global" -> RATE_LIMITED;
            case "slow_mode" -> SLOW_MODE;
            case "empty" -> MESSAGE_EMPTY;
            case "too_long" -> MESSAGE_TOO_LONG;
            case "attachment", "too_many_photos" -> BAD_ATTACHMENT;
            case "forbidden" -> FORBIDDEN;
            case "store" -> STORE_FAILED;
            default -> INTERNAL;
        };
    }

    /**
     * Maps a {@code ChatCommands.ReactResult} code onto the wire code.
     *
     * <p>Read denials come back from the bean already in wire form (they are produced by {@code readDenial}), so they
     * pass through unchanged rather than being re-spelled here — re-mapping them would be a second place for
     * {@code LEFT_CHANNEL} and {@code REMOVED_FROM_CHANNEL} to drift apart.
     */
    public static String forReactResult(final String resultCode) {
        if (resultCode == null) {
            return INTERNAL;
        }
        return switch (resultCode) {
            case "bad_emoji" -> BAD_EMOJI;
            case "not_found" -> NOT_FOUND;
            case "store" -> STORE_FAILED;
            default -> resultCode;
        };
    }

    /** Maps an edit outcome onto the wire. Read denials and the EDIT_* codes already arrive in wire form. */
    public static String forEditResult(final String resultCode) {
        if (resultCode == null) {
            return INTERNAL;
        }
        return switch (resultCode) {
            case "empty" -> MESSAGE_EMPTY;
            case "too_long" -> MESSAGE_TOO_LONG;
            case "not_found" -> NOT_FOUND;
            case "store" -> STORE_FAILED;
            default -> resultCode;
        };
    }
}
