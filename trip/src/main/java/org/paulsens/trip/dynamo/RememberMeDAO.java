package org.paulsens.trip.dynamo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.RememberToken;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

/**
 * The {@code remember_me} table: one row per browser holding a live remember-me cookie.
 *
 * <p>Deliberately UNCACHED, like {@link CredentialsDAO}: these rows authenticate, so a stale read is a
 * security bug, and the volume (one point-read per returning browser per restart) needs no cache. Deliberately
 * DynamoDB rather than Valkey: tokens are 60-day durable state, and the cache has been flushed in real
 * incidents -- silently signing every browser out defeats the feature. The {@code expires} attribute doubles
 * as the table's TTL, so expired rows delete themselves.
 */
@Slf4j
public class RememberMeDAO {
    static final String REMEMBER_TABLE = "remember_me";
    static final String SELECTOR = "selector";
    static final String VALIDATOR_HASH = "validatorHash";
    static final String USER_ID = "userId";
    static final String EMAIL = "email";
    static final String CREATED = "created";
    static final String LAST_USED = "lastUsed";
    static final String EXPIRES = "expires";

    private final Persistence persistence;

    protected RememberMeDAO(final Persistence persistence) {
        this.persistence = persistence;
    }

    protected Optional<RememberToken> getToken(final String selector) {
        if (selector == null || selector.isEmpty()) {
            return Optional.empty();
        }
        final GetItemResponse item = persistence.getItem(b -> b.tableName(REMEMBER_TABLE)
                .key(Map.of(SELECTOR, AttributeValue.builder().s(selector).build())).build());
        // Emptiness, not hasItem(): the in-memory fake answers a miss with an explicit empty map.
        return (item.hasItem() && !item.item().isEmpty())
                ? Optional.of(fromItem(item.item())) : Optional.empty();
    }

    protected Boolean saveToken(final RememberToken token) {
        final Map<String, AttributeValue> item = new HashMap<>();
        item.put(SELECTOR, AttributeValue.builder().s(token.getSelector()).build());
        item.put(VALIDATOR_HASH, AttributeValue.builder().s(token.getValidatorHash()).build());
        item.put(USER_ID, AttributeValue.builder().s(token.getUserId().getValue()).build());
        item.put(EMAIL, AttributeValue.builder().s(token.getEmail()).build());
        item.put(CREATED, AttributeValue.builder().n(String.valueOf(token.getCreated())).build());
        item.put(LAST_USED, AttributeValue.builder().n(String.valueOf(token.getLastUsed())).build());
        item.put(EXPIRES, AttributeValue.builder().n(String.valueOf(token.getExpires())).build());
        try {
            return persistence.putItem(b -> b.tableName(REMEMBER_TABLE).item(item))
                    .sdkHttpResponse().isSuccessful();
        } catch (final RuntimeException ex) {
            log.error("Failed to save remember-me token.", ex);
            return false;
        }
    }

    protected Boolean deleteToken(final String selector) {
        try {
            return persistence.deleteItem(b -> b.tableName(REMEMBER_TABLE)
                            .key(Map.of(SELECTOR, AttributeValue.builder().s(selector).build())))
                    .sdkHttpResponse().isSuccessful();
        } catch (final RuntimeException ex) {
            log.error("Failed to delete remember-me token.", ex);
            return false;
        }
    }

    /**
     * Revokes every browser's token for one person -- a password change, a credentials delete. A table scan
     * on purpose: the event is rare, the table is tiny, and a GSI (which {@code InMemoryPersistence} cannot
     * fake) would exist only for this.
     */
    protected int deleteAllForUser(final Person.Id userId) {
        if (userId == null) {
            return 0;
        }
        final List<Map<String, AttributeValue>> rows =
                persistence.scanAll(b -> b.tableName(REMEMBER_TABLE));
        int deleted = 0;
        for (final Map<String, AttributeValue> row : rows) {
            final AttributeValue owner = row.get(USER_ID);
            if (owner != null && userId.getValue().equals(owner.s())
                    && Boolean.TRUE.equals(deleteToken(row.get(SELECTOR).s()))) {
                deleted++;
            }
        }
        return deleted;
    }

    private static RememberToken fromItem(final Map<String, AttributeValue> item) {
        return new RememberToken(
                item.get(SELECTOR).s(),
                item.get(VALIDATOR_HASH).s(),
                Person.Id.from(item.get(USER_ID).s()),
                item.get(EMAIL).s(),
                numberOrNull(item.get(CREATED)),
                numberOrNull(item.get(LAST_USED)),
                numberOrNull(item.get(EXPIRES)));
    }

    private static Long numberOrNull(final AttributeValue value) {
        return (value == null || value.n() == null) ? null : Long.valueOf(value.n());
    }
}
