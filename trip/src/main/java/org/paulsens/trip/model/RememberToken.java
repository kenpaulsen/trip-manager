package org.paulsens.trip.model;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * One browser's remember-me registration: the server half of the {@code trip_remember} cookie.
 *
 * <p>The cookie holds {@code selector:validator}; this row holds the selector and only the SHA-256 of the
 * validator -- a copy of this table cannot be turned into working cookies. The validator hash rotates on every
 * use (same selector), which is what makes a stolen-then-replayed cookie detectable: a stale validator
 * presented against a live selector is loud evidence someone else has used the cookie.
 *
 * <p>The one innocent way a stale validator arrives is the browser racing its own rotation: two session-less
 * requests (a page plus its long-poll, say) carry the same cookie, the first rotates, the second presents the
 * pre-rotation copy milliseconds later. {@code prevValidatorHash}/{@code rotatedAt} keep the last rotation's
 * evidence so the service can tell that race from theft.
 */
@Data
@AllArgsConstructor
public final class RememberToken implements Serializable {
    /** Cookie/database join key; random, public-ish (it identifies, never authenticates). */
    private String selector;
    /** Base64 SHA-256 of the cookie's validator half; the only secret-derived value stored. */
    private String validatorHash;
    /** The hash the last rotation replaced, honored briefly after {@code rotatedAt}; null until first use. */
    private String prevValidatorHash;
    /** Epoch seconds of the last rotation; bounds how long {@code prevValidatorHash} stays honored. */
    private Long rotatedAt;
    private Person.Id userId;
    /** Lowercased; establishes {@code loginEmail} on a restored session. */
    private String email;
    /** Epoch seconds. Expiry is absolute from creation -- rotation refreshes the validator, never the clock. */
    private Long created;
    private Long lastUsed;
    /** Epoch seconds; also the DynamoDB TTL attribute, so expired rows delete themselves. */
    private Long expires;
}
