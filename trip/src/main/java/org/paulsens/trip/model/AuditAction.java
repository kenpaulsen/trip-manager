package org.paulsens.trip.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What an audit record is about.
 *
 * <p>This exists because the old free-text {@code type} argument drifted exactly the way an untyped string
 * always does: five years of history contains {@code LOGIN}, {@code saveTx}, {@code PWReset}, {@code REG} and a
 * single stray {@code Register} -- inconsistent in casing and in naming, and therefore impossible to filter on
 * in a table without a pile of special cases.
 *
 * <p><b>Why a String still comes in:</b> roughly half the call sites are JSFT expressions in XHTML, which
 * cannot reference a Java enum constant. Those pass a string and {@link #from(String)} resolves it. That is
 * also why {@link #UNKNOWN} exists: a record with an unrecognised type must still be RECORDED, because losing
 * an audit record is worse than storing one with a vague label. {@code UNKNOWN} keeps the original text in the
 * message so nothing is destroyed.
 */
public enum AuditAction {
    /** A sign-in attempt; the outcome distinguishes success from failure. */
    LOGIN,
    /** Credentials created for an account. */
    CREATE_CREDS,
    /** A password reset attempt (the forgot-my-password flow). */
    PASSWORD_RESET("PWReset"),
    /** A password was set: by the owner, or by an admin on someone else's account. */
    PASSWORD_CHANGE,
    /** Credentials were removed, so the account can no longer sign in. */
    DELETE_CREDS,
    /** Outbound email. */
    EMAIL,
    /** A person registered, or a registration was changed. */
    REGISTRATION("REG", "Register"),
    /** A financial transaction was recorded or edited. */
    TRANSACTION("saveTx"),
    /** A PayPal payment. */
    PAYMENT,
    /** A todo item's status changed. */
    TODO,
    /** A person record was created, edited or deleted by an admin. */
    PERSON,
    /** A runtime setting changed. */
    CONFIG,
    /** Managed media uploaded or deleted. */
    MEDIA,
    /** A privilege was granted or revoked. */
    PRIVILEGE,
    /** An admin acted as another user. */
    IMPERSONATION,
    /** Any admin action not covered above; the legacy catch-all type. */
    ADMIN,
    /** A person joined a trip chat channel. */
    CHAT_JOIN,
    /** A person left a trip chat channel. */
    CHAT_LEAVE,
    /** Chat moderation or settings change (mute, remove message, roster, etc.). */
    CHAT_ADMIN,
    /**
     * Cross-cutting: something an operator should look at (rate-limit abuse, auto-mute, future subsystems).
     * Not chat-specific; filter with a {@code subsystem.event:} message prefix.
     */
    ALARM,
    /** Unrecognised: the record is kept, the original type text is preserved in the message. */
    UNKNOWN;

    /**
     * Legacy spellings that must keep resolving, so five years of imported history lands on the right action
     * rather than piling up under {@link #UNKNOWN}.
     */
    private final String[] aliases;

    AuditAction(final String... aliases) {
        this.aliases = aliases;
    }

    /** Lower-cased alias (and canonical name) to constant. Built once; lookup is case-insensitive. */
    private static final Map<String, AuditAction> BY_NAME = Arrays.stream(values())
            .flatMap(action -> java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(action.name()), Arrays.stream(action.aliases))
                    .map(key -> Map.entry(key.toLowerCase(Locale.ROOT), action)))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));

    /**
     * Resolves a type string, from XHTML or from an imported legacy record.
     *
     * <p>Never throws and never returns null: an unrecognised value yields {@link #UNKNOWN}, because the
     * alternative -- dropping the record or failing the user's request -- is worse than an imprecise label.
     *
     * @param type the raw type text; may be null, blank, or any historical spelling.
     * @return the matching action, or {@link #UNKNOWN}.
     */
    public static AuditAction from(final String type) {
        if (type == null || type.isBlank()) {
            return UNKNOWN;
        }
        return BY_NAME.getOrDefault(type.trim().toLowerCase(Locale.ROOT), UNKNOWN);
    }

    /** True when {@link #from(String)} would recognise this text; lets an importer report what it could not map. */
    public static boolean isKnown(final String type) {
        return from(type) != UNKNOWN;
    }

    /** Convenience for building a resolver in EL-facing code without exposing the map. */
    public static Function<String, AuditAction> resolver() {
        return AuditAction::from;
    }
}
