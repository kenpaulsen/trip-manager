package org.paulsens.trip.security;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class ProcessorSecretsTest {

    /** A fake backing store standing in for Secrets Manager. */
    private static final class FakeStore implements ProcessorSecrets.Store {
        private final AtomicReference<String> json = new AtomicReference<>("{}");
        private final AtomicInteger reads = new AtomicInteger();
        private boolean failWrites;
        private boolean failReads;

        @Override
        public String read() {
            reads.incrementAndGet();
            if (failReads) {
                throw new IllegalStateException("read refused");
            }
            return json.get();
        }

        @Override
        public void write(final String value) {
            if (failWrites) {
                throw new IllegalStateException("write refused");
            }
            json.set(value);
        }
    }

    @Test
    public void inMemoryModePutsAndGetsWithMergeAndBlankDelete() {
        final ProcessorSecrets secrets = new ProcessorSecrets(null);
        assertTrue(secrets.get("cfg-1").isEmpty(), "Missing config answers an empty map, never null");

        assertTrue(secrets.put("cfg-1", Map.of("clientSecret", " s3cret ")));
        assertEquals(secrets.get("cfg-1").get("clientSecret"), "s3cret", "Values are trimmed");
        assertTrue(secrets.hasSecret("cfg-1", "clientSecret"));
        assertFalse(secrets.hasSecret("cfg-1", "clientSecretSandbox"));

        assertTrue(secrets.put("cfg-1", Map.of("clientSecretSandbox", "sb")));
        assertEquals(secrets.get("cfg-1").size(), 2, "Puts merge, they do not replace");

        assertTrue(secrets.put("cfg-1", java.util.Collections.singletonMap("clientSecret", "")));
        assertFalse(secrets.hasSecret("cfg-1", "clientSecret"), "Blank deletes the field");
        assertTrue(secrets.hasSecret("cfg-1", "clientSecretSandbox"));
    }

    @Test
    public void storeBackedModeWritesThroughAndRefreshes() {
        final FakeStore store = new FakeStore();
        final ProcessorSecrets secrets = new ProcessorSecrets(store);
        assertTrue(secrets.put("cfg-a", Map.of("clientSecret", "x")));
        assertTrue(store.json.get().contains("cfg-a"), "The write reached the backing store");

        // A second instance sharing the store sees the value (the fresh-task-after-rotation case).
        final ProcessorSecrets second = new ProcessorSecrets(store);
        assertEquals(second.get("cfg-a").get("clientSecret"), "x");
    }

    @Test
    public void aRefusedWriteReportsFalseAndKeepsTheOldValues() {
        final FakeStore store = new FakeStore();
        final ProcessorSecrets secrets = new ProcessorSecrets(store);
        assertTrue(secrets.put("cfg-b", Map.of("clientSecret", "before")));

        store.failWrites = true;
        assertFalse(secrets.put("cfg-b", Map.of("clientSecret", "after")),
                "A refused write must be surfaced, never silently swallowed");
        assertEquals(secrets.get("cfg-b").get("clientSecret"), "before");
    }

    @Test
    public void aFailedRefreshServesTheCachedValues() {
        final FakeStore store = new FakeStore();
        final ProcessorSecrets secrets = new ProcessorSecrets(store);
        assertTrue(secrets.put("cfg-c", Map.of("clientSecret", "keep")));

        store.failReads = true;
        // A transient store blip must not fail a payment yesterday's credentials would have served.
        assertEquals(secrets.get("cfg-c").get("clientSecret"), "keep");
    }

    @Test
    public void theResolutionLadderPicksTheRightStore() {
        assertTrue(ProcessorSecrets.create(true, "ignored").put("x", Map.of("clientSecret", "v")),
                "Local mode is pure in-memory");
        assertTrue(ProcessorSecrets.create(false, null).put("x", Map.of("clientSecret", "v")),
                "Unconfigured production logs loudly but still works in-memory");
        org.testng.Assert.assertNotNull(ProcessorSecrets.create(false, "trip/payment-processors"));
        org.testng.Assert.assertNotNull(ProcessorSecrets.getInstance(), "Tests run local; singleton resolves");
    }

    @Test
    public void aGarbageSecretBodyIsContained() {
        final FakeStore store = new FakeStore();
        store.json.set("not json at all {");
        final ProcessorSecrets secrets = new ProcessorSecrets(store);
        assertTrue(secrets.get("anything").isEmpty(),
                "A malformed secret serves empty (and logs) rather than tearing payments down");
        // ... but it is never WRITTEN from: the write replaces the whole secret, and what could not be parsed
        // may hold every organization's credentials.
        assertFalse(secrets.put("cfg-new", Map.of("clientSecret", "x")));
        assertEquals(store.json.get(), "not json at all {", "left for a person to repair");
    }

    /**
     * A write replaces the WHOLE secret, so it must merge into what the store holds now. The cache can be
     * empty for reasons that have nothing to do with the store (the first read after a deploy failed): a write
     * built from it erased every other organization's payment credentials.
     */
    @Test
    public void aWriteNeverStartsFromACacheThatWasNotLoaded() {
        final FakeStore store = new FakeStore();
        store.json.set("{\"org-a\":{\"clientSecret\":\"a\"},\"org-b\":{\"clientSecret\":\"b\"}}");
        store.failReads = true;
        final ProcessorSecrets secrets = new ProcessorSecrets(store);
        assertTrue(secrets.get("org-a").isEmpty(), "the first read failed, so the cache is empty");

        assertFalse(secrets.put("org-c", Map.of("clientSecret", "c")), "refused while the store is unreadable");
        assertTrue(store.json.get().contains("org-a") && store.json.get().contains("org-b"), store.json.get());
        assertFalse(store.json.get().contains("org-c"));

        // Readable again: the write merges into the stored entries, whatever the cache held.
        store.failReads = false;
        assertTrue(secrets.put("org-c", Map.of("clientSecret", "c")));
        assertTrue(store.json.get().contains("org-a") && store.json.get().contains("org-b")
                && store.json.get().contains("org-c"), store.json.get());
        assertEquals(secrets.get("org-a").get("clientSecret"), "a", "and the cache now holds what was stored");
        assertEquals(secrets.get("org-c").get("clientSecret"), "c");
    }

    /** Another task's write is not undone by this task's stale cache. */
    @Test
    public void aWriteMergesIntoAnotherWritersEntries() {
        final FakeStore store = new FakeStore();
        final ProcessorSecrets first = new ProcessorSecrets(store);
        final ProcessorSecrets second = new ProcessorSecrets(store);
        assertTrue(first.put("org-a", Map.of("clientSecret", "a")));
        assertTrue(second.get("org-a").containsKey("clientSecret"));
        assertTrue(first.put("org-b", Map.of("clientSecret", "b")), "second's cache does not know org-b yet");
        assertTrue(second.put("org-c", Map.of("clientSecret", "c")));
        assertTrue(store.json.get().contains("org-b"), "org-b survived second's write: " + store.json.get());
    }

    @Test
    public void whatAReaderIsHandedCannotBeChanged() {
        final ProcessorSecrets secrets = new ProcessorSecrets(null);
        assertTrue(secrets.put("cfg", Map.of("clientSecret", "s")));
        org.testng.Assert.assertThrows(UnsupportedOperationException.class,
                () -> secrets.get("cfg").put("clientSecret", "changed"));
    }

    @Test
    public void theSecretsManagerStoreRoundTripsThroughTheClient() {
        final software.amazon.awssdk.services.secretsmanager.SecretsManagerClient client =
                org.mockito.Mockito.mock(
                        software.amazon.awssdk.services.secretsmanager.SecretsManagerClient.class);
        org.mockito.Mockito.when(client.getSecretValue(org.mockito.ArgumentMatchers.any(
                        software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest.class)))
                .thenReturn(software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse
                        .builder().secretString("{\"cfg\":{\"clientSecret\":\"s\"}}").build());
        final ProcessorSecrets.SecretsManagerStore store =
                new ProcessorSecrets.SecretsManagerStore("trip/payment-processors", () -> client);

        assertEquals(store.read(), "{\"cfg\":{\"clientSecret\":\"s\"}}");
        store.write("{}");
        org.mockito.Mockito.verify(client).putSecretValue(org.mockito.ArgumentMatchers.any(
                software.amazon.awssdk.services.secretsmanager.model.PutSecretValueRequest.class));

        final ProcessorSecrets viaStore = new ProcessorSecrets(store);
        assertEquals(viaStore.get("cfg").get("clientSecret"), "s",
                "The store-backed read feeds the cache");
    }

    @Test
    public void theRealClientPathConstructsOffline() {
        // Building the AWS client resolves nothing until a call is made, so this is safe without AWS --
        // and it pins the region ladder without touching the network.
        final ProcessorSecrets.SecretsManagerStore store =
                new ProcessorSecrets.SecretsManagerStore("trip/payment-processors", null);
        try (software.amazon.awssdk.services.secretsmanager.SecretsManagerClient client = store.client()) {
            org.testng.Assert.assertNotNull(client);
        }
        org.testng.Assert.assertNotNull(ProcessorSecrets.SecretsManagerStore.resolveRegion());
    }

    @Test
    public void removingTheLastFieldRemovesTheConfigEntry() {
        final FakeStore store = new FakeStore();
        final ProcessorSecrets secrets = new ProcessorSecrets(store);
        assertTrue(secrets.put("cfg-d", Map.of("clientSecret", "x")));
        assertTrue(secrets.put("cfg-d", java.util.Collections.singletonMap("clientSecret", "")));
        assertFalse(store.json.get().contains("cfg-d"), "An emptied entry is dropped from the secret JSON");
    }
}
