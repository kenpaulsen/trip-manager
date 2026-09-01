package org.paulsens.trip.dynamo;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.model.PasskeyCredential;
import org.paulsens.trip.model.Person;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

/**
 * The {@code passkeys} table: registered WebAuthn credentials, keyed by credential id.
 *
 * <p>Uncached like {@link CredentialsDAO} (rows authenticate; a stale read is a security bug), and the
 * by-user/by-email lookups are table scans like {@link AuthTokenDAO} -- the table is tiny, the lookups are
 * rare (a registration ceremony, a manage-page render), and a GSI would exist only for them while breaking
 * the in-memory fake.
 */
@Slf4j
public class PasskeyDAO {
    static final String PASSKEY_TABLE = "passkeys";
    static final String CREDENTIAL_ID = "credentialId";
    static final String USER_ID = "userId";
    static final String EMAIL = "email";
    static final String USER_HANDLE = "userHandle";
    static final String PUBLIC_KEY = "publicKeyCose";
    static final String SIGN_COUNT = "signCount";
    static final String TRANSPORTS = "transports";
    static final String LABEL = "label";
    static final String RP_ID = "rpId";
    static final String CREATED = "created";
    static final String LAST_USED = "lastUsed";

    private final Persistence persistence;

    protected PasskeyDAO(final Persistence persistence) {
        this.persistence = persistence;
    }

    protected Optional<PasskeyCredential> getPasskey(final String credentialId) {
        if (credentialId == null || credentialId.isEmpty()) {
            return Optional.empty();
        }
        final GetItemResponse item = persistence.getItem(b -> b.tableName(PASSKEY_TABLE)
                .key(Map.of(CREDENTIAL_ID, AttributeValue.builder().s(credentialId).build())).build());
        // Emptiness, not hasItem(): the in-memory fake answers a miss with an explicit empty map.
        return (item.hasItem() && !item.item().isEmpty())
                ? Optional.of(fromItem(item.item())) : Optional.empty();
    }

    protected List<PasskeyCredential> getPasskeysForUser(final Person.Id userId) {
        if (userId == null) {
            return List.of();
        }
        return scanWhere(row -> userId.getValue().equals(str(row, USER_ID)));
    }

    /** The credentials offered/excluded during a ceremony -- domain-scoped, like the browser itself. */
    protected List<PasskeyCredential> getPasskeysForEmailAndRp(final String email, final String rpId) {
        if (email == null || email.isEmpty() || rpId == null) {
            return List.of();
        }
        final String lowEmail = email.toLowerCase(Locale.ROOT);
        return scanWhere(row -> lowEmail.equals(str(row, EMAIL)) && rpId.equals(str(row, RP_ID)));
    }

    protected Boolean savePasskey(final PasskeyCredential passkey) {
        final Map<String, AttributeValue> item = new HashMap<>();
        item.put(CREDENTIAL_ID, AttributeValue.builder().s(passkey.getCredentialId()).build());
        item.put(USER_ID, AttributeValue.builder().s(passkey.getUserId().getValue()).build());
        item.put(EMAIL, AttributeValue.builder().s(passkey.getEmail()).build());
        item.put(USER_HANDLE, AttributeValue.builder().s(passkey.getUserHandle()).build());
        item.put(PUBLIC_KEY, AttributeValue.builder().s(passkey.getPublicKeyCose()).build());
        item.put(SIGN_COUNT, AttributeValue.builder().n(String.valueOf(passkey.getSignCount())).build());
        if (passkey.getTransports() != null) {
            item.put(TRANSPORTS, AttributeValue.builder().s(passkey.getTransports()).build());
        }
        if (passkey.getLabel() != null) {
            item.put(LABEL, AttributeValue.builder().s(passkey.getLabel()).build());
        }
        item.put(RP_ID, AttributeValue.builder().s(passkey.getRpId()).build());
        item.put(CREATED, AttributeValue.builder().n(String.valueOf(passkey.getCreated())).build());
        item.put(LAST_USED, AttributeValue.builder().n(String.valueOf(passkey.getLastUsed())).build());
        try {
            return persistence.putItem(b -> b.tableName(PASSKEY_TABLE).item(item))
                    .sdkHttpResponse().isSuccessful();
        } catch (final RuntimeException ex) {
            log.error("Failed to save passkey.", ex);
            return false;
        }
    }

    /** Owner-checked: the id names whose keys may be deleted, whatever the page sent. */
    protected Boolean deletePasskey(final String credentialId, final Person.Id owner) {
        final PasskeyCredential existing = getPasskey(credentialId).orElse(null);
        if (existing == null || owner == null || !owner.equals(existing.getUserId())) {
            return false;
        }
        try {
            return persistence.deleteItem(b -> b.tableName(PASSKEY_TABLE)
                            .key(Map.of(CREDENTIAL_ID, AttributeValue.builder().s(credentialId).build())))
                    .sdkHttpResponse().isSuccessful();
        } catch (final RuntimeException ex) {
            log.error("Failed to delete passkey.", ex);
            return false;
        }
    }

    private List<PasskeyCredential> scanWhere(final java.util.function.Predicate<Map<String, AttributeValue>> keep) {
        return persistence.scanAll(b -> b.tableName(PASSKEY_TABLE)).stream()
                .filter(keep)
                .map(PasskeyDAO::fromItem)
                .toList();
    }

    private static PasskeyCredential fromItem(final Map<String, AttributeValue> item) {
        return new PasskeyCredential(
                str(item, CREDENTIAL_ID),
                Person.Id.from(str(item, USER_ID)),
                str(item, EMAIL),
                str(item, USER_HANDLE),
                str(item, PUBLIC_KEY),
                numberOrZero(item.get(SIGN_COUNT)),
                str(item, TRANSPORTS),
                str(item, LABEL),
                str(item, RP_ID),
                numberOrNull(item.get(CREATED)),
                numberOrNull(item.get(LAST_USED)));
    }

    private static String str(final Map<String, AttributeValue> item, final String name) {
        final AttributeValue value = item.get(name);
        return value == null ? null : value.s();
    }

    private static long numberOrZero(final AttributeValue value) {
        return (value == null || value.n() == null) ? 0L : Long.parseLong(value.n());
    }

    private static Long numberOrNull(final AttributeValue value) {
        return (value == null || value.n() == null) ? null : Long.valueOf(value.n());
    }
}
