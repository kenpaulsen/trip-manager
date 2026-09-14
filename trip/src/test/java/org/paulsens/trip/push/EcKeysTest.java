package org.paulsens.trip.push;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;
import org.testng.Assert;
import org.testng.annotations.Test;

/** The P-256 plumbing: PEM, raw scalar and uncompressed point all round-trip through the JDK. */
public class EcKeysTest {

    /** Apple's {@code .p8} is a PKCS#8 PEM; the JDK's encoded form is exactly that DER. */
    static String pemOf(final ECPrivateKey key) {
        return "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(key.getEncoded()) + "\n-----END PRIVATE KEY-----\n";
    }

    @Test
    public void aPemRoundTripsToTheSameKey() throws Exception {
        final KeyPair pair = EcKeys.generate();
        final ECPrivateKey parsed = EcKeys.privateKeyFromPem(pemOf((ECPrivateKey) pair.getPrivate()));
        Assert.assertEquals(parsed.getS(), ((ECPrivateKey) pair.getPrivate()).getS());
    }

    @Test
    public void aRawScalarAndAnUncompressedPointRoundTrip() throws Exception {
        final KeyPair pair = EcKeys.generate();
        final ECPublicKey pub = (ECPublicKey) pair.getPublic();
        final byte[] point = EcKeys.pointOf(pub);
        Assert.assertEquals(point.length, EcKeys.POINT_LENGTH);
        Assert.assertEquals(point[0], 0x04);
        Assert.assertEquals(EcKeys.publicKeyFromPoint(point).getW(), pub.getW());

        final byte[] scalar = new byte[32];
        final byte[] raw = ((ECPrivateKey) pair.getPrivate()).getS().toByteArray();
        final int start = Math.max(0, raw.length - 32);
        System.arraycopy(raw, start, scalar, 32 - (raw.length - start), raw.length - start);
        Assert.assertEquals(EcKeys.privateKeyFromScalar(scalar).getS(), ((ECPrivateKey) pair.getPrivate()).getS());
    }

    @Test
    public void theRfc8291KeysParse() throws Exception {
        final byte[] uaPublic = EcKeys.decodeUrl(
                "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4");
        final ECPublicKey key = EcKeys.publicKeyFromPoint(uaPublic);
        Assert.assertEquals(EcKeys.pointOf(key), uaPublic, "encode(decode(point)) is the identity");
        Assert.assertEquals(EcKeys.encodeUrl(uaPublic),
                "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4");
    }

    @Test
    public void aCompressedOrShortPointIsRefused() {
        Assert.assertThrows(GeneralSecurityException.class, () -> EcKeys.publicKeyFromPoint(new byte[10]));
        final byte[] wrongPrefix = new byte[EcKeys.POINT_LENGTH];
        wrongPrefix[0] = 0x02;
        Assert.assertThrows(GeneralSecurityException.class, () -> EcKeys.publicKeyFromPoint(wrongPrefix));
        Assert.assertThrows(GeneralSecurityException.class, () -> EcKeys.publicKeyFromPoint(null));
    }
}
