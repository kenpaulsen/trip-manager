package org.paulsens.trip.dynamo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatInvite;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

/**
 * The {@code chat_invites} table: one row per outstanding invite link for a chat channel.
 *
 * <p>Keyed PK {@code channelId} / SK {@code selector} — not PK=selector like {@code auth_tokens} — because the
 * admin UI must list a channel's outstanding links and this schema answers that with a partition query, no GSI
 * and no scan. Redemption has both keys anyway: the invite URL carries the trip id beside the token.
 *
 * <p>Deliberately UNCACHED, like {@link AuthTokenDAO}: these rows authorize, so a stale read is a security
 * bug (a revoked link must die immediately), and the volume — one point-read per invite click — needs no
 * cache. The {@code expires} attribute doubles as the table's TTL, so expired rows delete themselves; readers
 * still check it because TTL deletion can lag by up to 48 hours.
 */
@Slf4j
public class ChatInviteDAO {
    static final String INVITES_TABLE = "chat_invites";
    static final String CHANNEL_ID = "channelId";
    static final String SELECTOR = "selector";
    static final String VALIDATOR_HASH = "validatorHash";
    static final String CREATED_BY = "createdBy";
    static final String CREATED = "created";
    static final String EXPIRES = "expires";
    static final String USES = "uses";

    private final Persistence persistence;

    protected ChatInviteDAO(final Persistence persistence) {
        this.persistence = persistence;
    }

    protected Boolean saveInvite(final ChatInvite invite) {
        if (invite == null || invite.getChannelId() == null || invite.getSelector() == null) {
            return false;
        }
        final Map<String, AttributeValue> item = itemFor(invite);
        try {
            return persistence.putItem(b -> b.tableName(INVITES_TABLE).item(item))
                    .sdkHttpResponse().isSuccessful();
        } catch (final RuntimeException ex) {
            log.error("Failed to save chat invite {} for {}.", invite.getSelector(),
                    invite.getChannelId().getValue(), ex);
            return false;
        }
    }

    private static Map<String, AttributeValue> itemFor(final ChatInvite invite) {
        final Map<String, AttributeValue> item = new HashMap<>();
        item.put(CHANNEL_ID, AttributeValue.builder().s(invite.getChannelId().getValue()).build());
        item.put(SELECTOR, AttributeValue.builder().s(invite.getSelector()).build());
        item.put(VALIDATOR_HASH, AttributeValue.builder().s(invite.getValidatorHash()).build());
        item.put(CREATED_BY, AttributeValue.builder().s(invite.getCreatedBy().getValue()).build());
        item.put(CREATED, AttributeValue.builder().n(String.valueOf(invite.getCreated().getEpochSecond())).build());
        item.put(EXPIRES, AttributeValue.builder().n(String.valueOf(invite.getExpires())).build());
        item.put(USES, AttributeValue.builder().n(String.valueOf(invite.getUses())).build());
        return item;
    }

    protected Optional<ChatInvite> getInvite(final ChatChannel.Id channelId, final String selector) {
        if (channelId == null || selector == null || selector.isEmpty()) {
            return Optional.empty();
        }
        // Strongly consistent: a link revoked a moment ago must already read as gone.
        final GetItemResponse item = persistence.getItem(b -> b.tableName(INVITES_TABLE).consistentRead(true)
                .key(inviteKey(channelId, selector)).build());
        // Emptiness, not hasItem(): the in-memory fake answers a miss with an explicit empty map.
        return (item.hasItem() && !item.item().isEmpty())
                ? Optional.of(fromItem(item.item())) : Optional.empty();
    }

    protected List<ChatInvite> listInvites(final ChatChannel.Id channelId) {
        if (channelId == null) {
            return List.of();
        }
        final Map<String, String> names = Map.of("#channelId", CHANNEL_ID);
        final Map<String, AttributeValue> values =
                Map.of(":c", AttributeValue.builder().s(channelId.getValue()).build());
        return persistence.queryAll(b -> b.tableName(INVITES_TABLE)
                        .keyConditionExpression("#channelId = :c")
                        .expressionAttributeNames(names)
                        .expressionAttributeValues(values)
                        .build())
                .stream()
                .map(ChatInviteDAO::fromItem)
                .toList();
    }

    protected Boolean deleteInvite(final ChatChannel.Id channelId, final String selector) {
        if (channelId == null || selector == null || selector.isEmpty()) {
            return false;
        }
        try {
            return persistence.deleteItem(b -> b.tableName(INVITES_TABLE)
                            .key(inviteKey(channelId, selector)))
                    .sdkHttpResponse().isSuccessful();
        } catch (final RuntimeException ex) {
            log.error("Failed to delete chat invite {} for {}.", selector, channelId.getValue(), ex);
            return false;
        }
    }

    /**
     * Counts one redemption, and answers whether the link is still there to count it on.
     *
     * <p>The count itself stays best effort: a read-modify-write whose exact value is informational, so a
     * store error is logged and answered {@code true} -- a lost increment must never fail the join it counts.
     * What is NOT best effort is the row's existence. The put is conditional on the row still being there,
     * because the unconditional one wrote a link back that an administrator had revoked between the
     * redemption's read and this write, and "a revoked link must die immediately". Only that case answers
     * {@code false}, and the caller refuses the redemption on it.
     */
    protected boolean recordUse(final ChatInvite invite) {
        final Map<String, AttributeValue> item = itemFor(new ChatInvite(invite.getChannelId(),
                invite.getSelector(), invite.getValidatorHash(), invite.getCreatedBy(), invite.getCreated(),
                invite.getExpires(), invite.getUses() + 1));
        try {
            persistence.putItem(b -> b.tableName(INVITES_TABLE).item(item)
                    .conditionExpression("attribute_exists(#s)")
                    .expressionAttributeNames(Map.of("#s", SELECTOR)));
            return true;
        } catch (final ConditionalCheckFailedException gone) {
            return false;
        } catch (final RuntimeException ex) {
            log.debug("Chat invite use-count bump failed for {}.", invite.getSelector(), ex);
            return true;
        }
    }

    private static Map<String, AttributeValue> inviteKey(final ChatChannel.Id channelId, final String selector) {
        return Map.of(
                CHANNEL_ID, AttributeValue.builder().s(channelId.getValue()).build(),
                SELECTOR, AttributeValue.builder().s(selector).build());
    }

    private static ChatInvite fromItem(final Map<String, AttributeValue> item) {
        return new ChatInvite(
                ChatChannel.Id.from(item.get(CHANNEL_ID).s()),
                item.get(SELECTOR).s(),
                item.get(VALIDATOR_HASH).s(),
                Person.Id.from(item.get(CREATED_BY).s()),
                java.time.Instant.ofEpochSecond(numberOr(item.get(CREATED), 0L)),
                numberOr(item.get(EXPIRES), 0L),
                numberOr(item.get(USES), 0L));
    }

    private static long numberOr(final AttributeValue value, final long fallback) {
        return (value == null || value.n() == null) ? fallback : Long.parseLong(value.n());
    }
}
