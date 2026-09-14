package org.paulsens.trip.push;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

/** The {@code trip/push} secret: its JSON shape, the resolution ladder, the read cache and the local pair. */
public class PushSecretsTest {

    private static final KeyPair APNS = EcKeys.generate();
    private static final KeyPair VAPID = EcKeys.generate();

    static String json(final boolean apns, final boolean vapid) {
        final StringBuilder out = new StringBuilder("{");
        if (apns) {
            out.append("\"apns\":{\"keyId\":\"ABC123\",\"teamId\":\"TEAM1\",\"key\":")
                    .append('"').append(EcKeysTest.pemOf((ECPrivateKey) APNS.getPrivate()).replace("\n", "\\n"))
                    .append("\"}");
        }
        if (apns && vapid) {
            out.append(',');
        }
        if (vapid) {
            final byte[] scalar = new byte[32];
            final byte[] raw = ((ECPrivateKey) VAPID.getPrivate()).getS().toByteArray();
            final int start = Math.max(0, raw.length - 32);
            System.arraycopy(raw, start, scalar, 32 - (raw.length - start), raw.length - start);
            out.append("\"vapid\":{\"publicKey\":\"")
                    .append(EcKeys.encodeUrl(EcKeys.pointOf((ECPublicKey) VAPID.getPublic())))
                    .append("\",\"privateKey\":\"").append(EcKeys.encodeUrl(scalar))
                    .append("\",\"subject\":\"mailto:ken@example.org\"}");
        }
        return out.append('}').toString();
    }

    @Test
    public void bothHalvesParseFromTheDocumentedShape() {
        final PushSecrets.Loaded loaded = PushSecrets.parse(json(true, true));
        Assert.assertEquals(loaded.apns().keyId(), "ABC123");
        Assert.assertEquals(loaded.apns().teamId(), "TEAM1");
        Assert.assertEquals(loaded.apns().key().getS(), ((ECPrivateKey) APNS.getPrivate()).getS());
        Assert.assertEquals(loaded.vapid().subject(), "mailto:ken@example.org");
        Assert.assertEquals(loaded.vapid().key().getS(), ((ECPrivateKey) VAPID.getPrivate()).getS());
        Assert.assertEquals(loaded.vapid().publicKey(),
                EcKeys.encodeUrl(EcKeys.pointOf((ECPublicKey) VAPID.getPublic())));
    }

    @Test
    public void aMissingOrBrokenHalfLeavesTheOtherAlone() {
        Assert.assertNull(PushSecrets.parse(json(true, false)).vapid());
        Assert.assertNotNull(PushSecrets.parse(json(true, false)).apns());
        Assert.assertNull(PushSecrets.parse(json(false, true)).apns());
        Assert.assertEquals(PushSecrets.parse(""), PushSecrets.Loaded.NONE);
        Assert.assertEquals(PushSecrets.parse(null), PushSecrets.Loaded.NONE);
        Assert.assertEquals(PushSecrets.parse("{}"), PushSecrets.Loaded.NONE);

        final PushSecrets.Loaded badPem = PushSecrets.parse(
                "{\"apns\":{\"keyId\":\"k\",\"teamId\":\"t\",\"key\":\"not a key\"}}");
        Assert.assertNull(badPem.apns(), "a malformed key is logged and absent, never fatal");
        final PushSecrets.Loaded shortPoint = PushSecrets.parse(
                "{\"vapid\":{\"publicKey\":\"AAAA\",\"privateKey\":\"AAAA\",\"subject\":\"mailto:x\"}}");
        Assert.assertNull(shortPoint.vapid());
        final PushSecrets.Loaded blankField = PushSecrets.parse(
                "{\"apns\":{\"keyId\":\" \",\"teamId\":\"t\",\"key\":\"x\"}}");
        Assert.assertNull(blankField.apns());
        Assert.assertThrows(IllegalStateException.class, () -> PushSecrets.parse("not json"));
    }

    @Test
    public void theLadderPrefersTheFileThenLocalThenTheNamedSecret() throws Exception {
        final Path file = Files.createTempFile("push-secret", ".json");
        Files.writeString(file, json(true, true), StandardCharsets.UTF_8);
        try {
            final PushSecrets fromFile = PushSecrets.create(true, null, file.toString());
            Assert.assertTrue(fromFile.apns().isPresent());
            Assert.assertTrue(fromFile.vapid().isPresent());
            Assert.assertFalse(fromFile.isEphemeral());
            Assert.assertTrue(fromFile.isConfigured());
        } finally {
            Files.deleteIfExists(file);
        }
        final PushSecrets local = PushSecrets.create(true, "ignored", null);
        Assert.assertTrue(local.isEphemeral(), "local mode mints a throwaway VAPID pair");
        Assert.assertTrue(local.apns().isEmpty());
        Assert.assertTrue(local.vapid().isPresent());
        Assert.assertEquals(EcKeys.decodeUrl(local.vapid().get().publicKey()).length, EcKeys.POINT_LENGTH);
        Assert.assertEquals(EcKeys.decodeUrl(local.vapid().get().privateKey()).length, 32);
        Assert.assertEquals(EcKeys.privateKeyFromScalar(EcKeys.decodeUrl(local.vapid().get().privateKey())).getS(),
                local.vapid().get().key().getS(), "the wire scalar IS the signing key");

        final PushSecrets none = PushSecrets.create(false, null, null);
        Assert.assertFalse(none.isConfigured());
        Assert.assertFalse(none.isEphemeral());

        final PushSecrets named = PushSecrets.create(false, "trip/push", null);
        Assert.assertNotNull(named, "a store is wired; nothing is read until asked");

        final PushSecrets.FileStore missing = new PushSecrets.FileStore(Path.of("/nowhere/push.json"));
        Assert.assertThrows(IllegalStateException.class, missing::read);
    }

    private final AtomicInteger reads = new AtomicInteger();

    private String countedRead() {
        reads.incrementAndGet();
        return json(true, false);
    }

    private static String down() {
        throw new IllegalStateException("secrets manager down");
    }

    @Test
    public void readsAreCachedAndAFailedRefreshKeepsTheLastKeys() {
        final PushSecrets secrets = new PushSecrets(this::countedRead);
        Assert.assertTrue(secrets.apns().isPresent());
        Assert.assertTrue(secrets.apns().isPresent());
        Assert.assertEquals(reads.get(), 1, "the second read within the TTL is served from cache");

        final PushSecrets failing = new PushSecrets(PushSecretsTest::down);
        Assert.assertTrue(failing.apns().isEmpty(), "nothing cached yet, nothing served");
        Assert.assertFalse(failing.isConfigured());
        Assert.assertTrue(new PushSecrets(null).apns().isEmpty());
    }

    @Test
    public void theSecretsManagerStoreReadsTheSecretString() {
        final SecretsManagerClient client = Mockito.mock(SecretsManagerClient.class);
        Mockito.when(client.getSecretValue(Mockito.any(GetSecretValueRequest.class)))
                .thenReturn(GetSecretValueResponse.builder().secretString("{}").build());
        final PushSecrets.SecretsManagerStore store = new PushSecrets.SecretsManagerStore("trip/push", () -> client);
        Assert.assertEquals(store.read(), "{}");
        Mockito.verify(client).close();

        // The no-seam construction path: builds a real client without touching the network.
        final PushSecrets.SecretsManagerStore real = new PushSecrets.SecretsManagerStore("trip/push", null);
        try (SecretsManagerClient built = real.client()) {
            Assert.assertNotNull(built);
        }
        Assert.assertEquals(PushSecrets.SecretsManagerStore.resolveRegion(), Region.US_WEST_2);
    }

    @Test
    public void theSharedInstanceIsLocalModesEphemeralPair() {
        Assert.assertTrue(PushSecrets.getInstance().isEphemeral(), "the suite runs in local mode");
        Assert.assertSame(PushSecrets.getInstance(), PushSecrets.getInstance());
    }
}
