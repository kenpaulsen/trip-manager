package org.paulsens.trip.dynamo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.security.PasswordHasher;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * One-off sweep that hashes any remaining plaintext passwords in the {@code pass} table.
 *
 * <p>Successful logins upgrade themselves ({@link CredentialsDAO} re-hashes on the way through), so this tool exists
 * only for accounts that may never log in again. It scans every row, and for each whose {@code pass} is not already a
 * {@link PasswordHasher} envelope, computes the hash of the plaintext and writes the row back unchanged except for
 * that one attribute. Because the plaintext is known, no password actually changes and no reset email is needed.</p>
 *
 * <p><b>Safe by default.</b> With no arguments it is a dry run: it reports how many rows would change and writes
 * nothing. Pass {@code --apply} to perform the writes, and {@code --parallel N} to hash and write up to N rows at
 * once ({@code N} defaults to 1). It talks to the real DynamoDB (region from {@code TRIP_DYNAMO_REGION}, default
 * {@code us-west-2}; never fake/local persistence), and it hashes with the same pepper the application uses, so the
 * pepper environment ({@code TRIP_PASSWORD_PEPPER_SECRET}, etc.) must be set exactly as it is for the webapp or the
 * sweep would write hashes the running app cannot verify.</p>
 *
 * <p>The pepper secret is read <b>exactly once</b> (the shared {@link PasswordHasher} memoizes it), and every
 * parallel worker reuses that one resolution, so a large sweep still makes a single Secrets Manager call.</p>
 */
@Slf4j
public final class PasswordMigration {
    private final Persistence persistence;
    private final PasswordHasher hasher;

    PasswordMigration(final Persistence persistence, final PasswordHasher hasher) {
        this.persistence = persistence;
        this.hasher = hasher;
    }

    public static void main(final String[] args) {
        final boolean apply = hasFlag(args, "--apply");
        final int parallelism = Math.max(1, intArg(args, "--parallel", 1));
        if (!apply) {
            log.info("DRY RUN (pass --apply to write). Scanning '{}' for plaintext passwords...",
                    CredentialsDAO.PASS_TABLE);
        } else {
            log.warn("APPLY mode: plaintext passwords in '{}' WILL be hashed in place ({} at a time).",
                    CredentialsDAO.PASS_TABLE, parallelism);
        }
        // getInstance() resolves (and reads) the pepper once; the sweep below reuses this single hasher.
        final PasswordMigration migration =
                new PasswordMigration(new DynamoPersistence(), PasswordHasher.getInstance());
        final Result result = migration.run(apply, parallelism);
        log.info("Done. Scanned={}, alreadyHashed={}, plaintext={}, upgraded={}, failed={}.",
                result.scanned.get(), result.alreadyHashed.get(), result.plaintext.get(),
                result.upgraded.get(), result.failed.get());
        if (result.failed.get() > 0) {
            System.exit(1);
        }
    }

    private static boolean hasFlag(final String[] args, final String flag) {
        for (final String arg : args) {
            if (flag.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static int intArg(final String[] args, final String name, final int fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                return Integer.parseInt(args[i + 1].trim());
            }
        }
        return fallback;
    }

    /** Runs the sweep sequentially. When {@code apply} is false, counts what would change but performs no writes. */
    Result run(final boolean apply) {
        return run(apply, 1);
    }

    /**
     * Runs the sweep, blocking until it finishes. When {@code apply} is false, counts what would change but performs
     * no writes; otherwise hashes and rewrites plaintext rows up to {@code parallelism} at a time.
     */
    Result run(final boolean apply, final int parallelism) {
        final List<Map<String, AttributeValue>> rows =
                persistence.scanAll(b -> b.tableName(CredentialsDAO.PASS_TABLE));
        return upgradeAll(rows, apply, Math.max(1, parallelism));
    }

    private Result upgradeAll(final List<Map<String, AttributeValue>> rows, final boolean apply,
            final int parallelism) {
        final Result result = new Result();
        // One virtual thread per row, gated by a semaphore rather than a pool: the permit caps how many BCrypt
        // hashes (CPU-bound) run at once, and the scope guarantees every write finished before run() returns.
        final Semaphore permits = new Semaphore(parallelism);
        try (var scope = StructuredTaskScope.open()) {
            for (final Map<String, AttributeValue> row : rows) {
                result.scanned.incrementAndGet();
                final AttributeValue pw = row.get(CredentialsDAO.PW);
                final String email = row.containsKey(CredentialsDAO.EMAIL) ? row.get(CredentialsDAO.EMAIL).s() : "?";
                if (pw == null || pw.s() == null || pw.s().isBlank()) {
                    log.warn("Row for '{}' has no password attribute; skipping.", email);
                    continue;
                }
                if (hasher.isHashed(pw.s())) {
                    result.alreadyHashed.incrementAndGet();
                    continue;
                }
                result.plaintext.incrementAndGet();
                if (!apply) {
                    log.info("[dry run] would hash password for '{}'.", email);
                    continue;
                }
                scope.fork(() -> upgradeOne(row, pw.s(), email, result, permits));
            }
            scope.join();
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while migrating passwords", ex);
        }
        return result;
    }

    /**
     * Hashes and rewrites ONE row, counting the outcome. Never throws: one bad row must be a {@code failed}
     * tally, not a cancelled sweep -- which is also what keeps the fail-fast default of the scope inert.
     */
    private Void upgradeOne(final Map<String, AttributeValue> row, final String plaintext,
            final String email, final Result result, final Semaphore permits) {
        try {
            permits.acquire();
            try {
                final Map<String, AttributeValue> updated = new HashMap<>(row);
                updated.put(CredentialsDAO.PW, AttributeValue.builder().s(hasher.hash(plaintext)).build());
                final var resp = persistence.putItem(b -> b.tableName(CredentialsDAO.PASS_TABLE).item(updated));
                if (resp == null || !resp.sdkHttpResponse().isSuccessful()) {
                    result.failed.incrementAndGet();
                    log.error("Failed to upgrade password for '{}'.", email);
                } else {
                    result.upgraded.incrementAndGet();
                    log.info("Upgraded password for '{}'.", email);
                }
            } finally {
                permits.release();
            }
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            result.failed.incrementAndGet();
            log.error("Interrupted while upgrading password for '{}'.", email, ex);
        } catch (final RuntimeException ex) {
            result.failed.incrementAndGet();
            log.error("Failed to upgrade password for '{}'.", email, ex);
        }
        return null;
    }

    /** Thread-safe tallies from a sweep (updated from parallel workers). */
    static final class Result {
        final AtomicInteger scanned = new AtomicInteger();
        final AtomicInteger alreadyHashed = new AtomicInteger();
        final AtomicInteger plaintext = new AtomicInteger();
        final AtomicInteger upgraded = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
    }
}
