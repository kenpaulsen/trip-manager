package org.paulsens.trip.dynamo;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.paulsens.trip.security.PasswordHasher;
import org.paulsens.trip.security.Pepper;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class PasswordMigrationTest {
    private static final PasswordHasher HASHER =
            new PasswordHasher(Pepper.of(1, "migration-test-pepper-key-0000000".getBytes(StandardCharsets.UTF_8)));

    @Test
    public void dryRunReportsPlaintextButWritesNothing() {
        final List<Map<String, AttributeValue>> puts = new ArrayList<>();
        final PasswordMigration migration = new PasswordMigration(
                persistenceOver(List.of(row("a@x.com", "plain1"), row("b@x.com", HASHER.hash("already")),
                        row("c@x.com", "plain2")), puts), HASHER);

        final PasswordMigration.Result result = migration.run(false).join();

        assertEquals(result.scanned.get(), 3);
        assertEquals(result.alreadyHashed.get(), 1);
        assertEquals(result.plaintext.get(), 2);
        assertEquals(result.upgraded.get(), 0, "a dry run must not upgrade");
        assertTrue(puts.isEmpty(), "a dry run must write nothing");
    }

    @Test
    public void applyHashesOnlyThePlaintextRows() {
        final List<Map<String, AttributeValue>> puts = new ArrayList<>();
        final PasswordMigration migration = new PasswordMigration(
                persistenceOver(List.of(row("a@x.com", "plain1"), row("b@x.com", HASHER.hash("already"))), puts),
                HASHER);

        final PasswordMigration.Result result = migration.run(true).join();

        assertEquals(result.plaintext.get(), 1);
        assertEquals(result.upgraded.get(), 1);
        assertEquals(result.failed.get(), 0);
        assertEquals(puts.size(), 1, "only the plaintext row should be rewritten");
        final Map<String, AttributeValue> written = puts.get(0);
        assertEquals(written.get(CredentialsDAO.EMAIL).s(), "a@x.com", "the row's other attributes must be preserved");
        assertTrue(HASHER.isHashed(written.get(CredentialsDAO.PW).s()));
        assertTrue(HASHER.verify("plain1", written.get(CredentialsDAO.PW).s()), "no password should actually change");
    }

    @Test
    public void parallelApplyHashesEveryPlaintextRowExactlyOnce() {
        final List<Map<String, AttributeValue>> puts = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<Map<String, AttributeValue>> rows = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            rows.add(i % 5 == 0 ? row("h" + i + "@x.com", HASHER.hash("was" + i)) : row("p" + i + "@x.com", "pw" + i));
        }
        final PasswordMigration migration = new PasswordMigration(persistenceOver(rows, puts), HASHER);

        final PasswordMigration.Result result = migration.run(true, 8).join();

        assertEquals(result.scanned.get(), 50);
        assertEquals(result.alreadyHashed.get(), 10, "every 5th row was already hashed");
        assertEquals(result.plaintext.get(), 40);
        assertEquals(result.upgraded.get(), 40);
        assertEquals(result.failed.get(), 0);
        assertEquals(puts.size(), 40, "each plaintext row must be written exactly once, no duplicates or drops");
        for (final Map<String, AttributeValue> written : puts) {
            assertTrue(HASHER.isHashed(written.get(CredentialsDAO.PW).s()), "each written row must be hashed");
        }
    }

    private static Map<String, AttributeValue> row(final String email, final String pass) {
        return Map.of(
                CredentialsDAO.EMAIL, AttributeValue.builder().s(email).build(),
                CredentialsDAO.USER_ID, AttributeValue.builder().s("id-" + email).build(),
                CredentialsDAO.PRIV, AttributeValue.builder().s("user").build(),
                CredentialsDAO.PW, AttributeValue.builder().s(pass).build());
    }

    private static Persistence persistenceOver(final List<Map<String, AttributeValue>> rows,
            final List<Map<String, AttributeValue>> puts) {
        return new Persistence() {
            @Override
            public CompletableFuture<List<Map<String, AttributeValue>>> scanAll(
                    final Consumer<ScanRequest.Builder> scanRequest) {
                return CompletableFuture.completedFuture(rows);
            }
            @Override
            public CompletableFuture<PutItemResponse> putItem(final Consumer<PutItemRequest.Builder> req) {
                final PutItemRequest.Builder b = PutItemRequest.builder();
                req.accept(b);
                puts.add(b.build().item());
                return Persistence.super.putItem(req);
            }
        };
    }
}
