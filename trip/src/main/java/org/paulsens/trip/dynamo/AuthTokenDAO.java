package org.paulsens.trip.dynamo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.model.AuthToken;
import org.paulsens.trip.model.Person;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

/**
 * The {@code auth_tokens} table: one row per presentable credential -- remember-me cookies, API refresh
 * tokens, API access tokens (see {@code docs/api-tokens.md}).
 *
 * <p>Deliberately UNCACHED here, like {@link CredentialsDAO}: these rows authenticate, so a stale read is a
 * security bug. The one sanctioned exception is the ACCESS-token validation cache in
 * {@code security/TokenService}, which is read per API request -- the cost model the no-cache rule was not
 * written for -- and which preserves the rule's intent differently (explicit {@code removeKey} on every
 * revocation, soft-TTL reload as backstop). Deliberately DynamoDB rather than Valkey: refresh tokens are
 * 60-day durable state, and the cache has been flushed in real incidents -- silently signing every device out
 * defeats the feature. The {@code expires} attribute doubles as the table's TTL, so expired rows delete
 * themselves.
 *
 * <p>This table replaces {@code remember_me}, which DynamoDB cannot rename. Migration is lazy: a selector
 * missed here is looked up in the old table, copied forward, and deleted from it -- no script, no mass
 * re-login, no burned-token theft alarms. Until the fallback is dropped (a follow-up release), the scans
 * ({@link #deleteAllForUser}, {@link #listForUser}) cover BOTH tables, or password-change revocation would
 * miss any cookie row that was never read since the cutover.
 */
@Slf4j
public class AuthTokenDAO {
    static final String AUTH_TOKENS_TABLE = "auth_tokens";
    /** The pre-rename table, consulted on a miss until the lazy migration drains it. */
    static final String LEGACY_REMEMBER_TABLE = "remember_me";
    static final String SELECTOR = "selector";
    static final String VALIDATOR_HASH = "validatorHash";
    static final String PREV_VALIDATOR_HASH = "prevValidatorHash";
    static final String ROTATED_AT = "rotatedAt";
    static final String USER_ID = "userId";
    static final String EMAIL = "email";
    static final String CREATED = "created";
    static final String LAST_USED = "lastUsed";
    static final String EXPIRES = "expires";
    static final String KIND = "kind";
    static final String ROLE = "role";
    static final String SCOPE = "scope";
    static final String LABEL = "label";
    static final String PARENT_SELECTOR = "parentSelector";

    private final Persistence persistence;

    protected AuthTokenDAO(final Persistence persistence) {
        this.persistence = persistence;
    }

    protected Optional<AuthToken> getToken(final String selector) {
        if (selector == null || selector.isEmpty()) {
            return Optional.empty();
        }
        final Optional<AuthToken> current = read(AUTH_TOKENS_TABLE, selector);
        return current.isPresent() ? current : migrateLegacy(selector);
    }

    protected Boolean saveToken(final AuthToken token) {
        final Map<String, AttributeValue> item = new HashMap<>();
        item.put(SELECTOR, AttributeValue.builder().s(token.getSelector()).build());
        item.put(VALIDATOR_HASH, AttributeValue.builder().s(token.getValidatorHash()).build());
        // Absent until the first rotation; rows written before these columns existed read back as null.
        if (token.getPrevValidatorHash() != null) {
            item.put(PREV_VALIDATOR_HASH, AttributeValue.builder().s(token.getPrevValidatorHash()).build());
        }
        if (token.getRotatedAt() != null) {
            item.put(ROTATED_AT, AttributeValue.builder().n(String.valueOf(token.getRotatedAt())).build());
        }
        item.put(USER_ID, AttributeValue.builder().s(token.getUserId().getValue()).build());
        item.put(EMAIL, AttributeValue.builder().s(token.getEmail()).build());
        item.put(CREATED, AttributeValue.builder().n(String.valueOf(token.getCreated())).build());
        item.put(LAST_USED, AttributeValue.builder().n(String.valueOf(token.getLastUsed())).build());
        item.put(EXPIRES, AttributeValue.builder().n(String.valueOf(token.getExpires())).build());
        // Written explicitly even for REMEMBER so only pre-rename rows ever lack it.
        item.put(KIND, AttributeValue.builder().s(kindOf(token).name()).build());
        if (token.getRole() != null) {
            item.put(ROLE, AttributeValue.builder().s(token.getRole()).build());
        }
        if (token.getScope() != null) {
            item.put(SCOPE, AttributeValue.builder().s(token.getScope().name()).build());
        }
        if (token.getLabel() != null) {
            item.put(LABEL, AttributeValue.builder().s(token.getLabel()).build());
        }
        if (token.getParentSelector() != null) {
            item.put(PARENT_SELECTOR, AttributeValue.builder().s(token.getParentSelector()).build());
        }
        try {
            return persistence.putItem(b -> b.tableName(AUTH_TOKENS_TABLE).item(item))
                    .sdkHttpResponse().isSuccessful();
        } catch (final RuntimeException ex) {
            log.error("Failed to save auth token.", ex);
            return false;
        }
    }

    /**
     * Deletes from BOTH tables: a selector still sitting in {@code remember_me} must die on revocation too,
     * and deleting an absent key is a success in DynamoDB and the fake alike.
     */
    protected Boolean deleteToken(final String selector) {
        return deleteFrom(AUTH_TOKENS_TABLE, selector) && deleteFrom(LEGACY_REMEMBER_TABLE, selector);
    }

    /**
     * Revokes every credential of every kind for one person -- a password change, a credentials delete. A
     * table scan on purpose: the event is rare, the table is tiny, and a GSI (which
     * {@code InMemoryPersistence} cannot fake) would exist only for this.
     *
     * @return the deleted rows, NOT a count: the caller must purge the validation cache per ACCESS selector,
     *         and a count cannot do that. Do not "optimize" the return type away.
     */
    protected List<AuthToken> deleteAllForUser(final Person.Id userId) {
        final List<AuthToken> deleted = new ArrayList<>();
        for (final AuthToken token : listForUser(userId)) {
            if (Boolean.TRUE.equals(deleteToken(token.getSelector()))) {
                deleted.add(token);
            }
        }
        return deleted;
    }

    /** Every live credential for one person, both tables, any kind. Same scan justification as above. */
    protected List<AuthToken> listForUser(final Person.Id userId) {
        final List<AuthToken> tokens = new ArrayList<>();
        if (userId == null) {
            return tokens;
        }
        scanInto(tokens, AUTH_TOKENS_TABLE, userId);
        scanInto(tokens, LEGACY_REMEMBER_TABLE, userId);
        return tokens;
    }

    /** Fail-open like {@link #read}: one unreadable table must not take the other's rows down with it. */
    private void scanInto(final List<AuthToken> sink, final String table, final Person.Id userId) {
        try {
            for (final Map<String, AttributeValue> row : persistence.scanAll(b -> b.tableName(table))) {
                final AttributeValue owner = row.get(USER_ID);
                if (owner != null && userId.getValue().equals(owner.s())) {
                    sink.add(fromItem(row));
                }
            }
        } catch (final RuntimeException ex) {
            log.error("Failed to scan auth tokens in {}.", table, ex);
        }
    }

    /**
     * The lazy half of the table rename: a selector found only in {@code remember_me} is copied forward and
     * the original deleted, so the old table drains one returning browser at a time. A failed copy leaves the
     * legacy row alone and still answers the token -- migration is hygiene, not a precondition for signing in.
     */
    private Optional<AuthToken> migrateLegacy(final String selector) {
        final Optional<AuthToken> legacy = read(LEGACY_REMEMBER_TABLE, selector);
        legacy.ifPresent(token -> {
            if (Boolean.TRUE.equals(saveToken(token))) {
                deleteFrom(LEGACY_REMEMBER_TABLE, selector);
            }
        });
        return legacy;
    }

    /**
     * A failed read answers empty, never throws: a token lookup sits on the login path, and "not signed in"
     * is the right degradation for a broken store. The concrete case this guards is the deploy window in
     * which the app is live before {@code cdk deploy} has created {@code auth_tokens} -- every remember-me
     * restore reads this table, and an exception here would surface on ordinary page loads.
     */
    private Optional<AuthToken> read(final String table, final String selector) {
        try {
            final GetItemResponse item = persistence.getItem(b -> b.tableName(table)
                    .key(Map.of(SELECTOR, AttributeValue.builder().s(selector).build())).build());
            // Emptiness, not hasItem(): the in-memory fake answers a miss with an explicit empty map.
            return (item.hasItem() && !item.item().isEmpty())
                    ? Optional.of(fromItem(item.item())) : Optional.empty();
        } catch (final RuntimeException ex) {
            log.error("Failed to read auth token from {}.", table, ex);
            return Optional.empty();
        }
    }

    private Boolean deleteFrom(final String table, final String selector) {
        try {
            return persistence.deleteItem(b -> b.tableName(table)
                            .key(Map.of(SELECTOR, AttributeValue.builder().s(selector).build())))
                    .sdkHttpResponse().isSuccessful();
        } catch (final RuntimeException ex) {
            log.error("Failed to delete auth token.", ex);
            return false;
        }
    }

    private static AuthToken fromItem(final Map<String, AttributeValue> item) {
        return AuthToken.builder()
                .selector(item.get(SELECTOR).s())
                .validatorHash(item.get(VALIDATOR_HASH).s())
                .prevValidatorHash(stringOrNull(item.get(PREV_VALIDATOR_HASH)))
                .rotatedAt(numberOrNull(item.get(ROTATED_AT)))
                .userId(Person.Id.from(item.get(USER_ID).s()))
                .email(item.get(EMAIL).s())
                .created(numberOrNull(item.get(CREATED)))
                .lastUsed(numberOrNull(item.get(LAST_USED)))
                .expires(numberOrNull(item.get(EXPIRES)))
                .kind(kindOrRemember(item.get(KIND)))
                .role(stringOrNull(item.get(ROLE)))
                .scope(scopeOrNull(item.get(SCOPE)))
                .label(stringOrNull(item.get(LABEL)))
                .parentSelector(stringOrNull(item.get(PARENT_SELECTOR)))
                .build();
    }

    private static AuthToken.Kind kindOf(final AuthToken token) {
        return token.getKind() == null ? AuthToken.Kind.REMEMBER : token.getKind();
    }

    /** Rows written before the rename carry no kind; every one of them is a remember-me cookie. */
    private static AuthToken.Kind kindOrRemember(final AttributeValue value) {
        return (value == null || value.s() == null) ? AuthToken.Kind.REMEMBER : AuthToken.Kind.valueOf(value.s());
    }

    private static AuthToken.Scope scopeOrNull(final AttributeValue value) {
        return (value == null || value.s() == null) ? null : AuthToken.Scope.valueOf(value.s());
    }

    private static Long numberOrNull(final AttributeValue value) {
        return (value == null || value.n() == null) ? null : Long.valueOf(value.n());
    }

    private static String stringOrNull(final AttributeValue value) {
        return (value == null) ? null : value.s();
    }
}
