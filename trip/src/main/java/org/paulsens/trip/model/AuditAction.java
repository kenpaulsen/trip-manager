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
    /** A password reset attempt (the retired emailed-password flow; kept so old records still resolve). */
    PASSWORD_RESET("PWReset"),
    /** A login code was requested; the outcome records whether one was actually sent. */
    CODE_REQUEST,
    /** A passkey (WebAuthn credential) was registered. */
    PASSKEY_REGISTER,
    /** A passkey was removed. */
    PASSKEY_DELETE,
    /** A bearer token pair (refresh + access) was issued to an API client (docs/api-tokens.md). */
    TOKEN_ISSUE,
    /** A bearer access token was refreshed; the refresh validator rotated. */
    TOKEN_REFRESH,
    /** A bearer refresh token was revoked, along with its access-token children. */
    TOKEN_REVOKE,
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
    /** Family-account membership changed: member created/linked/unlinked/deleted, manager granted/revoked. */
    FAMILY,
    /** An organization was created or edited, or its membership/admins changed. */
    ORGANIZATION,
    /** A runtime setting changed. */
    CONFIG,
    /** Managed media uploaded or deleted. */
    MEDIA,
    /** A content template was created, edited, restored or deleted. */
    TEMPLATE,
    /** Template-driven page content was created, edited, restored or deleted. */
    CONTENT,
    /** A privilege was granted or revoked. */
    PRIVILEGE,
    /**
     * A deployment was started from the admin UI.
     *
     * <p>Its own constant rather than {@code ADMIN}: a deployment replaces the running site, so "who released
     * this, and when" is a question worth being able to ask on its own -- usually while something is wrong.
     */
    DEPLOY,
    /** An admin acted as another user. */
    IMPERSONATION,
    /**
     * A trip was permanently deleted, with every dependent row (registrations, events, todos, privileges,
     * chat, photos). Its own constant for the same reason {@link #DEPLOY} has one: "who destroyed this
     * pilgrimage, and when" must be answerable on its own -- usually while someone is asking where it went.
     */
    TRIP_DELETE,
    /** Any admin action not covered above; the legacy catch-all type. */
    ADMIN,
    /** A person joined a trip chat channel. */
    CHAT_JOIN,
    /** A person left a trip chat channel. */
    CHAT_LEAVE,
    /** A chat invite link was minted or revoked (redemption records {@link #CHAT_JOIN}). */
    CHAT_INVITE,
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
