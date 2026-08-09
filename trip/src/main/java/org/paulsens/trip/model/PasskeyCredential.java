package org.paulsens.trip.model;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * One registered passkey (WebAuthn credential): the public half of a keypair that lives in someone's phone,
 * laptop, or security key. Holds no secrets at all -- a copy of this table lets an attacker do nothing.
 *
 * <p>Passkeys are scoped to the domain ({@code rpId}) they were registered on, hard, by the browser. This app
 * serves several domains, so the row records which one; a key registered on one domain simply never appears
 * on another, and the person types their password there like always.
 */
@Data
@AllArgsConstructor
public final class PasskeyCredential implements Serializable {
    /** The authenticator-assigned credential id, base64url; the table's key. */
    private String credentialId;
    private Person.Id userId;
    /** Lowercased; what the assertion logs the person in as. */
    private String email;
    /** WebAuthn user handle, base64url of the {@link Person.Id} value -- stable where email is not. */
    private String userHandle;
    /** COSE-encoded public key, base64url. */
    private String publicKeyCose;
    /** Authenticator signature counter; cloud-synced passkeys legitimately report 0 forever. */
    private long signCount;
    /** Comma-joined transports ("internal,hybrid"), informational. */
    private String transports;
    /** User-facing name ("iPhone", "Windows Hello"), editable on the manage page. */
    private String label;
    /** The registrable domain this key belongs to. */
    private String rpId;
    /** Epoch seconds. */
    private Long created;
    private Long lastUsed;
}
