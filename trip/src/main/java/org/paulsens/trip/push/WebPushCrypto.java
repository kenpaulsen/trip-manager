package org.paulsens.trip.push;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 8291 message encryption for Web Push ({@code aes128gcm}, RFC 8188 framing), all JDK crypto: P-256
 * ECDH against the subscription's key, HKDF-SHA256 hand-rolled over {@code HmacSHA256} (the JDK has no
 * HKDF API on this line), AES-128-GCM. One record per message -- a notification is a few hundred bytes.
 *
 * <p>Layout of the body this produces (RFC 8188 §2.1):
 * {@code salt(16) || rs(4) || idlen(1)=65 || keyid(65 = our ephemeral public point) || ciphertext || tag}.
 * The RFC 8291 Appendix A vector is reproduced byte-for-byte by {@code WebPushClientTest} with the
 * appendix's keys and salt injected.
 */
final class WebPushCrypto {

    static final int SALT_LENGTH = 16;
    static final int RECORD_SIZE = 4096;
    static final int AUTH_MIN_LENGTH = 16;
    private static final int KEY_LENGTH = 16;
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int HASH_LENGTH = 32;

    private WebPushCrypto() {
    }

    /**
     * Encrypts {@code plaintext} for the subscription ({@code uaPublic} = its 65-byte {@code p256dh} point,
     * {@code authSecret} = its {@code auth} bytes) using {@code asKeys} as the sender's ephemeral pair and
     * {@code salt} (16 random bytes; injectable for the RFC vector).
     */
    static byte[] encrypt(final byte[] plaintext, final byte[] uaPublic, final byte[] authSecret,
            final KeyPair asKeys, final byte[] salt) throws GeneralSecurityException {
        if (salt == null || salt.length != SALT_LENGTH) {
            throw new GeneralSecurityException("aes128gcm salt must be 16 bytes");
        }
        if (authSecret == null || authSecret.length < AUTH_MIN_LENGTH) {
            throw new GeneralSecurityException("auth secret must be at least 16 bytes");
        }
        final ECPublicKey uaKey = EcKeys.publicKeyFromPoint(uaPublic);
        final byte[] asPublic = EcKeys.pointOf((ECPublicKey) asKeys.getPublic());
        final byte[] ecdh = agree(asKeys, uaKey);
        // RFC 8291 §3.3-3.4: IKM = HKDF(auth, ecdh, "WebPush: info" || 0x00 || ua_public || as_public, 32).
        final byte[] ikm = hkdf(authSecret, ecdh, concat("WebPush: info".getBytes(StandardCharsets.US_ASCII),
                new byte[] {0}, uaPublic, asPublic), HASH_LENGTH);
        // RFC 8188 §2.2: CEK and NONCE from the salt and the IKM.
        final byte[] cek = hkdf(salt, ikm, info("Content-Encoding: aes128gcm"), KEY_LENGTH);
        final byte[] nonce = hkdf(salt, ikm, info("Content-Encoding: nonce"), NONCE_LENGTH);
        // One record: plaintext || 0x02 (the last-record delimiter), no extra padding.
        final byte[] record = concat(plaintext, new byte[] {2});
        final Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        final byte[] ciphertext = gcm.doFinal(record);
        return concat(header(salt, asPublic), ciphertext);
    }

    /** {@code salt || rs || idlen || keyid}. */
    static byte[] header(final byte[] salt, final byte[] keyId) {
        return ByteBuffer.allocate(SALT_LENGTH + 4 + 1 + keyId.length)
                .put(salt).putInt(RECORD_SIZE).put((byte) keyId.length).put(keyId).array();
    }

    /** HKDF-SHA256 extract-then-expand (RFC 5869), for outputs up to one hash block. */
    static byte[] hkdf(final byte[] salt, final byte[] ikm, final byte[] info, final int length)
            throws GeneralSecurityException {
        if (length > HASH_LENGTH) {
            throw new GeneralSecurityException("Web Push never needs more than one HKDF block");
        }
        final Mac extract = Mac.getInstance("HmacSHA256");
        extract.init(new SecretKeySpec(salt, "HmacSHA256"));
        final byte[] prk = extract.doFinal(ikm);
        final Mac expand = Mac.getInstance("HmacSHA256");
        expand.init(new SecretKeySpec(prk, "HmacSHA256"));
        expand.update(info);
        expand.update((byte) 1);
        final byte[] block = expand.doFinal();
        final byte[] out = new byte[length];
        System.arraycopy(block, 0, out, 0, length);
        return out;
    }

    /** The RFC 8188 info strings end in a single zero byte. */
    static byte[] info(final String label) {
        return concat(label.getBytes(StandardCharsets.US_ASCII), new byte[] {0});
    }

    static byte[] agree(final KeyPair ours, final ECPublicKey theirs) throws GeneralSecurityException {
        final KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(ours.getPrivate());
        agreement.doPhase(theirs, true);
        return agreement.generateSecret();
    }

    static byte[] concat(final byte[]... parts) {
        int total = 0;
        for (final byte[] part : parts) {
            total += part.length;
        }
        final ByteBuffer buffer = ByteBuffer.allocate(total);
        for (final byte[] part : parts) {
            buffer.put(part);
        }
        return buffer.array();
    }
}
