package org.paulsens.trip.dynamo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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
 * nothing. Pass {@code --apply} to perform the writes. It always talks to the real DynamoDB in {@code us-west-2}
 * (never fake/local persistence), and it hashes with the same pepper the application uses, so the pepper environment
 * ({@code TRIP_PASSWORD_PEPPER_SECRET}, etc.) must be set exactly as it is for the webapp or the sweep would write
 * hashes the running app cannot verify.</p>
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
        final boolean apply = args.length > 0 && "--apply".equals(args[0]);
        if (!apply) {
            log.info("DRY RUN (pass --apply to write). Scanning '{}' for plaintext passwords...",
                    CredentialsDAO.PASS_TABLE);
        } else {
            log.warn("APPLY mode: plaintext passwords in '{}' WILL be hashed in place.", CredentialsDAO.PASS_TABLE);
        }
        final PasswordMigration migration =
                new PasswordMigration(new DynamoPersistence(), PasswordHasher.getInstance());
        final Result result = migration.run(apply).join();
        log.info("Done. Scanned={}, alreadyHashed={}, plaintext={}, upgraded={}, failed={}.",
                result.scanned, result.alreadyHashed, result.plaintext, result.upgraded, result.failed);
        if (result.failed > 0) {
            System.exit(1);
        }
    }

    /** Runs the sweep. When {@code apply} is false, counts what would change but performs no writes. */
    CompletableFuture<Result> run(final boolean apply) {
        return persistence.scanAll(b -> b.tableName(CredentialsDAO.PASS_TABLE))
                .thenCompose(rows -> upgradeAll(rows, apply));
    }

    private CompletableFuture<Result> upgradeAll(final List<Map<String, AttributeValue>> rows, final boolean apply) {
        final Result result = new Result();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (final Map<String, AttributeValue> row : rows) {
            result.scanned++;
            final AttributeValue pw = row.get(CredentialsDAO.PW);
            final String email = row.containsKey(CredentialsDAO.EMAIL) ? row.get(CredentialsDAO.EMAIL).s() : "?";
            if (pw == null || pw.s() == null || pw.s().isBlank()) {
                log.warn("Row for '{}' has no password attribute; skipping.", email);
                continue;
            }
            if (hasher.isHashed(pw.s())) {
                result.alreadyHashed++;
                continue;
            }
            result.plaintext++;
            if (!apply) {
                log.info("[dry run] would hash password for '{}'.", email);
                continue;
            }
            final Map<String, AttributeValue> updated = new HashMap<>(row);
            updated.put(CredentialsDAO.PW, AttributeValue.builder().s(hasher.hash(pw.s())).build());
            chain = chain.thenCompose(ignored -> persistence.putItem(b -> b.tableName(CredentialsDAO.PASS_TABLE)
                            .item(updated))
                    .handle((resp, ex) -> {
                        if (ex != null || !resp.sdkHttpResponse().isSuccessful()) {
                            result.failed++;
                            log.error("Failed to upgrade password for '{}'.", email, ex);
                        } else {
                            result.upgraded++;
                            log.info("Upgraded password for '{}'.", email);
                        }
                        return null;
                    }));
        }
        return chain.thenApply(ignored -> result);
    }

    /** Tallies from a sweep. */
    static final class Result {
        int scanned;
        int alreadyHashed;
        int plaintext;
        int upgraded;
        int failed;
    }
}
