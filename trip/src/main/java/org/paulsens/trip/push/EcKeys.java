package org.paulsens.trip.push;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * P-256 key plumbing shared by APNs (a {@code .p8} PEM), VAPID (raw scalar + uncompressed point, both
 * base64url) and Web Push encryption (the subscription's uncompressed point). All JDK: {@code SunEC} lives
 * in {@code java.base} since JDK 22, so the jlink image needs no extra module.
 */
final class EcKeys {

    /** Uncompressed SEC1 point: {@code 0x04 || X(32) || Y(32)}. */
    static final int POINT_LENGTH = 65;
    private static final int COORD_LENGTH = 32;

    private EcKeys() {
    }

    /** The curve parameters, read off a generated key rather than {@code AlgorithmParameters}: one seam, one catch. */
    static ECParameterSpec p256() {
        return ((ECPublicKey) generate().getPublic()).getParams();
    }

    static KeyPair generate() {
        try {
            final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (final GeneralSecurityException ex) {
            throw new IllegalStateException("P-256 is mandatory in every JRE", ex);
        }
    }

    /** A {@code -----BEGIN PRIVATE KEY-----} PKCS#8 PEM (Apple's {@code .p8}) to a key. */
    static ECPrivateKey privateKeyFromPem(final String pem) throws GeneralSecurityException {
        final String base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        final byte[] der = Base64.getDecoder().decode(base64);
        return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /** A raw 32-byte scalar {@code d} (VAPID's private key) to a key. */
    static ECPrivateKey privateKeyFromScalar(final byte[] d) throws GeneralSecurityException {
        return (ECPrivateKey) KeyFactory.getInstance("EC")
                .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, d), p256()));
    }

    /** An uncompressed 65-byte point to a public key. */
    static ECPublicKey publicKeyFromPoint(final byte[] point) throws GeneralSecurityException {
        if (point == null || point.length != POINT_LENGTH || point[0] != 0x04) {
            throw new GeneralSecurityException("Not an uncompressed P-256 point");
        }
        final BigInteger x = new BigInteger(1, Arrays.copyOfRange(point, 1, 1 + COORD_LENGTH));
        final BigInteger y = new BigInteger(1, Arrays.copyOfRange(point, 1 + COORD_LENGTH, POINT_LENGTH));
        return (ECPublicKey) KeyFactory.getInstance("EC")
                .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), p256()));
    }

    /** A public key to its uncompressed 65-byte point (what a VAPID {@code k=} and the aes128gcm header carry). */
    static byte[] pointOf(final ECPublicKey key) {
        final byte[] out = new byte[POINT_LENGTH];
        out[0] = 0x04;
        copyCoordinate(key.getW().getAffineX(), out, 1);
        copyCoordinate(key.getW().getAffineY(), out, 1 + COORD_LENGTH);
        return out;
    }

    /** Left-pads (or trims a sign byte off) a coordinate into exactly 32 bytes. */
    private static void copyCoordinate(final BigInteger value, final byte[] target, final int offset) {
        final byte[] raw = value.toByteArray();
        final int start = Math.max(0, raw.length - COORD_LENGTH);
        final int length = raw.length - start;
        System.arraycopy(raw, start, target, offset + (COORD_LENGTH - length), length);
    }

    static byte[] decodeUrl(final String base64url) {
        return Base64.getUrlDecoder().decode(base64url.trim());
    }

    static String encodeUrl(final byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
