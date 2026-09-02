package org.paulsens.trip.security;

import java.util.Optional;
import org.paulsens.trip.model.AuthToken;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.util.RandomData;

/**
 * The mechanics every selector:validator credential shares -- remember-me cookies, API refresh tokens, API
 * access tokens: minting a pair, parsing a presented one, and judging a presented validator against the
 * stored row. Extracted from {@code RememberMeService} so the bearer-token flows reuse the exact rotation and
 * theft semantics instead of drifting from them (see {@code docs/api-tokens.md}).
 *
 * <p>Rotation keeps the SAME selector and replaces the validator, which is what buys theft detection: a known
 * selector with a wrong validator is evidence, not noise. The one innocent case is a client racing its own
 * rotation, so the validator the LAST rotation replaced stays honored for {@value #ROTATION_GRACE_SECONDS}
 * seconds -- one generation back, no further.
 */
public final class SelectorTokens {

    /** How long the pre-rotation validator stays honored; see the class javadoc for why it exists at all. */
    public static final long ROTATION_GRACE_SECONDS = 30;

    private static final char SEPARATOR = ':';
    private static final int SELECTOR_CHARS = 9;
    private static final int VALIDATOR_CHARS = 32;

    private SelectorTokens() {
    }

    /** What a presented validator is, judged against the stored row. The caller maps these to actions. */
    public enum Judgment {
        /** The live validator: authenticate, and (for rotating kinds) rotate. */
        CURRENT,
        /** The validator the last rotation replaced, within the grace window: authenticate, do NOT rotate. */
        GRACE,
        /** The row is past its absolute expiry: refuse and delete it. */
        EXPIRED,
        /** A stale validator against a live selector: the theft signature. Refuse, delete, alarm. */
        THEFT
    }

    /** A freshly minted pair. Only {@link #presentable()} ever leaves the server; the row stores the hash. */
    public record Minted(String selector, String validator) {
        public String presentable() {
            return selector + SEPARATOR + validator;
        }

        public String validatorHash() {
            return Digests.sha256Base64(validator);
        }
    }

    /** The two halves of a presented credential, split but not yet checked against anything. */
    public record Parsed(String selector, String validator) {
        public String validatorHash() {
            return Digests.sha256Base64(validator);
        }
    }

    public static Minted mint() {
        return new Minted(RandomData.genSecureToken(SELECTOR_CHARS), mintValidator());
    }

    /** A fresh validator alone, for rotation -- the selector must survive rotation (it is the theft key). */
    public static String mintValidator() {
        return RandomData.genSecureToken(VALIDATOR_CHARS);
    }

    /**
     * Splits {@code selector:validator}, rejecting malformed input before any I/O -- a garbage flood must
     * never reach the store. Empty on null, a missing separator, or an empty half.
     */
    public static Optional<Parsed> parse(final String presented) {
        if (presented == null) {
            return Optional.empty();
        }
        final int split = presented.indexOf(SEPARATOR);
        if (split <= 0 || split == presented.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(new Parsed(presented.substring(0, split), presented.substring(split + 1)));
    }

    /**
     * Judges a presented validator hash against the stored row at {@code now} (epoch seconds; passed in so
     * tests need no sleeping). Expiry is checked FIRST: an expired row is refused even to the right
     * validator, and an expired row presented with a wrong one is expiry, not theft.
     */
    public static Judgment judge(final AuthToken row, final String presentedHash, final long now) {
        if (row.getExpires() == null || row.getExpires() <= now) {
            return Judgment.EXPIRED;
        }
        if (Digests.matches(row.getValidatorHash(), presentedHash)) {
            return Judgment.CURRENT;
        }
        if (withinRotationGrace(row, presentedHash, now)) {
            return Judgment.GRACE;
        }
        return Judgment.THEFT;
    }

    /**
     * Whether the {@code pass} row reached through a token's email is still the account that token was issued
     * to. The token records a {@link org.paulsens.trip.model.Person.Id}; the pass table is KEYED by a mutable
     * email, so an address that has since been re-assigned resolves to somebody ELSE's account -- and a token
     * must never restore an account it was not issued for. Callers treat a mismatch as a dead token, not as a
     * login, which also retires rows left stale by an email change.
     */
    public static boolean owns(final AuthToken token, final Creds creds) {
        return token != null && creds != null && token.getUserId() != null
                && token.getUserId().equals(creds.getUserId());
    }

    /**
     * Whether this stale validator is the client racing its own rotation rather than a theft: it must be
     * the exact validator the LAST rotation replaced, presented within {@value #ROTATION_GRACE_SECONDS}
     * seconds of that rotation. Rows from before the grace columns existed have neither field and never
     * match, which keeps the old strict behavior for them.
     */
    private static boolean withinRotationGrace(final AuthToken row, final String presentedHash, final long now) {
        return row.getPrevValidatorHash() != null && row.getRotatedAt() != null
                && Digests.matches(row.getPrevValidatorHash(), presentedHash)
                && now - row.getRotatedAt() <= ROTATION_GRACE_SECONDS;
    }
}
