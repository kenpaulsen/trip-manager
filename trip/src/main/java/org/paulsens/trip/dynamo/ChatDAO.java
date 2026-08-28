package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.CacheSupport;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.cache.PointCache;
import org.paulsens.trip.chat.ChatNudge;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatAttachment;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMembership;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatPage;
import org.paulsens.trip.model.chat.ChatReaction;
import org.paulsens.trip.model.chat.ChatReactionSummary;
import org.paulsens.trip.model.chat.ChatVisibility;
import org.paulsens.trip.model.chat.PhotoChatMeta;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

/**
 * Chat persistence: four Dynamo tables + Valkey hot buffer. See chat-design.md §§2–5.
 *
 * <p>Message writes go Dynamo first (conditional put + monotonic id allocator), then best-effort Valkey.
 * Visibility is always {@link ChatVisibility} — never the stored {@code expiresAt} alone.
 */
@Slf4j
public class ChatDAO {

    static final String CHANNELS_TABLE = "chat_channels";
    static final String MEMBERS_TABLE = "chat_members";
    static final String MESSAGES_TABLE = "chat_messages";
    static final String REACTIONS_TABLE = "chat_reactions";

    static final String ATTR_CHANNEL_ID = "channelId";
    static final String ATTR_MSG_ID = "msgId";
    static final String ATTR_PERSON_ID = "personId";
    static final String ATTR_SK = "sk";
    static final String ATTR_CONTENT = "content";
    static final String ATTR_EXPIRES_AT = "expiresAt";

    /**
     * {@code newestFirst} values for {@link #toPage}, named because a bare boolean at a call site is exactly as
     * easy to invert as to get right — and inverting it tells a client to page in the wrong direction.
     */
    private static final boolean OLDEST_FIRST = false;
    private static final boolean NEWEST_FIRST = true;

    private static final int MAX_WRITE_RETRIES = 25;
    /** Reject writes if the per-JVM allocator runs more than this far ahead of wall-clock. */
    private static final long MAX_DRIFT_MS = 5_000L;
    private static final Duration CLIENT_MSG_TTL = Duration.ofHours(24);

    private final ObjectMapper mapper;
    private final Persistence persistence;
    private final CacheClient cacheClient;
    private final PointCache<ChatChannel> channelCache;
    private final AtomicLong idClock = new AtomicLong(0L);

    protected ChatDAO(final ObjectMapper mapper, final Persistence persistence) {
        this(mapper, persistence, new InMemoryCacheClient());
    }

    protected ChatDAO(final ObjectMapper mapper, final Persistence persistence, final CacheClient cacheClient) {
        this.mapper = mapper;
        this.persistence = persistence;
        this.cacheClient = cacheClient;
        this.channelCache = PointCache.<ChatChannel>builder()
                .cache(cacheClient)
                .keyPrefix(CacheKeys.CHAT_CHANNEL_META_PREFIX)
                .softRevalidate(CacheSupport.softRevalidateEnabled(cacheClient))
                .serializer(this::toJson)
                .deserializer(this::parseChannel)
                .build();
    }

    // --- channels ---

    protected Boolean saveChannel(final ChatChannel channel) {
        if (channel == null || channel.getId() == null) {
            return false;
        }
        final Map<String, AttributeValue> item = new HashMap<>();
        item.put(ATTR_CHANNEL_ID, persistence.toStrAttr(channel.getId().getValue()));
        final String json = toJson(channel);
        if (json == null) {
            return false;
        }
        item.put(ATTR_CONTENT, persistence.toStrAttr(json));
        try {
            final boolean saved = persistence.putItem(b -> b.tableName(CHANNELS_TABLE).item(item))
                    .sdkHttpResponse().isSuccessful();
            return
                    saved && channelCache.put(channel.getId().getValue(), channel);
        } catch (final RuntimeException ex) {
            throw ex;
        }
    }

    protected Optional<ChatChannel> getChannel(final ChatChannel.Id id) {
        if (id == null) {
            return Optional.empty();
        }
        try {
            return
                    channelCache.get(id.getValue(), missed -> pointReadChannel(missed).orElse(null));
        } catch (final RuntimeException ex) {
            throw ex;
        }
    }

    private Optional<ChatChannel> pointReadChannel(final String channelId) {
        final Map<String, AttributeValue> key =
                Map.of(ATTR_CHANNEL_ID, AttributeValue.builder().s(channelId).build());
        try {
            return channelFrom(persistence.getItem(b -> b.tableName(CHANNELS_TABLE).key(key).build()));
        } catch (final RuntimeException ex) {
            return logChannelReadFailure(channelId, ex);
        }
    }

    private Optional<ChatChannel> channelFrom(final GetItemResponse resp) {
        if (resp.item() == null || resp.item().isEmpty()) {
            return Optional.empty();
        }
        final AttributeValue content = resp.item().get(ATTR_CONTENT);
        return Optional.ofNullable(content == null ? null : parseChannel(content.s()));
    }

    private Optional<ChatChannel> logChannelReadFailure(final String channelId, final Throwable ex) {
        log.debug("ChatDAO: unable to read channel {}", channelId, ex);
        return Optional.empty();
    }

    // --- membership ---

    protected Boolean saveMembership(final ChatMembership member) {
        if (member == null || member.getChannelId() == null || member.getPersonId() == null) {
            return false;
        }
        final String channelId = member.getChannelId().getValue();
        final String personId = member.getPersonId().getValue();
        final Map<String, AttributeValue> item = new HashMap<>();
        item.put(ATTR_CHANNEL_ID, persistence.toStrAttr(channelId));
        item.put(ATTR_PERSON_ID, persistence.toStrAttr(personId));
        final String json = toJson(member);
        if (json == null) {
            return false;
        }
        item.put(ATTR_CONTENT, persistence.toStrAttr(json));
        try {
            final boolean saved = persistence.putItem(b -> b.tableName(MEMBERS_TABLE).item(item))
                    .sdkHttpResponse().isSuccessful();
            return saved && cacheMembership(channelId, personId, json);
        } catch (final RuntimeException ex) {
            throw ex;
        }
    }

    /** The row is already durable; the cache write is best effort, so its result never fails the save. */
    private boolean cacheMembership(final String channelId, final String personId, final String json) {
        cacheClient.putHashField(CacheKeys.chatMembersKey(channelId), personId, json);
        return true;
    }

    protected Optional<ChatMembership> getMembership(
            final ChatChannel.Id channelId, final Person.Id personId) {
        if (channelId == null || personId == null) {
            return Optional.empty();
        }
        final String cId = channelId.getValue();
        final String pId = personId.getValue();
        try {
            final String cached = cacheClient.getHashFields(CacheKeys.chatMembersKey(cId), List.of(pId)).get(pId);
            final ChatMembership hit = cached == null ? null : parseMembership(cached);
            return
                    hit == null ? pointReadMembership(cId, pId) : Optional.of(hit);
        } catch (final RuntimeException ex) {
            throw ex;
        }
    }

    private Optional<ChatMembership> pointReadMembership(final String channelId, final String personId) {
        final Map<String, AttributeValue> key = Map.of(
                ATTR_CHANNEL_ID, AttributeValue.builder().s(channelId).build(),
                ATTR_PERSON_ID, AttributeValue.builder().s(personId).build());
        try {
            return membershipFrom(channelId, personId,
                    persistence.getItem(b -> b.tableName(MEMBERS_TABLE).key(key).build()));
        } catch (final RuntimeException ex) {
            return logMembershipReadFailure(channelId, personId, ex);
        }
    }

    private Optional<ChatMembership> membershipFrom(
            final String channelId, final String personId, final GetItemResponse resp) {
        if (resp.item() == null || resp.item().isEmpty()) {
            return Optional.empty();
        }
        final AttributeValue content = resp.item().get(ATTR_CONTENT);
        final ChatMembership member = content == null ? null : parseMembership(content.s());
        if (member != null) {
            cacheClient.putHashField(CacheKeys.chatMembersKey(channelId), personId, content.s());
        }
        return Optional.ofNullable(member);
    }

    private Optional<ChatMembership> logMembershipReadFailure(
            final String channelId, final String personId, final Throwable ex) {
        log.debug("ChatDAO: unable to read membership {}/{}", channelId, personId, ex);
        return Optional.empty();
    }

    /**
     * Guest reverse lookup: which channels did this person join by invite? Stored as synthetic rows in
     * {@code chat_members} under PK {@code person:{personId}} with the channel id string in the sort-key
     * attribute — disjoint from real channel partitions because every real channel id is prefixed
     * {@code trip:}/{@code photo:}/{@code support:}. This answers "list my guest chats" with one partition
     * query, keeping the locked no-GSI/no-scan design.
     *
     * <p>These rows are NOT {@link ChatMembership} JSON and must never be parsed as such; only these two
     * methods touch the {@code person:} partitions. Known inert wart: {@code purgeChannel} purges by channel
     * partition and orphans reverse rows — harmless because {@code myChats} skips channels that no longer
     * exist.
     */
    protected Boolean addGuestChannel(final Person.Id personId, final ChatChannel.Id channelId) {
        if (personId == null || channelId == null) {
            return false;
        }
        final Map<String, AttributeValue> item = new HashMap<>();
        item.put(ATTR_CHANNEL_ID, persistence.toStrAttr(guestPartition(personId)));
        item.put(ATTR_PERSON_ID, persistence.toStrAttr(channelId.getValue()));
        try {
            return persistence.putItem(b -> b.tableName(MEMBERS_TABLE).item(item))
                    .sdkHttpResponse().isSuccessful();
        } catch (final RuntimeException ex) {
            log.error("Unable to record guest channel {} for {}", channelId.getValue(), personId.getValue(), ex);
            return false;
        }
    }

    /** The channel ids this person joined as a guest. See {@link #addGuestChannel}. */
    protected List<ChatChannel.Id> listGuestChannelIds(final Person.Id personId) {
        if (personId == null) {
            return List.of();
        }
        final Map<String, String> names = Map.of("#channelId", ATTR_CHANNEL_ID);
        final Map<String, AttributeValue> values =
                Map.of(":c", AttributeValue.builder().s(guestPartition(personId)).build());
        return persistence.queryAll(b -> b.tableName(MEMBERS_TABLE)
                        .keyConditionExpression("#channelId = :c")
                        .expressionAttributeNames(names)
                        .expressionAttributeValues(values)
                        .build())
                .stream()
                .map(item -> item.get(ATTR_PERSON_ID))
                .filter(v -> v != null)
                .map(v -> ChatChannel.Id.from(v.s()))
                .toList();
    }

    /**
     * Removes one guest's reverse-index row for a channel. {@link #purgeChannel} purges by channel partition
     * and cannot see these {@code person:} rows, so a hard channel delete (the trip delete cascade) must call
     * this per guest -- otherwise the rows dangle forever once the channel is gone.
     */
    protected Boolean removeGuestChannel(final Person.Id personId, final ChatChannel.Id channelId) {
        if (personId == null || channelId == null) {
            return false;
        }
        return persistence.deleteItem(b -> b.tableName(MEMBERS_TABLE).key(Map.of(
                        ATTR_CHANNEL_ID, AttributeValue.builder().s(guestPartition(personId)).build(),
                        ATTR_PERSON_ID, AttributeValue.builder().s(channelId.getValue()).build())))
                .sdkHttpResponse().isSuccessful();
    }

    private static String guestPartition(final Person.Id personId) {
        return "person:" + personId.getValue();
    }

    protected List<ChatMembership> listMembers(final ChatChannel.Id channelId) {
        if (channelId == null) {
            return List.of();
        }
        final String cId = channelId.getValue();
        final Map<String, String> names = Map.of("#channelId", ATTR_CHANNEL_ID);
        final Map<String, AttributeValue> values =
                Map.of(":c", AttributeValue.builder().s(cId).build());
        try {
            return persistence.queryAll(b -> b.tableName(MEMBERS_TABLE)
                            .keyConditionExpression("#channelId = :c")
                            .expressionAttributeNames(names)
                            .expressionAttributeValues(values)
                            .build())
                    .stream()
                    .map(item -> item.get(ATTR_CONTENT))
                    .filter(c -> c != null)
                    .map(c -> parseMembership(c.s()))
                    .filter(m -> m != null)
                    .toList();
        } catch (final RuntimeException ex) {
            throw ex;
        }
    }

    // --- messages ---

    /**
     * Allocates the next message id for this JVM. Monotonic and never behind wall-clock; rejects if more than
     * {@link #MAX_DRIFT_MS} ahead (burst limits make that unreachable in practice).
     */
    long nextMillis() {
        final long now = System.currentTimeMillis();
        final long allocated = idClock.updateAndGet(prev -> Math.max(now, prev + 1));
        if (allocated - now > MAX_DRIFT_MS) {
            // Roll the clock back conceptually by refusing; reset toward wall clock so a stuck clock recovers.
            idClock.updateAndGet(prev -> Math.min(prev, now + MAX_DRIFT_MS));
            throw new IllegalStateException(
                    "Chat id allocator drifted more than " + MAX_DRIFT_MS + "ms ahead of wall-clock");
        }
        return allocated;
    }

    /** Test hook: advances the allocator as if wall-clock returned {@code millis}. */
    void forceAllocator(final long millis) {
        idClock.set(millis);
    }

    protected Optional<ChatMessage> saveMessage(
            final ChatMessage draft, final ChatChannel channel, final Trip trip) {
        if (draft == null || draft.getChannelId() == null) {
            return Optional.empty();
        }
        // Idempotency: same clientMessageId from the same author collapses before minting a new server id.
        if (draft.getClientMessageId() != null && !draft.getClientMessageId().isBlank()
                && draft.getAuthorId() != null) {
            final String cmidKey = CacheKeys.chatClientMessageKey(
                    draft.getChannelId().getValue(),
                    draft.getAuthorId().getValue(),
                    draft.getClientMessageId());
            final Optional<String> existing = cacheClient.getValue(cmidKey);
            if (existing.isPresent()) {
                return getMessage(draft.getChannelId(), ChatMessage.Id.from(existing.get()));
            }
        }
        return mintAndSave(draft, channel, trip);
    }

    private Optional<ChatMessage> mintAndSave(
            final ChatMessage draft, final ChatChannel channel, final Trip trip) {
        final long millis = nextMillis();
        final ChatMessage.Id id = ChatMessage.Id.of(millis);
        final Long expiresAt = ChatVisibility.expiresAtEpochSeconds(channel, trip, Instant.ofEpochMilli(millis));
        final ChatMessage withId = draft.withId(id).withExpiresAt(expiresAt);
        return saveMessageWithRetry(withId, channel, 0);
    }

    private Optional<ChatMessage> saveMessageWithRetry(
            final ChatMessage message, final ChatChannel channel, final int attempt) {
        if (attempt >= MAX_WRITE_RETRIES) {
            log.error("Chat message could not find a free key after {} attempts: {}", MAX_WRITE_RETRIES, message);
            return Optional.empty();
        }
        final Map<String, AttributeValue> item = new HashMap<>();
        item.put(ATTR_CHANNEL_ID, persistence.toStrAttr(message.getChannelId().getValue()));
        item.put(ATTR_MSG_ID, persistence.toStrAttr(message.getId().getValue()));
        final String json = toJson(message);
        if (json == null) {
            return Optional.empty();
        }
        item.put(ATTR_CONTENT, persistence.toStrAttr(json));
        if (message.getExpiresAt() != null) {
            item.put(ATTR_EXPIRES_AT, AttributeValue.builder().n(Long.toString(message.getExpiresAt())).build());
        }
        try {
            final boolean saved = persistence.putItem(b -> b.tableName(MESSAGES_TABLE)
                            .item(item)
                            .conditionExpression("attribute_not_exists(#msgId)")
                            .expressionAttributeNames(Map.of("#msgId", ATTR_MSG_ID)))
                    .sdkHttpResponse().isSuccessful();
            return saved ? bufferAndReturn(message, channel) : Optional.empty();
        } catch (final RuntimeException ex) {
            return retryOrFail(message, channel, attempt, ex);
        }
    }

    private Optional<ChatMessage> bufferAndReturn(
            final ChatMessage message, final ChatChannel channel) {
        afterMessageWritten(message, channel);
        return Optional.of(message);
    }

    /**
     * A conditional-check failure means another writer owns this millisecond, so walk forward one and try again.
     * Anything else is a real error and must surface as an empty result, never a silently dropped message.
     */
    private Optional<ChatMessage> retryOrFail(
            final ChatMessage message, final ChatChannel channel, final int attempt, final Throwable ex) {
        if (isConditionalFailure(ex)) {
            return saveMessageWithRetry(message.withId(message.getId().next()), channel, attempt + 1);
        }
        log.warn("Unable to store chat message: {}", message.getId(), ex);
        return Optional.empty();
    }

    private void afterMessageWritten(final ChatMessage message, final ChatChannel channel) {
        final String channelId = message.getChannelId().getValue();
        final String msgId = message.getId().getValue();
        final double score = message.getId().getEpochMilli();
        final int bufferMax = channel == null || channel.getSettings() == null
                ? 300 : channel.getSettings().getBufferMaxMessages();
        final int bufferMinutes = channel == null || channel.getSettings() == null
                ? 60 : channel.getSettings().getBufferMinutes();
        final Duration bufferTtl = Duration.ofMinutes(Math.max(1, bufferMinutes));

        cacheClient.addScoredEntries(CacheKeys.chatLogKey(channelId), Map.of(msgId, score));
        cacheClient.putHashField(CacheKeys.chatBodyKey(channelId), msgId, toJson(message));
        cacheClient.trimSortedSet(CacheKeys.chatLogKey(channelId), bufferMax);
        cacheClient.expire(CacheKeys.chatLogKey(channelId), bufferTtl);
        cacheClient.expire(CacheKeys.chatBodyKey(channelId), bufferTtl);
        // The MESSAGE ID, not System.currentTimeMillis(). Unread is "cursor < lastActivity", and the
        // cursor a client reports is a message id -- so a fresh clock reading here is a different, always
        // later number, and the comparison could never come out equal. The unread dot stayed lit forever,
        // for everyone, even immediately after reading. Same total order as the sort key and the ZSET
        // score, which is the property the whole cursor design rests on.
        // Photo channels are excluded: "My Chats" reads this WHOLE hash per request, and a field per
        // commented photo would grow it without bound for a page that never lists photo threads anyway.
        if (!isPhotoChannel(channelId)) {
            cacheClient.putHashField(CacheKeys.CHAT_LAST_ACTIVITY, channelId, msgId);
        }
        markClientMessageId(message);
        nudge(message);
    }

    private static boolean isPhotoChannel(final String channelId) {
        return channelId != null && channelId.startsWith("photo:");
    }

    /**
     * Tells anyone parked on this channel that there is something new.
     *
     * <p>Last in the chain, deliberately: the buffer and the last-activity marker are already written, so a reader
     * woken by this finds the message rather than racing the writes that make it visible. Best effort -- the send is
     * already durable in DynamoDB, so a failed publish costs real-time latency and nothing else.
     */
    private boolean nudge(final ChatMessage message) {
        final String channelId = message.getChannelId().getValue();
        return cacheClient.publish(
                CacheKeys.chatPubSubChannelFor(channelId),
                ChatNudge.encode(channelId, message.getId().getEpochMilli()));
    }

    /**
     * Records the sender's idempotency key so a retried send collapses onto this message instead of writing a
     * second one. No key (a SYSTEM message, or a client that sent none) means nothing to record.
     */
    private boolean markClientMessageId(final ChatMessage message) {
        if (message.getClientMessageId() == null || message.getAuthorId() == null) {
            return true;
        }
        final String key = CacheKeys.chatClientMessageKey(
                message.getChannelId().getValue(),
                message.getAuthorId().getValue(),
                message.getClientMessageId());
        return cacheClient.putValue(key, message.getId().getValue(), CLIENT_MSG_TTL);
    }

    /**
     * One message, <b>unfiltered</b> — it may be expired or outside the reader's history window. This is the
     * moderation/plumbing read (tombstone a message, resolve an idempotent resend); anything that shows a message
     * to a person must use {@link #getVisibleMessage} instead, or retention stops meaning what the settings say.
     */
    protected Optional<ChatMessage> getMessage(
            final ChatChannel.Id channelId, final ChatMessage.Id msgId) {
        if (channelId == null || msgId == null) {
            return Optional.empty();
        }
        final String cId = channelId.getValue();
        final String mId = msgId.getValue();
        try {
            final String cached = cacheClient.getHashFields(CacheKeys.chatBodyKey(cId), List.of(mId)).get(mId);
            return cachedMessageOrRead(cId, mId, cached);
        } catch (final RuntimeException ex) {
            throw ex;
        }
    }

    private Optional<ChatMessage> cachedMessageOrRead(
            final String cId, final String mId, final String cached) {
        final ChatMessage hit = cached == null ? null : parseMessage(cached);
        if (hit != null) {
            return Optional.of(hit);
        }
        final Map<String, AttributeValue> key = Map.of(
                ATTR_CHANNEL_ID, AttributeValue.builder().s(cId).build(),
                ATTR_MSG_ID, AttributeValue.builder().s(mId).build());
        try {
            return
                    messageFrom(persistence.getItem(b -> b.tableName(MESSAGES_TABLE).key(key).build()));
        } catch (final RuntimeException ex) {
            throw ex;
        }
    }

    private Optional<ChatMessage> messageFrom(final GetItemResponse resp) {
        if (resp.item() == null || resp.item().isEmpty()) {
            return Optional.empty();
        }
        final AttributeValue content = resp.item().get(ATTR_CONTENT);
        return Optional.ofNullable(content == null ? null : parseMessage(content.s()));
    }

    /**
     * One message as a given reader may see it — the same {@link ChatVisibility} rule the poll and the page use,
     * so a single-message read (a quote resolve, a deep link) cannot surface a body the feed would have hidden.
     */
    protected Optional<ChatMessage> getVisibleMessage(
            final ChatChannel.Id channelId,
            final ChatMessage.Id msgId,
            final ChatMembership member,
            final ChatChannel channel,
            final Trip trip,
            final Instant now) {
        final Instant clock = now == null ? Instant.now() : now;
        return getMessage(channelId, msgId)
                .filter(m -> ChatVisibility.isVisible(m, member, channel, trip, clock));
    }

    /**
     * Tombstone: clear body, set deletedAt/deletedBy. Never a Dynamo delete — already-polled clients need a
     * correction event.
     */
    protected Optional<ChatMessage> tombstoneMessage(
            final ChatChannel.Id channelId, final ChatMessage.Id msgId, final String deletedBy) {
        final Optional<ChatMessage> existing = getMessage(channelId, msgId);
        return existing.isEmpty() ? Optional.empty() : writeTombstone(existing.get(), deletedBy);
    }

    private Optional<ChatMessage> writeTombstone(
            final ChatMessage original, final String deletedBy) {
        final ChatMessage tombstoned = original.withDeleted(deletedBy);
        final String json = toJson(tombstoned);
        final Map<String, AttributeValue> item = new HashMap<>();
        item.put(ATTR_CHANNEL_ID, persistence.toStrAttr(tombstoned.getChannelId().getValue()));
        item.put(ATTR_MSG_ID, persistence.toStrAttr(tombstoned.getId().getValue()));
        item.put(ATTR_CONTENT, persistence.toStrAttr(json));
        if (tombstoned.getExpiresAt() != null) {
            item.put(ATTR_EXPIRES_AT,
                    AttributeValue.builder().n(Long.toString(tombstoned.getExpiresAt())).build());
        }
        try {
            final boolean saved = persistence.putItem(b -> b.tableName(MESSAGES_TABLE).item(item))
                    .sdkHttpResponse().isSuccessful();
            return saved ? cacheTombstone(tombstoned, json) : Optional.empty();
        } catch (final RuntimeException ex) {
            throw ex;
        }
    }

    private Optional<ChatMessage> cacheTombstone(
            final ChatMessage tombstoned, final String json) {
        cacheClient.putHashField(CacheKeys.chatBodyKey(tombstoned.getChannelId().getValue()),
                tombstoned.getId().getValue(), json);
        return announceMutation(tombstoned);
    }

    /**
     * Tombstones ONE attachment of a message, leaving the rest (and the body) untouched. Same shape as a
     * message tombstone — never a removal from the list, so an already-polled client is told the photo was
     * withdrawn rather than watching it silently vanish. Empty when the message is gone, tombstoned as a
     * whole, or has no live attachment under that key.
     */
    protected Optional<ChatMessage> removeAttachment(
            final ChatChannel.Id channelId, final ChatMessage.Id msgId,
            final String s3Key, final String deletedBy) {
        final Optional<ChatMessage> existing = getMessage(channelId, msgId);
        if (existing.isEmpty() || existing.get().isDeleted()) {
            return Optional.empty();
        }
        final List<ChatAttachment> replaced = tombstoneOne(existing.get().getAttachments(), s3Key, deletedBy);
        return replaced == null ? Optional.empty() : writeAttachments(existing.get(), replaced);
    }

    /** The list with the matching live attachment tombstoned, or null when nothing matched. */
    private static List<ChatAttachment> tombstoneOne(
            final List<ChatAttachment> attachments, final String s3Key, final String deletedBy) {
        boolean matched = false;
        final List<ChatAttachment> out = new ArrayList<>(attachments.size());
        for (final ChatAttachment attachment : attachments) {
            if (!matched && !attachment.isDeleted() && s3Key.equals(attachment.getS3Key())) {
                matched = true;
                out.add(attachment.deleted(deletedBy));
            } else {
                out.add(attachment);
            }
        }
        return matched ? out : null;
    }

    private Optional<ChatMessage> writeAttachments(
            final ChatMessage original, final List<ChatAttachment> replaced) {
        final ChatMessage updated = original.withAttachments(replaced);
        final String json = toJson(updated);
        if (json == null) {
            return Optional.empty();
        }
        final Map<String, AttributeValue> item = new HashMap<>();
        item.put(ATTR_CHANNEL_ID, persistence.toStrAttr(updated.getChannelId().getValue()));
        item.put(ATTR_MSG_ID, persistence.toStrAttr(updated.getId().getValue()));
        item.put(ATTR_CONTENT, persistence.toStrAttr(json));
        if (updated.getExpiresAt() != null) {
            item.put(ATTR_EXPIRES_AT,
                    AttributeValue.builder().n(Long.toString(updated.getExpiresAt())).build());
        }
        final boolean saved = persistence.putItem(b -> b.tableName(MESSAGES_TABLE).item(item))
                .sdkHttpResponse().isSuccessful();
        return saved ? cacheEdit(updated, json) : Optional.empty();
    }

    /**
     * Replaces a message body in place, stamping {@code editedAt}. Author and window checks live in the action
     * layer; this is the write.
     *
     * <p>The ZSET entry is untouched, so the message keeps its position in the log — an edit must not reorder a
     * conversation. Only the body hash and the Dynamo row change.
     */
    protected Optional<ChatMessage> editMessage(
            final ChatChannel.Id channelId, final ChatMessage.Id msgId, final String newBody) {
        final Optional<ChatMessage> existing = getMessage(channelId, msgId);
        return existing.isEmpty() || existing.get().isDeleted()
                ? Optional.empty() : writeEdit(existing.get(), newBody);
    }

    private Optional<ChatMessage> writeEdit(
            final ChatMessage original, final String newBody) {
        final ChatMessage edited = original.withBody(newBody);
        final String json = toJson(edited);
        if (json == null) {
            return Optional.empty();
        }
        final Map<String, AttributeValue> item = new HashMap<>();
        item.put(ATTR_CHANNEL_ID, persistence.toStrAttr(edited.getChannelId().getValue()));
        item.put(ATTR_MSG_ID, persistence.toStrAttr(edited.getId().getValue()));
        item.put(ATTR_CONTENT, persistence.toStrAttr(json));
        if (edited.getExpiresAt() != null) {
            item.put(ATTR_EXPIRES_AT,
                    AttributeValue.builder().n(Long.toString(edited.getExpiresAt())).build());
        }
        try {
            final boolean saved = persistence.putItem(b -> b.tableName(MESSAGES_TABLE).item(item))
                    .sdkHttpResponse().isSuccessful();
            return saved ? cacheEdit(edited, json) : Optional.empty();
        } catch (final RuntimeException ex) {
            throw ex;
        }
    }

    private Optional<ChatMessage> cacheEdit(final ChatMessage edited, final String json) {
        cacheClient.putHashField(CacheKeys.chatBodyKey(edited.getChannelId().getValue()),
                edited.getId().getValue(), json);
        return announceMutation(edited);
    }

    /**
     * Bumps the mutation counter and nudges, so a change to an already-delivered message actually reaches clients.
     *
     * <p>Without this an edit or a delete was invisible to anyone already holding the message: the id does not
     * change, so the message never reappears on their cursor and their next poll returns nothing. Only a full page
     * reload showed the correction.
     */
    private Optional<ChatMessage> announceMutation(final ChatMessage changed) {
        final String cId = changed.getChannelId().getValue();
        cacheClient.increment(CacheKeys.chatMutationsVersionKey(cId), 1L, CacheKeys.GC_TTL);
        nudgeChannel(cId);
        return Optional.of(changed);
    }

    // --- read cursor (Valkey-authoritative; a lost cursor costs an unread dot, never a message) ---

    protected Boolean saveCursor(
            final ChatChannel.Id channelId, final Person.Id personId, final ChatMessage.Id cursor) {
        if (channelId == null || personId == null || cursor == null) {
            return false;
        }
        return cacheClient.putValue(
                CacheKeys.chatCursorKey(channelId.getValue(), personId.getValue()),
                cursor.getValue(), CacheKeys.GC_TTL);
    }

    protected Optional<ChatMessage.Id> getCursor(
            final ChatChannel.Id channelId, final Person.Id personId) {
        if (channelId == null || personId == null) {
            return Optional.empty();
        }
        return
                cacheClient.getValue(CacheKeys.chatCursorKey(channelId.getValue(), personId.getValue()))
                        .map(ChatMessage.Id::from);
    }

    /** Last-activity millis per channel, for the unread indicator and the "My Chats" sort. */
    protected Map<String, String> lastActivity() {
        return cacheClient.getHash(CacheKeys.CHAT_LAST_ACTIVITY);
    }

    /**
     * Messages after {@code since} (exclusive), oldest first — the poll hot path.
     *
     * <p>The Valkey buffer may only answer this when it is <em>provably complete</em> for the caller's cursor:
     * the buffer holds just the last {@code bufferMaxMessages} / {@code bufferMinutes}, so a client returning
     * after a day would otherwise be served the buffered tail, advance its cursor past the gap, and never see
     * the messages in between. See {@link #bufferCoversCursor}.
     */
    protected ChatPage getMessagesSince(
            final ChatChannel.Id channelId,
            final ChatMessage.Id since,
            final int limit,
            final ChatMembership member,
            final ChatChannel channel,
            final Trip trip,
            final Instant now) {
        final Read read = new Read(channelId.getValue(), since, limit <= 0 ? 200 : Math.min(limit, 200),
                member, channel, trip, now);
        final boolean covered = bufferCoversCursor(read.channelId(), read.sinceMillis());
        return withReactionMeta(channelId, covered ? bufferedSince(read) : dynamoSince(read));
    }

    /**
     * Everything a paged read needs, grouped so the legs below can be separate methods without a seven-argument
     * signature repeated at each hand-off.
     */
    private record Read(
            String channelId,
            ChatMessage.Id since,
            int limit,
            ChatMembership member,
            ChatChannel channel,
            Trip trip,
            Instant now) {

        long sinceMillis() {
            return since == null ? -1L : since.getEpochMilli();
        }

        String sinceValue() {
            return since == null ? null : since.getValue();
        }
    }

    /** The buffer has been proven complete for this cursor, so it may answer the read on its own. */
    private ChatPage bufferedSince(final Read read) {
        final List<String> ids = cacheClient.getRangeByScore(CacheKeys.chatLogKey(read.channelId()),
                read.sinceMillis() + 1, Double.POSITIVE_INFINITY, false, read.limit());
        return ids.isEmpty() ? nothingNew(read) : bufferedBodies(read, ids);
    }

    /**
     * The buffer is complete for this cursor and holds nothing newer, so "nothing new" is an authoritative
     * answer. This is what keeps an idle poll off DynamoDB entirely.
     */
    private ChatPage nothingNew(final Read read) {
        return toPage(List.of(), List.of(), false, OLDEST_FIRST, read.now());
    }

    private ChatPage bufferedBodies(final Read read, final List<String> ids) {
        final List<ChatMessage> msgs = loadBodies(read.channelId(), ids);
        if (msgs.size() == ids.size()) {
            return bufferPage(read, msgs, ids.size() >= read.limit());
        }
        // A body went missing independently of its log entry (the hash and the ZSET have separate
        // lifetimes and only the ZSET is trimmed). Dropping it here would hide the message *and*
        // advance the cursor past it, losing it for good.
        return dynamoSince(read);
    }

    private ChatPage bufferPage(final Read read, final List<ChatMessage> msgs, final boolean hasMore) {
        return toPage(filterVisible(msgs, read.member(), read.channel(), read.trip(), read.now()), msgs,
                hasMore, OLDEST_FIRST, read.now());
    }

    private ChatPage dynamoSince(final Read read) {
        return dynamoPage(read, queryMessages(read.channelId(), read.sinceValue(), null, true, read.limit()));
    }

    private ChatPage dynamoPage(final Read read, final List<ChatMessage> msgs) {
        repopulateBuffer(read.channelId(), msgs, read.channel());
        return bufferPage(read, msgs, msgs.size() >= read.limit());
    }

    /**
     * Whether the hot buffer can be trusted to hold <em>every</em> message after {@code sinceMillis} — true only
     * when its oldest entry is at or below the cursor, so there is no gap between them. An empty or expired
     * buffer is never covering, and neither is an initial load ({@code sinceMillis < 0}), which must come from
     * the durable store.
     */
    private boolean bufferCoversCursor(final String cId, final long sinceMillis) {
        if (sinceMillis < 0) {
            return false;
        }
        final List<String> oldest = cacheClient.getRangeByScore(
                CacheKeys.chatLogKey(cId), Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, false, 1);
        return !oldest.isEmpty() && ChatMessage.Id.from(oldest.get(0)).getEpochMilli() <= sinceMillis + 1;
    }

    /**
     * Newest-first page for initial load / "load older". {@code before} exclusive upper bound (null = latest).
     * The returned {@link ChatPage#getCursor()} is the <em>oldest</em> id examined — pass it back as
     * {@code before} to page further back. It is not a poll cursor; {@code newestFirst} on the page says which.
     */
    protected ChatPage getMessagesBefore(
            final ChatChannel.Id channelId,
            final ChatMessage.Id before,
            final int limit,
            final ChatMembership member,
            final ChatChannel channel,
            final Trip trip,
            final Instant now) {
        final int capped = limit <= 0 ? 50 : Math.min(limit, 50);
        final String cId = channelId.getValue();
        final List<ChatMessage> msgs =
                queryMessages(cId, null, before == null ? null : before.getValue(), false, capped);
        final ChatPage page = toPage(filterVisible(msgs, member, channel, trip, now), msgs,
                msgs.size() >= capped, NEWEST_FIRST, now);
        return withReactionMeta(channelId, page);
    }

    /**
     * A newest-first page of raw rows, <b>unfiltered</b> by visibility — plumbing, not display. Exists for
     * photo-channel parent resolution, which must find the message that carried a photo even when the trip
     * channel's history settings would hide it from the resolving reader.
     */
    protected List<ChatMessage> rawMessagesBefore(
            final ChatChannel.Id channelId, final ChatMessage.Id before, final int limit) {
        if (channelId == null) {
            return List.of();
        }
        return queryMessages(channelId.getValue(), null, before == null ? null : before.getValue(),
                false, limit <= 0 ? 200 : Math.min(limit, 200));
    }

    private List<ChatMessage> queryMessages(
            final String channelId,
            final String afterExclusive,
            final String beforeExclusive,
            final boolean forward,
            final int limit) {
        final Map<String, String> names = new HashMap<>();
        names.put("#channelId", ATTR_CHANNEL_ID);
        final Map<String, AttributeValue> values = new HashMap<>();
        values.put(":c", AttributeValue.builder().s(channelId).build());
        final StringBuilder cond = new StringBuilder("#channelId = :c");
        if (afterExclusive != null) {
            names.put("#msgId", ATTR_MSG_ID);
            cond.append(" AND #msgId > :after");
            values.put(":after", AttributeValue.builder().s(afterExclusive).build());
        }
        if (beforeExclusive != null) {
            names.put("#msgId", ATTR_MSG_ID);
            cond.append(" AND #msgId < :before");
            values.put(":before", AttributeValue.builder().s(beforeExclusive).build());
        }
        // query(), NOT queryAll(): DynamoDB's limit caps one page, so a paginating read walks the whole
        // partition and only then truncates in memory -- every page load would read the channel's entire
        // history. A message log grows without bound, so this read must stay one bounded page.
        try {
            return toMessages(
                    persistence.query(b -> b.tableName(MESSAGES_TABLE)
                            .keyConditionExpression(cond.toString())
                            .expressionAttributeNames(names)
                            .expressionAttributeValues(values)
                            .scanIndexForward(forward)
                            .limit(limit)).items(),
                    limit);
        } catch (final RuntimeException ex) {
            throw ex;
        }
    }

    private List<ChatMessage> toMessages(final List<Map<String, AttributeValue>> items, final int limit) {
        final List<ChatMessage> out = new ArrayList<>();
        for (final Map<String, AttributeValue> item : items) {
            if (out.size() >= limit) {
                break;
            }
            final AttributeValue content = item.get(ATTR_CONTENT);
            if (content != null) {
                final ChatMessage m = parseMessage(content.s());
                if (m != null) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    private List<ChatMessage> loadBodies(final String channelId, final List<String> ids) {
        return parseBodies(ids, cacheClient.getHashFields(CacheKeys.chatBodyKey(channelId), ids));
    }

    /**
     * Parses bodies in the log's order. A short result means the buffer is missing a body for an id it still
     * lists; the caller must treat that as a miss rather than a shorter page.
     */
    private List<ChatMessage> parseBodies(final List<String> ids, final Map<String, String> bodies) {
        final List<ChatMessage> out = new ArrayList<>();
        for (final String id : ids) {
            final String json = bodies.get(id);
            if (json != null) {
                final ChatMessage m = parseMessage(json);
                if (m != null) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    private void repopulateBuffer(final String channelId, final List<ChatMessage> msgs, final ChatChannel channel) {
        if (msgs.isEmpty()) {
            return;
        }
        final Map<String, Double> scores = new HashMap<>();
        final Map<String, String> bodies = new HashMap<>();
        for (final ChatMessage m : msgs) {
            scores.put(m.getId().getValue(), (double) m.getId().getEpochMilli());
            final String json = toJson(m);
            if (json != null) {
                bodies.put(m.getId().getValue(), json);
            }
        }
        cacheClient.addScoredEntries(CacheKeys.chatLogKey(channelId), scores);
        cacheClient.putHashFields(CacheKeys.chatBodyKey(channelId), bodies);
    }

    private List<ChatMessage> filterVisible(
            final List<ChatMessage> msgs,
            final ChatMembership member,
            final ChatChannel channel,
            final Trip trip,
            final Instant now) {
        final Instant clock = now == null ? Instant.now() : now;
        final List<ChatMessage> out = new ArrayList<>();
        for (final ChatMessage m : msgs) {
            if (ChatVisibility.isVisible(m, member, channel, trip, clock)) {
                out.add(m);
            }
        }
        return out;
    }

    /**
     * Builds a page. The cursor comes from {@code examined} (everything read from the store), <b>not</b> from
     * {@code visible} (what survived the filter). Taking it from the visible list means an invisible message at
     * the tail of a page leaves the cursor behind it, so the next poll re-reads the same rows and never advances
     * — a permanent spin that appears whenever retention expires mid-page or history is limited by join date.
     */
    private ChatPage toPage(final List<ChatMessage> visible, final List<ChatMessage> examined,
            final boolean hasMore, final boolean newestFirst, final Instant now) {
        final ChatMessage.Id cursor = examined.isEmpty()
                ? null : examined.get(examined.size() - 1).getId();
        return new ChatPage(visible, Map.of(), cursor, 0L, 0L, hasMore, newestFirst, Map.of(),
                now == null ? Instant.now() : now);
    }

    // --- reactions ---

    /**
     * Attaches reaction summaries and the channel's reaction version to a page.
     *
     * <p>The version is read even for an empty page, and that is deliberate: a reaction does not create a message,
     * so a woken long-poll finds nothing new on the message cursor and would return an empty page. If that page
     * carried no version, a reaction would never reach a client that already had the message it was attached to —
     * real-time reactions would silently not work while every individual piece looked correct.
     */
    private ChatPage withReactionMeta(final ChatChannel.Id channelId, final ChatPage page) {
        return withMutationsVersion(channelId, page, reactionsVersion(channelId.getValue()));
    }

    private ChatPage withMutationsVersion(
            final ChatChannel.Id channelId, final ChatPage page, final long reactionsVersion) {
        final Long mutations = mutationsVersion(channelId.getValue());
        return page.withReactions(summariesForPage(channelId, page), reactionsVersion, mutations);
    }

    private Map<ChatMessage.Id, ChatReactionSummary> summariesForPage(
            final ChatChannel.Id channelId, final ChatPage page) {
        // Only the VISIBLE messages: reactions inherit their target's visibility, so summarising a message the
        // reader may not see would leak that it exists (and who engaged with it) through the reaction chips.
        return cachedSummaries(channelId, page.getMessages());
    }

    /**
     * The channel's reaction counter. Zero when the cache is cold or down — see {@link ChatPage#getReactionsVersion}
     * for why zero must mean "not reported" rather than "no reactions".
     */
    private Long reactionsVersion(final String channelId) {
        return toVersion(cacheClient.getValue(CacheKeys.chatReactionsVersionKey(channelId)));
    }

    private Long mutationsVersion(final String channelId) {
        return toVersion(cacheClient.getValue(CacheKeys.chatMutationsVersionKey(channelId)));
    }

    private static Long toVersion(final Optional<String> raw) {
        if (raw.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.get().trim());
        } catch (final NumberFormatException ex) {
            return 0L;
        }
    }

    /**
     * Summaries for a page, served from the {@code rsum} hash and rebuilt from DynamoDB on a miss.
     *
     * <p>This cache is a <em>derived view of an authoritative store</em>, which is what makes it safe without a
     * lock: DynamoDB holds the reaction rows, so a racing refresh costs at most a briefly stale count that the next
     * read heals, and a flush costs a rebuild rather than correctness.
     *
     * <p><b>Messages with no reactions are cached too</b>, as empty summaries. Caching only the messages that have
     * reactions would mean any page containing a single reaction-free message missed on every read and re-queried
     * the whole range forever — which is most pages, so the cache would do nothing.
     */
    private Map<ChatMessage.Id, ChatReactionSummary> cachedSummaries(
            final ChatChannel.Id channelId, final List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return Map.of();
        }
        final List<String> fields = new ArrayList<>(messages.size());
        for (final ChatMessage m : messages) {
            fields.add(m.getId().getValue());
        }
        return summariesFromCacheOrStore(channelId, messages,
                cacheClient.getHashFields(CacheKeys.chatReactionSummaryKey(channelId.getValue()), fields));
    }

    private Map<ChatMessage.Id, ChatReactionSummary> summariesFromCacheOrStore(
            final ChatChannel.Id channelId,
            final List<ChatMessage> messages,
            final Map<String, String> cached) {
        final Map<ChatMessage.Id, ChatReactionSummary> hit = parseSummaries(messages, cached);
        if (hit != null) {
            return hit;
        }
        // Any miss rebuilds the whole page's range rather than the missing ids one at a time: the range is a single
        // Query either way, so one round trip is strictly cheaper than N and repopulates the neighbours as well.
        return cacheSummaries(channelId, summariesForMessages(channelId, messages));
    }

    /** Parsed summaries when every message was cached, or {@code null} when the page must be rebuilt. */
    private Map<ChatMessage.Id, ChatReactionSummary> parseSummaries(
            final List<ChatMessage> messages, final Map<String, String> cached) {
        if (cached == null || cached.size() < messages.size()) {
            return null;
        }
        final Map<ChatMessage.Id, ChatReactionSummary> out = new HashMap<>();
        for (final ChatMessage m : messages) {
            final String json = cached.get(m.getId().getValue());
            final ChatReactionSummary summary = json == null ? null : parse(json, ChatReactionSummary.class);
            if (summary == null) {
                return null;
            }
            out.put(m.getId(), summary);
        }
        return out;
    }

    private Map<ChatMessage.Id, ChatReactionSummary> cacheSummaries(
            final ChatChannel.Id channelId, final Map<ChatMessage.Id, ChatReactionSummary> summaries) {
        final Map<String, String> fields = new HashMap<>();
        for (final Map.Entry<ChatMessage.Id, ChatReactionSummary> e : summaries.entrySet()) {
            final String json = toJson(e.getValue());
            if (json != null) {
                fields.put(e.getKey().getValue(), json);
            }
        }
        if (fields.isEmpty()) {
            return summaries;
        }
        final String key = CacheKeys.chatReactionSummaryKey(channelId.getValue());
        // Best effort: a cache write that fails degrades to a rebuild on the next read. GC_TTL bounds a
        // hash for a channel nobody reads any more.
        cacheClient.putHashFields(key, fields);
        cacheClient.expire(key, CacheKeys.GC_TTL);
        return summaries;
    }

    protected Boolean putReaction(final ChatReaction reaction) {
        if (reaction == null || reaction.getChannelId() == null || reaction.getTargetMessageId() == null
                || reaction.getPersonId() == null || reaction.getEmoji() == null) {
            return false;
        }
        final Map<String, AttributeValue> item = new HashMap<>();
        item.put(ATTR_CHANNEL_ID, persistence.toStrAttr(reaction.getChannelId().getValue()));
        item.put(ATTR_SK, persistence.toStrAttr(reaction.sortKey()));
        final String json = toJson(reaction);
        if (json == null) {
            return false;
        }
        item.put(ATTR_CONTENT, persistence.toStrAttr(json));
        if (reaction.getExpiresAt() != null) {
            item.put(ATTR_EXPIRES_AT,
                    AttributeValue.builder().n(Long.toString(reaction.getExpiresAt())).build());
        }
        try {
            final boolean saved = persistence.putItem(b -> b.tableName(REACTIONS_TABLE).item(item))
                    .sdkHttpResponse().isSuccessful();
            return afterReactionWritten(saved, reaction.getChannelId(), reaction.getTargetMessageId());
        } catch (final RuntimeException ex) {
            throw ex;
        }
    }

    protected Boolean deleteReaction(
            final ChatChannel.Id channelId,
            final ChatMessage.Id targetMessageId,
            final Person.Id personId,
            final String emoji) {
        final Map<String, AttributeValue> key = Map.of(
                ATTR_CHANNEL_ID, AttributeValue.builder().s(channelId.getValue()).build(),
                ATTR_SK, AttributeValue.builder()
                        .s(ChatReaction.sortKey(targetMessageId, personId, emoji)).build());
        try {
            final boolean deleted = persistence.deleteItem(b -> b.tableName(REACTIONS_TABLE).key(key))
                    .sdkHttpResponse().isSuccessful();
            return afterReactionWritten(deleted, channelId, targetMessageId);
        } catch (final RuntimeException ex) {
            throw ex;
        }
    }

    /**
     * Post-write bookkeeping shared by add and remove: drop the stale summary, bump the version, nudge.
     *
     * <p>Order matters. The summary field is dropped <em>before</em> the version is published, so a client that
     * refetches the instant it sees the new version cannot be served the pre-write summary it was told to replace.
     * The nudge goes last, for the same reason it does on the message path: whoever it wakes must already be able
     * to read the change it is telling them about.
     */
    private Boolean afterReactionWritten(
            final boolean ok, final ChatChannel.Id channelId, final ChatMessage.Id targetMessageId) {
        if (!ok) {
            return false;
        }
        final String cId = channelId.getValue();
        cacheClient.removeHashField(CacheKeys.chatReactionSummaryKey(cId), targetMessageId.getValue());
        bumpReactionsVersion(cId);
        nudgeChannel(cId);
        return true;
    }

    private Optional<Long> bumpReactionsVersion(final String channelId) {
        return cacheClient.increment(CacheKeys.chatReactionsVersionKey(channelId), 1L, CacheKeys.GC_TTL);
    }

    /**
     * Wakes parked readers for a change that is not a new message — a reaction, an edit, or a tombstone.
     *
     * <p>The watermark is 0 because none of these has a message id to advance a cursor to. That is harmless: the
     * nudge only means "look again", and a woken reader re-reads its cursor, its summaries and its versions anyway.
     */
    private boolean nudgeChannel(final String channelId) {
        return cacheClient.publish(
                CacheKeys.chatPubSubChannelFor(channelId), ChatNudge.encode(channelId, 0L));
    }

    /** Summaries for an explicit message-id window, for a client refetching after the version changed. */
    protected Map<ChatMessage.Id, ChatReactionSummary> summariesForWindow(
            final ChatChannel.Id channelId,
            final ChatMessage.Id oldest,
            final ChatMessage.Id newest) {
        if (channelId == null || oldest == null || newest == null || oldest.compareTo(newest) > 0) {
            return Map.of();
        }
        return foldWindow(channelId, oldest, newest,
                summarizeByTarget(getReactionsForRange(channelId, oldest, newest)));
    }

    /**
     * Folds photo reactions into a window refetch. The window read has no message list, but the fold needs
     * the attachments, so the window's rows are read here (bounded pages — a window is what one client has
     * on screen). Entries whose folded summary stays empty remain absent, preserving the window contract
     * ("absent = no reactions").
     */
    private Map<ChatMessage.Id, ChatReactionSummary> foldWindow(
            final ChatChannel.Id channelId,
            final ChatMessage.Id oldest,
            final ChatMessage.Id newest,
            final Map<ChatMessage.Id, ChatReactionSummary> direct) {
        if (channelId.tripIdOrNull() == null) {
            return direct;
        }
        final Map<ChatMessage.Id, List<String>> keysByMsg =
                attachmentKeysByMessage(windowMessages(channelId, oldest, newest));
        if (keysByMsg.isEmpty()) {
            return direct;
        }
        final Map<String, PhotoChatMeta> meta = photoMeta(distinctKeys(keysByMsg));
        for (final Map.Entry<ChatMessage.Id, List<String>> e : keysByMsg.entrySet()) {
            final ChatReactionSummary base =
                    direct.getOrDefault(e.getKey(), ChatReactionSummary.empty(e.getKey()));
            final ChatReactionSummary folded =
                    ChatReactionSummary.foldCounts(base, rootSummaries(meta, e.getValue()));
            if (folded.totalCount() > 0) {
                direct.put(e.getKey(), folded);
            }
        }
        return direct;
    }

    /** The window's message rows, inclusive on both ends, read in bounded pages. */
    private List<ChatMessage> windowMessages(
            final ChatChannel.Id channelId, final ChatMessage.Id oldest, final ChatMessage.Id newest) {
        final List<ChatMessage> out = new ArrayList<>();
        String after = ChatMessage.Id.of(Math.max(0L, oldest.getEpochMilli() - 1)).getValue();
        final String before = newest.next().getValue();
        for (int page = 0; page < 5; page++) {
            final List<ChatMessage> batch = queryMessages(channelId.getValue(), after, before, true, 200);
            out.addAll(batch);
            if (batch.size() < 200) {
                return out;
            }
            after = batch.get(batch.size() - 1).getId().getValue();
        }
        // A five-page window means the client asked about ~1000 on-screen messages; fold what was read
        // rather than walking an arbitrarily wide range.
        log.warn("Chat reaction window unusually large; photo fold truncated at {} messages for {}",
                out.size(), channelId.getValue());
        return out;
    }

    /**
     * Folds loose reaction rows by target, for the window read where there is no message list to key off. Only
     * messages that actually have reactions appear, which is what a refetching client needs: an absent entry means
     * "no reactions", and it already knows which messages it is asking about.
     */
    private static Map<ChatMessage.Id, ChatReactionSummary> summarizeByTarget(final List<ChatReaction> reactions) {
        final Map<ChatMessage.Id, List<ChatReaction>> byMsg = new HashMap<>();
        for (final ChatReaction r : reactions) {
            byMsg.computeIfAbsent(r.getTargetMessageId(), k -> new ArrayList<>()).add(r);
        }
        final Map<ChatMessage.Id, ChatReactionSummary> out = new HashMap<>();
        for (final Map.Entry<ChatMessage.Id, List<ChatReaction>> e : byMsg.entrySet()) {
            out.put(e.getKey(), ChatReactionSummary.fromReactions(e.getKey(), e.getValue()));
        }
        return out;
    }

    /** The current reaction version, for a client that wants it without reading a page. */
    protected Long currentReactionsVersion(final ChatChannel.Id channelId) {
        return reactionsVersion(channelId.getValue());
    }

    /**
     * Every reaction on a contiguous range of messages, in one Query.
     *
     * <p>This is the read the row-per-reaction design buys: because {@code msgId} is a fixed-width 13-digit number,
     * the reactions for a message range are themselves contiguous in the sort key, so a whole page costs one round
     * trip rather than one per message.
     *
     * <p><b>{@code queryAll} is deliberate here</b>, despite the rule that it must not be used on an
     * unbounded-growth partition. It paginates until the <em>key range</em> is exhausted, and this range is bounded
     * by the caller's page — so it is bounded by (messages per page × reactions per message), not by the channel's
     * lifetime. A bounded {@code query()} would be actively wrong: a truncated result silently undercounts, and a
     * reaction chip that reads "2" when three people reacted is indistinguishable from correct.
     */
    protected List<ChatReaction> getReactionsForRange(
            final ChatChannel.Id channelId,
            final ChatMessage.Id oldest,
            final ChatMessage.Id newest) {
        if (channelId == null || oldest == null || newest == null) {
            return List.of();
        }
        final Map<String, String> names = Map.of("#channelId", ATTR_CHANNEL_ID, "#sk", ATTR_SK);
        final Map<String, AttributeValue> values = Map.of(
                ":c", AttributeValue.builder().s(channelId.getValue()).build(),
                ":lo", AttributeValue.builder().s(ChatReaction.rangeLower(oldest)).build(),
                ":hi", AttributeValue.builder().s(ChatReaction.rangeUpper(newest)).build());
        try {
            return persistence.queryAll(b -> b.tableName(REACTIONS_TABLE)
                            .keyConditionExpression("#channelId = :c AND #sk BETWEEN :lo AND :hi")
                            .expressionAttributeNames(names)
                            .expressionAttributeValues(values)
                            .build())
                    .stream()
                    .map(item -> item.get(ATTR_CONTENT))
                    .filter(c -> c != null)
                    .map(c -> parseReaction(c.s()))
                    .filter(r -> r != null)
                    .toList();
        } catch (final RuntimeException ex) {
            throw ex;
        }
    }

    protected Map<ChatMessage.Id, ChatReactionSummary> summariesForMessages(
            final ChatChannel.Id channelId, final List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return Map.of();
        }
        ChatMessage.Id oldest = messages.get(0).getId();
        ChatMessage.Id newest = messages.get(0).getId();
        for (final ChatMessage m : messages) {
            if (m.getId().compareTo(oldest) < 0) {
                oldest = m.getId();
            }
            if (m.getId().compareTo(newest) > 0) {
                newest = m.getId();
            }
        }
        return summarize(channelId, messages, getReactionsForRange(channelId, oldest, newest));
    }

    /** Folds a page's reaction rows into one summary per message — every message gets an entry, even if empty. */
    private Map<ChatMessage.Id, ChatReactionSummary> summarize(
            final ChatChannel.Id channelId, final List<ChatMessage> messages, final List<ChatReaction> reactions) {
        final Map<ChatMessage.Id, List<ChatReaction>> byMsg = new HashMap<>();
        for (final ChatReaction r : reactions) {
            byMsg.computeIfAbsent(r.getTargetMessageId(), k -> new ArrayList<>()).add(r);
        }
        final Map<ChatMessage.Id, ChatReactionSummary> out = new HashMap<>();
        for (final ChatMessage m : messages) {
            out.put(m.getId(), ChatReactionSummary.fromReactions(
                    m.getId(), byMsg.getOrDefault(m.getId(), List.of())));
        }
        return foldPhotoReactions(channelId, attachmentKeysByMessage(messages), out);
    }

    /**
     * Folds each photo's own reactions into the summary of the message that carried it — trip channels only
     * (the {@code tripIdOrNull} guard also keeps a photo channel from folding recursively). Photo reactions
     * live in the photo's channel, so without this fold a reaction on a photo never reaches the chip on the
     * message. The counts land in {@code overflowCount} (SUM semantics; see
     * {@link ChatReactionSummary#foldCounts}), and because the folded result is what
     * {@link #cacheSummaries} stores, the existing drop-field/bump/nudge contract delivers photo-reaction
     * updates to chat clients with no client protocol change.
     */
    private Map<ChatMessage.Id, ChatReactionSummary> foldPhotoReactions(
            final ChatChannel.Id channelId,
            final Map<ChatMessage.Id, List<String>> keysByMsg,
            final Map<ChatMessage.Id, ChatReactionSummary> summaries) {
        if (channelId == null || channelId.tripIdOrNull() == null || keysByMsg.isEmpty()) {
            return summaries;
        }
        final Map<String, PhotoChatMeta> meta = photoMeta(distinctKeys(keysByMsg));
        for (final Map.Entry<ChatMessage.Id, List<String>> e : keysByMsg.entrySet()) {
            final ChatReactionSummary direct = summaries.get(e.getKey());
            if (direct != null) {
                summaries.put(e.getKey(),
                        ChatReactionSummary.foldCounts(direct, rootSummaries(meta, e.getValue())));
            }
        }
        return summaries;
    }

    private static Map<ChatMessage.Id, List<String>> attachmentKeysByMessage(final List<ChatMessage> messages) {
        final Map<ChatMessage.Id, List<String>> out = new HashMap<>();
        for (final ChatMessage m : messages) {
            for (final ChatAttachment a : m.getAttachments()) {
                if (a != null && a.getS3Key() != null) {
                    out.computeIfAbsent(m.getId(), k -> new ArrayList<>()).add(a.getS3Key());
                }
            }
        }
        return out;
    }

    private static List<String> distinctKeys(final Map<ChatMessage.Id, List<String>> keysByMsg) {
        final List<String> out = new ArrayList<>();
        for (final List<String> keys : keysByMsg.values()) {
            for (final String key : keys) {
                if (!out.contains(key)) {
                    out.add(key);
                }
            }
        }
        return out;
    }

    private static List<ChatReactionSummary> rootSummaries(
            final Map<String, PhotoChatMeta> meta, final List<String> keys) {
        final List<ChatReactionSummary> out = new ArrayList<>();
        for (final String key : keys) {
            final PhotoChatMeta m = meta.get(key);
            if (m != null) {
                out.add(m.getRootReactions());
            }
        }
        return out;
    }

    // --- photo channels (per-image comment threads; channel id shape photo:{s3Key}) ---

    /**
     * Batch per-photo meta (comment count + the photo's own reactions), cache-first with per-key rebuild.
     * Same derived-view safety argument as the reaction summaries: DynamoDB is authoritative, so a racing
     * rebuild costs at most a briefly stale count that the next write's field-drop heals.
     */
    protected Map<String, PhotoChatMeta> photoMeta(final List<String> s3Keys) {
        if (s3Keys == null || s3Keys.isEmpty()) {
            return Map.of();
        }
        final Map<String, String> cached = cacheClient.getHashFields(CacheKeys.chatPhotoMetaKey(), s3Keys);
        final Map<String, PhotoChatMeta> out = new HashMap<>();
        final Map<String, String> rebuilt = new HashMap<>();
        for (final String key : s3Keys) {
            final PhotoChatMeta hit = parse(cached.get(key), PhotoChatMeta.class);
            out.put(key, hit == null ? rebuildPhotoMeta(key, rebuilt) : hit);
        }
        if (!rebuilt.isEmpty()) {
            cacheClient.putHashFields(CacheKeys.chatPhotoMetaKey(), rebuilt);
            cacheClient.expire(CacheKeys.chatPhotoMetaKey(), CacheKeys.GC_TTL);
        }
        return out;
    }

    private PhotoChatMeta rebuildPhotoMeta(final String s3Key, final Map<String, String> writeBack) {
        final ChatChannel.Id photoId = ChatChannel.Id.forPhoto(s3Key);
        final PhotoChatMeta meta = new PhotoChatMeta(
                countComments(photoId),
                ChatReactionSummary.fromReactions(PhotoChatMeta.PHOTO_ROOT,
                        getReactionsForRange(photoId, PhotoChatMeta.PHOTO_ROOT, PhotoChatMeta.PHOTO_ROOT)));
        final String json = toJson(meta);
        if (json != null) {
            writeBack.put(s3Key, json);
        }
        return meta;
    }

    /**
     * Non-deleted comments in a photo thread. {@code queryAll} is safe here for the same reason it is on the
     * reaction range: a single photo's thread is humanly bounded, not an unbounded-growth partition.
     */
    private int countComments(final ChatChannel.Id photoChannelId) {
        final Map<String, String> names = Map.of("#channelId", ATTR_CHANNEL_ID);
        final Map<String, AttributeValue> values =
                Map.of(":c", AttributeValue.builder().s(photoChannelId.getValue()).build());
        final List<Map<String, AttributeValue>> rows = persistence.queryAll(b -> b.tableName(MESSAGES_TABLE)
                .keyConditionExpression("#channelId = :c")
                .expressionAttributeNames(names)
                .expressionAttributeValues(values)
                .build());
        int count = 0;
        for (final Map<String, AttributeValue> row : rows) {
            final AttributeValue content = row.get(ATTR_CONTENT);
            final ChatMessage m = content == null ? null : parseMessage(content.s());
            if (m != null && !m.isDeleted()) {
                count++;
            }
        }
        return count;
    }

    /** Drops one photo's cached meta so the next read rebuilds it. Best effort, like every field drop. */
    protected Boolean invalidatePhotoMeta(final String s3Key) {
        if (s3Key == null || s3Key.isBlank()) {
            return false;
        }
        cacheClient.removeHashField(CacheKeys.chatPhotoMetaKey(), s3Key);
        return true;
    }

    /**
     * After a photo-thread write, invalidates the parent message's folded summary and tells the trip channel's
     * clients to refetch — same drop-field / bump-version / nudge order as {@link #afterReactionWritten}, aimed
     * at the parent. No parent recorded (pre-chat photo, or the message expired) means nothing to roll up.
     */
    protected Boolean rollupToParent(final ChatChannel photoChannel) {
        if (photoChannel == null || photoChannel.getParentChannelId() == null
                || photoChannel.getParentMsgId() == null) {
            return false;
        }
        final String parentId = photoChannel.getParentChannelId().getValue();
        cacheClient.removeHashField(
                CacheKeys.chatReactionSummaryKey(parentId), photoChannel.getParentMsgId().getValue());
        bumpReactionsVersion(parentId);
        nudgeChannel(parentId);
        return true;
    }

    /**
     * Removes a channel and everything under it — rows in all four tables plus every cache key. Built for the
     * photo delete cascade but channel-shape agnostic, and idempotent: deleting absent rows succeeds, so a
     * replayed cascade is harmless. Returns the channel as it was, because the caller rolls up to its parent.
     *
     * <p>{@code cmid:} idempotency keys are per (channel, person, clientMessageId) and not enumerable, so they
     * are left to their 24 h TTL — a resend collapsing onto a purged message id resolves to an empty read.
     */
    protected Optional<ChatChannel> purgeChannel(final ChatChannel.Id id) {
        if (id == null) {
            return Optional.empty();
        }
        final Optional<ChatChannel> channel = getChannel(id);
        final String cId = id.getValue();
        purgeRows(MESSAGES_TABLE, ATTR_MSG_ID, cId);
        purgeRows(REACTIONS_TABLE, ATTR_SK, cId);
        purgeRows(MEMBERS_TABLE, ATTR_PERSON_ID, cId);
        persistence.deleteItem(b -> b.tableName(CHANNELS_TABLE)
                .key(Map.of(ATTR_CHANNEL_ID, AttributeValue.builder().s(cId).build())));
        channelCache.remove(cId);
        cacheClient.removeKey(CacheKeys.chatLogKey(cId));
        cacheClient.removeKey(CacheKeys.chatBodyKey(cId));
        cacheClient.removeKey(CacheKeys.chatMembersKey(cId));
        cacheClient.removeKey(CacheKeys.chatReactionSummaryKey(cId));
        cacheClient.removeKey(CacheKeys.chatReactionsVersionKey(cId));
        cacheClient.removeKey(CacheKeys.chatMutationsVersionKey(cId));
        cacheClient.removeHashField(CacheKeys.CHAT_LAST_ACTIVITY, cId);
        final String photoKey = id.photoKeyOrNull();
        if (photoKey != null) {
            invalidatePhotoMeta(photoKey);
        }
        return channel;
    }

    private void purgeRows(final String table, final String sortAttr, final String channelId) {
        final Map<String, String> names = Map.of("#channelId", ATTR_CHANNEL_ID);
        final Map<String, AttributeValue> values =
                Map.of(":c", AttributeValue.builder().s(channelId).build());
        final List<Map<String, AttributeValue>> rows = persistence.queryAll(b -> b.tableName(table)
                .keyConditionExpression("#channelId = :c")
                .expressionAttributeNames(names)
                .expressionAttributeValues(values)
                .build());
        for (final Map<String, AttributeValue> row : rows) {
            final AttributeValue sort = row.get(sortAttr);
            if (sort != null) {
                persistence.deleteItem(b -> b.tableName(table).key(Map.of(
                        ATTR_CHANNEL_ID, AttributeValue.builder().s(channelId).build(),
                        sortAttr, sort)));
            }
        }
    }

    // --- parse/serialize ---

    private static boolean isConditionalFailure(final Throwable ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof ConditionalCheckFailedException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private String toJson(final Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (final IOException ex) {
            log.error("Unable to serialize {}", value == null ? null : value.getClass().getSimpleName(), ex);
            return null;
        }
    }

    private ChatChannel parseChannel(final String json) {
        return parse(json, ChatChannel.class);
    }

    private ChatMembership parseMembership(final String json) {
        return parse(json, ChatMembership.class);
    }

    private ChatMessage parseMessage(final String json) {
        return parse(json, ChatMessage.class);
    }

    private ChatReaction parseReaction(final String json) {
        return parse(json, ChatReaction.class);
    }

    private <T> T parse(final String json, final Class<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, type);
        } catch (final IOException ex) {
            log.error("Unable to parse {}: {}", type.getSimpleName(), json, ex);
            return null;
        }
    }
}
