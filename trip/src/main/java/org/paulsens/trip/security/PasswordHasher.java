package org.paulsens.trip.security;

import at.favre.lib.crypto.bcrypt.BCrypt;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Hashes and verifies user passwords with BCrypt over a {@link Pepper}.
 *
 * <h2>Stored format</h2>
 * A hashed password is stored as a small self-describing envelope:
 * <pre>  p&lt;pepperVersion&gt;:&lt;bcryptHash&gt;      e.g.  p1:$2a$10$Q9....</pre>
 * The {@code p&lt;version&gt;} prefix records which pepper produced the hash, so verification can pick the right key
 * and {@link #needsRehash} can spot hashes made under an older pepper. The prefix also makes "is this value already
 * hashed?" unambiguous: a plaintext password cannot match {@link #ENVELOPE}, which is what lets the same column hold
 * legacy plaintext during migration without ever being mistaken for a hash.
 *
 * <h2>Migration</h2>
 * {@link #verify} accepts a legacy plaintext value (anything not matching the envelope) and compares it directly, so
 * existing rows keep working. {@link #needsRehash} returns true for such a value -- and for any hash stamped with a
 * non-current pepper version -- so the caller can transparently re-hash on the next successful login. A one-off bulk
 * sweep does the same for accounts that never log in.
 *
 * <h2>A note on {@code String} vs {@code char[]}</h2>
 * This class takes passwords as {@code String} because every caller in this codebase (the {@code Creds} model, the
 * JSF beans, DynamoDB) already holds them as immutable {@code String}s that cannot be wiped. Accepting {@code char[]}
 * here would be security theater while the value sits in a dozen un-clearable strings up the stack.
 */
@Slf4j
public final class PasswordHasher {
    /** BCrypt work factor. 10 (~100ms/hash on current hardware) balances login latency against brute-force cost. */
    static final int COST = 10;

    // p<digits>:<bcrypt> -- BCrypt hashes start with $2a$, $2b$, or $2y$.
    private static final Pattern ENVELOPE = Pattern.compile("^p(\\d+):(\\$2[aby]\\$.+)$", Pattern.DOTALL);

    private static final class Holder {
        private static final PasswordHasher INSTANCE = new PasswordHasher(Pepper.resolve());
    }

    private final Pepper pepper;

    /**
     * Builds a hasher over an explicit pepper. Production wiring uses {@link #getInstance()} (which resolves the
     * pepper from configuration); this constructor is for callers that supply their own -- chiefly tests injecting a
     * fixed pepper so they never reach AWS.
     */
    public PasswordHasher(final Pepper pepper) {
        this.pepper = pepper;
    }

    public static PasswordHasher getInstance() {
        return Holder.INSTANCE;
    }

    /** Hashes a password into the stored envelope, stamped with the pepper's current version. */
    public String hash(final String password) {
        final int version = pepper.currentVersion();
        final byte[] bcrypt = BCrypt.withDefaults().hash(COST, pepper.preHash(password, version));
        return "p" + version + ":" + new String(bcrypt, StandardCharsets.UTF_8);
    }

    /** True if {@code stored} is one of our hash envelopes (as opposed to a legacy plaintext password). */
    public boolean isHashed(final String stored) {
        return stored != null && ENVELOPE.matcher(stored).matches();
    }

    /**
     * Verifies {@code password} against a stored value, which may be either a hash envelope or -- for not-yet-migrated
     * rows -- a legacy plaintext password.
     */
    public boolean verify(final String password, final String stored) {
        if (password == null || stored == null) {
            return false;
        }
        final var matcher = ENVELOPE.matcher(stored);
        if (!matcher.matches()) {
            // Legacy plaintext. Constant-time compare so this branch does not leak length/content via timing.
            return MessageDigest.isEqual(
                    password.getBytes(StandardCharsets.UTF_8), stored.getBytes(StandardCharsets.UTF_8));
        }
        final int version = Integer.parseInt(matcher.group(1));
        final String bcrypt = matcher.group(2);
        if (!pepper.supportsVersion(version)) {
            log.error("Cannot verify a password hashed with pepper version {} -- no key for it is configured.",
                    version);
            return false;
        }
        return BCrypt.verifyer()
                .verify(pepper.preHash(password, version), bcrypt.getBytes(StandardCharsets.UTF_8)).verified;
    }

    /**
     * True if a (previously verified) stored value should be re-hashed and saved: it is legacy plaintext, or it was
     * hashed under a pepper version older than the current one. Callers should re-hash only <em>after</em> a
     * successful {@link #verify}, so they still hold the plaintext.
     */
    public boolean needsRehash(final String stored) {
        if (stored == null) {
            return false;
        }
        final var matcher = ENVELOPE.matcher(stored);
        if (!matcher.matches()) {
            return true;
        }
        return Integer.parseInt(matcher.group(1)) != pepper.currentVersion();
    }
}
