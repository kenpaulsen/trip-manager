package org.paulsens.trip.security;

import com.upokecenter.cbor.CBORObject;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;

/**
 * A software authenticator for tests: a real P-256 keypair producing REAL WebAuthn wire-format responses that
 * the Yubico library fully verifies -- signatures included. What the browser+authenticator produce in
 * production, minus the hardware.
 */
public final class WebAuthnTestFixture {

    private final KeyPair keyPair;
    private final byte[] credentialId;

    private WebAuthnTestFixture(final KeyPair keyPair, final byte[] credentialId) {
        this.keyPair = keyPair;
        this.credentialId = credentialId;
    }

    public static WebAuthnTestFixture create() {
        try {
            final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(256);
            final byte[] credentialId = new byte[16];
            new java.security.SecureRandom().nextBytes(credentialId);
            return new WebAuthnTestFixture(generator.generateKeyPair(), credentialId);
        } catch (final Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public String credentialIdBase64Url() {
        return b64u(credentialId);
    }

    /** The browser's {@code create()} result for the given options-challenge, ready for finishRegistration. */
    public String registrationResponseJson(final String challengeB64u, final String origin, final String rpId) {
        try {
            final byte[] clientData = clientData("webauthn.create", challengeB64u, origin);
            final byte[] attested = attestedCredentialData();
            final ByteBuffer authData = ByteBuffer.allocate(37 + attested.length);
            authData.put(sha256(rpId.getBytes(StandardCharsets.UTF_8)));
            authData.put((byte) 0x45);              // UP | UV | AT
            authData.putInt(0);                     // sign count
            authData.put(attested);
            final byte[] attestationObject = CBORObject.NewMap()
                    .Add("fmt", "none")
                    .Add("attStmt", CBORObject.NewMap())
                    .Add("authData", authData.array())
                    .EncodeToBytes();
            return "{"
                    + "\"id\":\"" + b64u(credentialId) + "\","
                    + "\"rawId\":\"" + b64u(credentialId) + "\","
                    + "\"type\":\"public-key\","
                    + "\"response\":{"
                    + "\"attestationObject\":\"" + b64u(attestationObject) + "\","
                    + "\"clientDataJSON\":\"" + b64u(clientData) + "\","
                    + "\"transports\":[\"internal\"]},"
                    + "\"clientExtensionResults\":{}"
                    + "}";
        } catch (final Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** The browser's {@code get()} result: authenticator data plus a REAL signature over it. */
    public String assertionResponseJson(final String challengeB64u, final String origin, final String rpId,
            final long counter, final byte[] userHandle) {
        try {
            final byte[] clientData = clientData("webauthn.get", challengeB64u, origin);
            final ByteBuffer authData = ByteBuffer.allocate(37);
            authData.put(sha256(rpId.getBytes(StandardCharsets.UTF_8)));
            authData.put((byte) 0x05);              // UP | UV
            authData.putInt((int) counter);
            final Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(keyPair.getPrivate());
            signer.update(authData.array());
            signer.update(sha256(clientData));
            final byte[] signature = signer.sign();
            return "{"
                    + "\"id\":\"" + b64u(credentialId) + "\","
                    + "\"rawId\":\"" + b64u(credentialId) + "\","
                    + "\"type\":\"public-key\","
                    + "\"response\":{"
                    + "\"authenticatorData\":\"" + b64u(authData.array()) + "\","
                    + "\"clientDataJSON\":\"" + b64u(clientData) + "\","
                    + "\"signature\":\"" + b64u(signature) + "\","
                    + "\"userHandle\":\"" + b64u(userHandle) + "\"},"
                    + "\"clientExtensionResults\":{}"
                    + "}";
        } catch (final Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private byte[] attestedCredentialData() {
        final byte[] coseKey = coseKey((ECPublicKey) keyPair.getPublic());
        final ByteBuffer buf = ByteBuffer.allocate(16 + 2 + credentialId.length + coseKey.length);
        buf.put(new byte[16]);                      // AAGUID: zeros (fmt "none")
        buf.putShort((short) credentialId.length);
        buf.put(credentialId);
        buf.put(coseKey);
        return buf.array();
    }

    /** COSE_Key for ES256: {1: 2 (EC2), 3: -7 (ES256), -1: 1 (P-256), -2: x, -3: y}. */
    private static byte[] coseKey(final ECPublicKey publicKey) {
        return CBORObject.NewMap()
                .Add(1, 2)
                .Add(3, -7)
                .Add(-1, 1)
                .Add(-2, coordinate(publicKey.getW().getAffineX()))
                .Add(-3, coordinate(publicKey.getW().getAffineY()))
                .EncodeToBytes();
    }

    /** A P-256 coordinate as exactly 32 bytes (BigInteger adds sign bytes and drops leading zeros). */
    private static byte[] coordinate(final BigInteger value) {
        final byte[] raw = value.toByteArray();
        final byte[] fixed = new byte[32];
        if (raw.length >= 32) {
            System.arraycopy(raw, raw.length - 32, fixed, 0, 32);
        } else {
            System.arraycopy(raw, 0, fixed, 32 - raw.length, raw.length);
        }
        return fixed;
    }

    private static byte[] clientData(final String type, final String challengeB64u, final String origin) {
        return ("{\"type\":\"" + type + "\",\"challenge\":\"" + challengeB64u + "\",\"origin\":\"" + origin
                + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sha256(final byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (final Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String b64u(final byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
