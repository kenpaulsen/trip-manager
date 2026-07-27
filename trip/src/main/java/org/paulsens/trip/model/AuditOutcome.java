package org.paulsens.trip.model;

import java.util.Locale;

/**
 * Whether the audited action succeeded.
 *
 * <p>Today this is encoded in prose -- a failed login is the words {@code "Login Failed!"} inside the message --
 * which means "show me every failed login" is a substring search over free text rather than a filter. Making it
 * a field is most of what turns the trail into something a table can answer questions about.
 *
 * <p>{@link #UNKNOWN} is for imported history, where the message may not say either way. It is deliberately not
 * the same as {@link #SUCCESS}: claiming an old record succeeded when the log never said so would be inventing
 * history, which is the one thing an audit trail must never do.
 */
public enum AuditOutcome {
    SUCCESS,
    FAILURE,
    /** Not recorded -- almost always a legacy record whose text was ambiguous. Never assume this means success. */
    UNKNOWN;

    /** Resolves loosely, for EL callers and importers; anything unrecognised is {@link #UNKNOWN}, never a guess. */
    public static AuditOutcome from(final String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "success", "succeeded", "ok", "true" -> SUCCESS;
            case "failure", "failed", "fail", "error", "false" -> FAILURE;
            default -> UNKNOWN;
        };
    }

    /** Maps a boolean result at a call site, which is how most Java callers already know what happened. */
    public static AuditOutcome of(final boolean succeeded) {
        return succeeded ? SUCCESS : FAILURE;
    }
}
