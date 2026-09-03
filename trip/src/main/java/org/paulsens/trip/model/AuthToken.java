package org.paulsens.trip.model;

import java.io.Serial;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One presentable credential this application handed out: a remember-me browser cookie, an API refresh
 * token, or an API access token -- the server half of an opaque {@code selector:validator} pair.
 *
 * <p>The client holds {@code selector:validator}; this row holds the selector and only the SHA-256 of the
 * validator -- a copy of this table (or of the validation cache) cannot be turned into working credentials.
 * For the rotating kinds ({@code REMEMBER}, {@code REFRESH}) the validator hash rotates on every use (same
 * selector), which is what makes a stolen-then-replayed credential detectable: a stale validator presented
 * against a live selector is loud evidence someone else has used it.
 *
 * <p>The one innocent way a stale validator arrives is the client racing its own rotation: two requests carry
 * the same credential, the first rotates, the second presents the pre-rotation copy milliseconds later.
 * {@code prevValidatorHash}/{@code rotatedAt} keep the last rotation's evidence so
 * {@code SelectorTokens.judge} can tell that race from theft. {@code ACCESS} tokens never rotate, so both
 * stay null for them. Design doc: {@code docs/api-tokens.md}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public final class AuthToken implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    /** What the credential is for; absent on rows written before this column existed, meaning REMEMBER. */
    public enum Kind { REMEMBER, REFRESH, ACCESS }

    /**
     * What a bearer token may do, capped below what its holder could: a member-scoped token held by an
     * administrator behaves as that person without the admin role. Only REFRESH/ACCESS rows carry one.
     */
    public enum Scope { MEMBER, ADMIN }

    /** Client/database join key; random, public-ish (it identifies, never authenticates). */
    private String selector;
    /** Base64 SHA-256 of the credential's validator half; the only secret-derived value stored. */
    private String validatorHash;
    /** The hash the last rotation replaced, honored briefly after {@code rotatedAt}; null until first use. */
    private String prevValidatorHash;
    /** Epoch seconds of the last rotation; bounds how long {@code prevValidatorHash} stays honored. */
    private Long rotatedAt;
    private Person.Id userId;
    /** Lowercased. Stored so a token-authenticated actor is never half-known: email AND id, always. */
    private String email;
    /** Epoch seconds. Expiry is absolute from creation -- rotation refreshes the validator, never the clock. */
    private Long created;
    private Long lastUsed;
    /** Epoch seconds; also the DynamoDB TTL attribute, so expired rows delete themselves. */
    private Long expires;
    /** Normalized by the DAO: a row read back always has a kind, legacy rows reading as {@link Kind#REMEMBER}. */
    private Kind kind;
    /** The role stamped at issuance/refresh (REFRESH/ACCESS only); the refresh call is the freshness checkpoint. */
    private String role;
    /** REFRESH/ACCESS only; see {@link Scope}. */
    private Scope scope;
    /** Client-supplied device name (REFRESH only), for the signed-in-devices UI. Display data, no authority. */
    private String label;
    /** For ACCESS rows: the refresh token that minted it, so revoking the parent cascades to its children. */
    private String parentSelector;
}
