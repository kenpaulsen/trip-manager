package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.chat.ChatNotifications;
import org.paulsens.trip.chat.ChatRateLimiter;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatAttachment;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatEmoji;
import org.paulsens.trip.model.chat.ChatMentions;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatPage;
import org.paulsens.trip.model.chat.ChatReaction;
import org.paulsens.trip.model.chat.ChatReactionSummary;
import org.paulsens.trip.model.chat.ChatSettings;
import org.paulsens.trip.model.chat.PhotoChatMeta;

/**
 * Per-photo comment threads and image reactions. A photo's thread is an ordinary chat channel whose id is
 * {@code photo:{s3Key}} — the s3Key is the one identity both a {@code ChatAttachment} and its {@code MediaItem}
 * album row share, and it embeds the trip ({@code chat/{tripId}/…}), which is what authorization scopes on.
 *
 * <p>Authorization here deliberately differs from trip chat (user decisions 2026-08-09): READING follows the
 * photo — anonymous is allowed unless the photo is {@code hidden} (the bytes are public-by-URL on the CDN
 * already); POSTING requires only a signed-in user, not trip membership; and reactions on the photo roll up
 * into the reaction chips of the chat message that carried it, with SUM semantics.
 */
@Slf4j
@Named("photoChat")
@ApplicationScoped
public class PhotoChatCommands {

    /** Duplicated from {@code ChatCommands} (private there); the grant rows are shared. */
    private static final String CHAT_ADMIN_PRIV = "chatAdmin";
    private static final String CHAT_MGR_PRIV = "chatMgr";

    /** Most photo keys one batch-meta call may ask about; more is a client bug, not a bigger page. */
    public static final int MAX_META_KEYS = 200;

    /** Chat photo keys look like {@code chat/{tripId}/{stamp}-{rand}[.ext]}; anything else is refused early. */
    private static final Pattern CHAT_KEY = Pattern.compile("^chat/([^/#\\s]+)/[^#\\s]+$");

    /** How many newest-first pages of 200 the legacy parent scan may read before giving up (runs once per photo). */
    private static final int MAX_PARENT_PAGES = 10;

    /**
     * ONE static instance, same as {@code ChatPhotos} and for the same reason: the REST edge has no
     * FacesContext, so a context-sensitive lookup would hand JSF and REST different instances. Nothing here is
     * request-scoped, so sharing is safe.
     */
    private static volatile PhotoChatCommands shared;

    private final ChatRateLimiter rateLimiter;
    private final ConfigCommands config;

    public PhotoChatCommands() {
        this(new ChatRateLimiter(DAO.getInstance().getCacheClient()));
    }

    /** Test constructor. */
    public PhotoChatCommands(final ChatRateLimiter rateLimiter) {
        this(rateLimiter, new ConfigCommands());
    }

    public PhotoChatCommands(final ChatRateLimiter rateLimiter, final ConfigCommands config) {
        this.rateLimiter = rateLimiter;
        this.config = config;
    }

    public static PhotoChatCommands getPhotoChatCommands() {
        PhotoChatCommands local = shared;
        if (local == null) {
            synchronized (PhotoChatCommands.class) {
                local = shared;
                if (local == null) {
                    local = new PhotoChatCommands();
                    shared = local;
                }
            }
        }
        return local;
    }

    /** Test seam: replaces the shared instance (pass null to reset to lazy default). */
    static void setShared(final PhotoChatCommands instance) {
        shared = instance;
    }

    // --- key and media resolution ---

    /** The trip embedded in a chat-photo key, or {@code null} when the key is not shaped like one. */
    public static String tripIdOfKey(final String s3Key) {
        if (s3Key == null) {
            return null;
        }
        final Matcher m = CHAT_KEY.matcher(s3Key);
        return m.matches() ? m.group(1) : null;
    }

    /**
     * The photo's album row, or {@code null} when none exists — in which case the photo does not exist for this
     * feature (deleted, never recorded, or not a chat photo at all).
     */
    public MediaItem mediaFor(final String s3Key) {
        if (s3Key == null) {
            return null;
        }
        for (final MediaItem item : dao().getAllMedia()) {
            if (s3Key.equals(item.getS3Key()) && ChatPhotos.isChatSlot(item.getSlot())) {
                return item;
            }
        }
        return null;
    }

    // --- authorization ---

    /**
     * Why this caller may not read the photo's thread, as a wire code, or {@code null} when they may.
     *
     * <p>The rule is "comments follow the photo": a photo that is not hidden is readable by anyone, signed in
     * or not, because the photo itself already is (CDN, public-by-URL). {@code hidden} is a moderation state,
     * so a hidden photo's thread is member-only. NOT_FOUND covers a malformed key and a missing media row
     * alike — an anonymous caller must not be able to distinguish "hidden" from "gone".
     */
    public String readDenialFor(final String s3Key, final Caller caller) {
        final String tripId = tripIdOfKey(s3Key);
        if (tripId == null) {
            return "NOT_FOUND";
        }
        final MediaItem item = mediaFor(s3Key);
        if (item == null) {
            return "NOT_FOUND";
        }
        if (!item.getHidden()) {
            return null;
        }
        if (caller == null || !caller.isAuthenticated()) {
            return "NOT_FOUND";
        }
        return canSeeIdentities(tripId, caller) ? null : "NOT_FOUND";
    }

    /**
     * Whether this caller gets reactor identities and member-level detail: trip members, {@code tripView}
     * holders, moderators and site admins. Everyone else — including anonymous — gets counts only.
     */
    public boolean canSeeIdentities(final String tripId, final Caller caller) {
        if (caller == null || !caller.isAuthenticated()) {
            return false;
        }
        final Trip trip = dao().getTrip(tripId).orElse(null);
        if (trip != null && caller.personId() != null && trip.getPeople().contains(caller.personId())) {
            return true;
        }
        return caller.has(PrivilegeCommands.TRIP_VIEW, tripId) || canModerate(tripId, caller);
    }

    /** Whether this caller may delete any comment on the trip's photos (authors may always delete their own). */
    public boolean canModerate(final String tripId, final Caller caller) {
        if (caller == null) {
            return false;
        }
        return caller.has(CHAT_ADMIN_PRIV)
                || caller.has(CHAT_MGR_PRIV, tripId)
                || caller.has(PrivilegeCommands.TRIP_MGR, tripId)
                || caller.has(PrivilegeCommands.MEDIA_ADMIN);
    }

    /**
     * The sender-trust gate for mention email (user decision 2026-08-09): someone who has joined at least one
     * trip — appears in some trip's people list, the same membership notion the rest of chat uses — is known to
     * us and may cause mail. Mere account registration is open to anyone and deliberately does not count.
     */
    public boolean isKnownTraveler(final Person.Id personId) {
        return personId != null && !dao().getTripsForUser(personId).isEmpty();
    }

    public boolean isEnabled() {
        return config.getBoolean(KnownSettings.CHAT_PHOTO_COMMENTS_ENABLED);
    }

    // --- channel lifecycle ---

    /** The photo's channel if one exists. Never creates — a GET must not write. */
    public ChatChannel photoChannelForRead(final String s3Key) {
        return dao().getChatChannel(ChatChannel.Id.forPhoto(s3Key)).orElse(null);
    }

    /**
     * The photo's channel, created on first use. Creation resolves the parent message once (see
     * {@link #resolveParent}) and stores it on the channel row; the create race is benign because both writers
     * compute identical content.
     */
    public ChatChannel ensurePhotoChannel(final String s3Key, final AuditActor actor) {
        final ChatChannel existing = photoChannelForRead(s3Key);
        if (existing != null) {
            return existing;
        }
        final Parent parent = resolveParent(tripIdOfKey(s3Key), s3Key);
        return createPhotoChannel(s3Key, parent, actor);
    }

    /**
     * Eager creation at send time — called for each attachment of a just-saved chat message, where the parent
     * is simply known. Failures are logged and never fail the send (same posture as {@code recordAlbumRows}).
     */
    public void ensureChannelsForMessage(final ChatMessage stored, final AuditActor actor) {
        if (stored == null) {
            return;
        }
        for (final ChatAttachment a : stored.getAttachments()) {
            try {
                if (a.getS3Key() != null && photoChannelForRead(a.getS3Key()) == null) {
                    createPhotoChannel(a.getS3Key(),
                            new Parent(stored.getChannelId(), stored.getId()), actor);
                }
            } catch (final RuntimeException ex) {
                log.error("Photo channel was not created for {}", a.getS3Key(), ex);
            }
        }
    }

    private ChatChannel createPhotoChannel(
            final String s3Key, final Parent parent, final AuditActor actor) {
        // The hand-written builder keeps the attachment caps at their nonzero defaults, so allowMedia=false
        // survives the v1ReservedMedia fingerprint (false with ZEROED caps would be "upgraded" back to on).
        // Retention fields stay null: photo comments live as long as the photo — album semantics.
        final ChatSettings settings = ChatSettings.builder().allowMedia(false).build();
        final ChatChannel channel = new ChatChannel(
                ChatChannel.Id.forPhoto(s3Key), tripIdOfKey(s3Key), ChatChannel.Kind.PHOTO,
                null, null, List.of(), settings, Instant.now(),
                actor == null ? null : actor.id(), null,
                parent == null ? null : parent.channelId(),
                parent == null ? null : parent.msgId(), null);
        dao().saveChatChannel(channel);
        return channel;
    }

    private record Parent(ChatChannel.Id channelId, ChatMessage.Id msgId) {
    }

    /**
     * Finds the trip-chat message that carried this photo, for a channel created lazily (a photo uploaded
     * before this feature existed). Newest-first raw pages, bounded; a retention-expired or never-found parent
     * resolves to none, which just means reactions have nothing to roll up into. Runs at most once per photo —
     * the created channel row is the durable record of the answer either way.
     */
    private Parent resolveParent(final String tripId, final String s3Key) {
        if (tripId == null) {
            return new Parent(null, null);
        }
        final ChatChannel.Id tripChannel = ChatChannel.Id.forTrip(tripId);
        ChatMessage.Id before = null;
        for (int page = 0; page < MAX_PARENT_PAGES; page++) {
            final List<ChatMessage> batch = dao().getRawChatMessagesBefore(tripChannel, before, 200);
            final ChatMessage.Id found = messageCarrying(batch, s3Key);
            if (found != null) {
                return new Parent(tripChannel, found);
            }
            if (batch.size() < 200) {
                return new Parent(null, null);
            }
            before = batch.get(batch.size() - 1).getId();
        }
        log.info("Photo parent scan gave up after {} pages for {}", MAX_PARENT_PAGES, s3Key);
        return new Parent(null, null);
    }

    private static ChatMessage.Id messageCarrying(final List<ChatMessage> batch, final String s3Key) {
        for (final ChatMessage m : batch) {
            for (final ChatAttachment a : m.getAttachments()) {
                if (s3Key.equals(a.getS3Key())) {
                    return m.getId();
                }
            }
        }
        return null;
    }

    // --- reads ---

    /**
     * One page of the photo's comment thread, newest-first, with display names resolved. The caller must have
     * passed {@link #readDenialFor} first; a photo with no channel yet answers an empty page rather than
     * creating one.
     */
    public ChatPage thread(final String s3Key, final ChatMessage.Id before, final int limit) {
        final ChatChannel channel = photoChannelForRead(s3Key);
        if (channel == null) {
            return new ChatPage(List.of(), Map.of(), null, 0L, 0L, false, true, Map.of(), Instant.now());
        }
        // member=null + stored fullHistoryForNewMembers=true ⇒ everything is visible; null trip ⇒ no
        // trip-end expiry can apply. Both are the album semantics, stated on the channel row itself.
        final ChatPage page = dao().getChatMessagesBefore(
                channel.getId(), before, limit <= 0 ? 50 : Math.min(limit, 50),
                null, channel, null, Instant.now());
        return withNames(page);
    }

    /** The photo's own reaction summary (the image-root target), from the cached per-photo meta. */
    public ChatReactionSummary rootSummary(final String s3Key) {
        final PhotoChatMeta meta = dao().getPhotoChatMeta(List.of(s3Key)).get(s3Key);
        return meta == null ? ChatReactionSummary.empty(PhotoChatMeta.PHOTO_ROOT) : meta.getRootReactions();
    }

    /**
     * Batch per-photo meta for badge rendering. Keys the caller may not read — hidden without membership,
     * malformed, or with no media row — are simply ABSENT from the result, indistinguishable by design.
     */
    public Map<String, PhotoChatMeta> batchMeta(final List<String> s3Keys, final Caller caller) {
        if (s3Keys == null || s3Keys.isEmpty()) {
            return Map.of();
        }
        final Set<String> distinct = new LinkedHashSet<>(s3Keys);
        final List<String> allowed = new ArrayList<>();
        for (final String key : distinct) {
            if (allowed.size() >= MAX_META_KEYS) {
                break;
            }
            if (readDenialFor(key, caller) == null) {
                allowed.add(key);
            }
        }
        return dao().getPhotoChatMeta(allowed);
    }

    // --- mutations ---

    /**
     * Posts a comment. The caller is the signed-in user (the resource enforces that); mention tokens in the
     * body are honored per {@link ChatNotifications#photoMentionsFor} — email only from a commenter who has
     * joined a trip.
     */
    public ChatCommands.SendResult comment(
            final String s3Key, final Person.Id me, final String body,
            final String clientMessageId, final Caller caller) {
        if (!isEnabled()) {
            return ChatCommands.SendResult.fail("disabled", "Photo comments are turned off.");
        }
        final String denial = readDenialFor(s3Key, caller);
        if (denial != null) {
            return ChatCommands.SendResult.fail("not_found", "Photo not found.");
        }
        final String text = body == null ? "" : body.strip();
        if (text.isEmpty()) {
            return ChatCommands.SendResult.fail("empty", "Comment cannot be empty.");
        }
        final int max = config.getInt(KnownSettings.CHAT_PHOTO_COMMENT_MAX_CHARS);
        if (text.codePointCount(0, text.length()) > max) {
            return ChatCommands.SendResult.fail("too_long",
                    "Comment is too long (max " + max + " characters).");
        }
        final Instant now = Instant.now();
        final AuditActor who = caller == null ? AuditActor.current() : caller.auditActor();
        final ChatChannel channel = ensurePhotoChannel(s3Key, who);
        final ChatRateLimiter.Decision decision = rateLimiter.check(channel, me, now);
        if (!decision.isAllowed() || decision.getAutoMuteUntil() != null) {
            // No auto-mute here: mute is a trip-chat membership state and photo posting has no membership.
            // Refusing with a plain 429 is the whole remedy.
            return ChatCommands.SendResult.rateLimited(decision);
        }
        final ChatMessage draft = new ChatMessage(
                null, channel.getId(), me, null,
                ChatMessage.MessageKind.TEXT, text, null, List.of(), null,
                null, null, null, null, clientMessageId, null);
        final Optional<ChatMessage> saved;
        try {
            saved = dao().saveChatMessage(draft, channel, null);
        } catch (final RuntimeException ex) {
            log.warn("Photo comment was not stored for {}", s3Key, ex);
            return ChatCommands.SendResult.fail("store", "Comment was not saved. Try again.");
        }
        if (saved.isEmpty()) {
            return ChatCommands.SendResult.fail("store", "Comment was not saved. Try again.");
        }
        final ChatMessage stored = saved.get();
        dao().invalidatePhotoChatMeta(s3Key);
        ChatNotifications.photoMentionsFor(stored, channel, tripOf(channel), authorDisplayName(me),
                isKnownTraveler(me), ownerOf(mediaFor(s3Key)));
        return ChatCommands.SendResult.ok(stored);
    }

    /**
     * The photo's uploader as a person id, or null. Chat-photo album rows store the author's id in
     * {@code uploadedBy}; anything else there (a reconciled or admin-uploaded row holds an email or a label)
     * resolves to no known person downstream and is therefore never mailed.
     */
    private static Person.Id ownerOf(final MediaItem photo) {
        final String uploadedBy = photo == null ? null : photo.getUploadedBy();
        return (uploadedBy == null || uploadedBy.isBlank()) ? null : Person.Id.from(uploadedBy);
    }

    /** Adds or removes this person's emoji on the photo itself (the image-root target). Idempotent by key. */
    public ChatCommands.ReactResult react(
            final String s3Key, final Person.Id me, final String emoji, final boolean add,
            final Caller caller) {
        if (!isEnabled()) {
            return ChatCommands.ReactResult.fail("disabled", "Photo comments are turned off.");
        }
        if (!ChatEmoji.isAllowed(emoji, palette())) {
            return ChatCommands.ReactResult.fail("bad_emoji", "That reaction is not available.");
        }
        final String denial = readDenialFor(s3Key, caller);
        if (denial != null) {
            return ChatCommands.ReactResult.fail("not_found", "Photo not found.");
        }
        final AuditActor who = caller == null ? AuditActor.current() : caller.auditActor();
        final ChatChannel channel = ensurePhotoChannel(s3Key, who);
        final boolean ok = add
                ? dao().putChatReaction(new ChatReaction(
                        channel.getId(), PhotoChatMeta.PHOTO_ROOT, me, emoji, Instant.now(), null))
                : dao().deleteChatReaction(channel.getId(), PhotoChatMeta.PHOTO_ROOT, me, emoji);
        if (!ok) {
            return ChatCommands.ReactResult.fail("store", "Reaction was not saved. Try again.");
        }
        // pmeta FIRST, then the parent: rollupToParent drops the parent's folded summary, and the rebuild
        // that follows reads pmeta — dropped first, it cannot fold the pre-write counts back in.
        dao().invalidatePhotoChatMeta(s3Key);
        dao().rollupPhotoToParent(channel);
        return ChatCommands.ReactResult.success();
    }

    /**
     * Tombstones a comment (never a row delete — a client already holding it needs the correction). Author
     * always may; otherwise the moderation set: chatMgr/tripMgr of the trip, chatAdmin, mediaAdmin, site admin.
     */
    public boolean deleteComment(final String s3Key, final ChatMessage.Id msgId, final Caller caller) {
        final ChatChannel channel = photoChannelForRead(s3Key);
        if (channel == null || msgId == null || caller == null) {
            return false;
        }
        final AuditActor who = caller.auditActor();
        final Optional<ChatMessage> before = dao().getChatMessage(channel.getId(), msgId);
        if (before.isEmpty()) {
            return false;
        }
        final boolean own = who != null && who.id() != null && before.get().getAuthorId() != null
                && before.get().getAuthorId().getValue().equals(who.id());
        if (!own && !canModerate(channel.getTripId(), caller)) {
            Audit.log(Audit.builder(AuditAction.CHAT_ADMIN, AuditOutcome.FAILURE)
                    .actor(who)
                    .target(AuditEventBuilder.TARGET_CHAT_MESSAGE, msgId.getValue())
                    .message("refused: delete photo comment on " + s3Key)
                    .build());
            return false;
        }
        final Optional<ChatMessage> tomb = dao().tombstoneChatMessage(
                channel.getId(), msgId, who == null || who.id() == null ? null : who.id());
        if (tomb.isEmpty()) {
            return false;
        }
        final String author = before.get().getAuthorId() == null
                ? "?" : before.get().getAuthorId().getValue();
        Audit.log(Audit.builder(AuditAction.CHAT_ADMIN, AuditOutcome.SUCCESS)
                .actor(who)
                .target(AuditEventBuilder.TARGET_CHAT_MESSAGE, msgId.getValue())
                .message("photo comment deleted; photo=" + s3Key + "; author=" + author
                        + "; body=" + snippetOf(before.get().getBody()))
                .build());
        dao().invalidatePhotoChatMeta(s3Key);
        return true;
    }

    // --- delete cascade ---

    /**
     * Removes a photo's whole thread — called when the photo itself is deleted, from the {@code MediaEvents}
     * listener and directly from {@code ChatPhotos.deleteEverywhere} (belt-and-braces for a photo that never
     * got its media row). Order: meta first (badges stop counting), then the parent roll-up (the folded counts
     * leave the message chip), then the purge. Idempotent throughout.
     */
    public static void purgePhotoThread(final String s3Key) {
        if (s3Key == null || s3Key.isBlank()) {
            return;
        }
        try {
            final DAO dao = DAO.getInstance();
            final ChatChannel.Id id = ChatChannel.Id.forPhoto(s3Key);
            dao.invalidatePhotoChatMeta(s3Key);
            dao.getChatChannel(id).ifPresent(dao::rollupPhotoToParent);
            dao.purgeChatChannel(id);
        } catch (final RuntimeException ex) {
            log.error("Photo comment thread was not purged for {}", s3Key, ex);
        }
    }

    /** The {@code MediaEvents} hook; registered once at startup by {@code ChatLifecycleListener}. */
    public static void onMediaChange(final MediaEvents.Change change, final String key) {
        if (change == MediaEvents.Change.REMOVED) {
            purgePhotoThread(key);
        }
    }

    // --- mention search (the all-users typeahead) ---

    /**
     * Typeahead candidates for mentioning ANY user, used from the album and landing-page surfaces (chat's own
     * composer stays roster-scoped). Guardrails, in order of what they protect against: signed-in only and a
     * 2-character minimum (the resource enforces both) plus the result cap defeat bulk directory enumeration;
     * and colliding display names are disambiguated with MASKED addresses only — the full address never leaves
     * the server, and the picked label is swapped for {@code @{id}} at send anyway.
     */
    /**
     * The typeahead's harvest brake: 30 lookups a minute per person — generous for typing, hostile to
     * scripted directory harvesting. Fails open on cache trouble, like every other rate limiter here.
     */
    public boolean mentionSearchAllowed(final Person.Id me) {
        if (me == null) {
            return false;
        }
        final long win = Instant.now().getEpochSecond() / 60;
        final Optional<Long> count = dao().getCacheClient().increment(
                CacheKeys.chatMentionSearchKey(me.getValue(), win), 1L, Duration.ofMinutes(2));
        return count.isEmpty() || count.get() <= 30;
    }

    public List<Map<String, String>> mentionSearch(final String query, final int maxResults) {
        final String q = query == null ? "" : query.strip();
        if (q.length() < 2) {
            return List.of();
        }
        final List<Person> matches = new ArrayList<>();
        for (final Person person : dao().searchPeople(q, Math.max(1, maxResults))) {
            if (person != null && person.getId() != null) {
                matches.add(person);
            }
        }
        return labeled(matches);
    }

    /** Labels for a result page: display name, plus a masked address only where two names collide. */
    private static List<Map<String, String>> labeled(final List<Person> matches) {
        final Map<String, Integer> nameCounts = new LinkedHashMap<>();
        for (final Person person : matches) {
            nameCounts.merge(displayName(person), 1, Integer::sum);
        }
        final List<Map<String, String>> out = new ArrayList<>(matches.size());
        for (final Person person : matches) {
            final String name = displayName(person);
            final String label = nameCounts.getOrDefault(name, 0) > 1
                    ? name + " (" + maskedEmail(person.getEmail()) + ")" : name;
            out.add(Map.of("id", person.getId().getValue(), "label", label));
        }
        return out;
    }

    private static String displayName(final Person person) {
        final String name = person == null ? null : person.getPreferredName();
        return name == null || name.isBlank()
                ? (person == null || person.getId() == null ? "?" : person.getId().getValue()) : name;
    }

    /**
     * {@code jsmith@example.org} → {@code j•••h@e…}. Enough for the person typing to pick the right entry,
     * useless for harvesting.
     */
    static String maskedEmail(final String email) {
        if (email == null || email.isBlank()) {
            return "no email";
        }
        final int at = email.indexOf('@');
        final String local = at > 0 ? email.substring(0, at) : email;
        final String domainFirst = at > 0 && at + 1 < email.length()
                ? email.substring(at + 1, at + 2) : "";
        final String head = local.substring(0, 1);
        final String tail = local.length() > 1 ? local.substring(local.length() - 1) : "";
        return head + "•••" + tail + "@" + domainFirst + "…";
    }

    // --- helpers ---

    private ChatPage withNames(final ChatPage page) {
        final Set<String> ids = new LinkedHashSet<>();
        for (final ChatMessage m : page.getMessages()) {
            if (m.getAuthorId() != null) {
                ids.add(m.getAuthorId().getValue());
            }
            for (final Person.Id mentioned : ChatMentions.extract(m.getBody())) {
                ids.add(mentioned.getValue());
            }
        }
        if (ids.isEmpty()) {
            return page;
        }
        final Map<String, String> names = new LinkedHashMap<>();
        for (final String id : ids) {
            names.put(id, displayNameOrId(id));
        }
        return page.withDisplayNames(names);
    }

    /** Same null-name guard as {@code ChatCommands.displayNameOrId}: a nameless person must not NPE the page. */
    private static String displayNameOrId(final String id) {
        final Person person = PersonCommands.getPersonCommands().getPerson(Person.Id.from(id));
        final String name = person == null ? null : person.getPreferredName();
        return name == null || name.isBlank() ? id : name;
    }

    /** Display names for everyone in a root summary's who-lists, for the member-level thread response. */
    public Map<String, String> reactorNames(final ChatReactionSummary summary) {
        if (summary == null) {
            return Map.of();
        }
        final Map<String, String> names = new LinkedHashMap<>();
        for (final List<Person.Id> who : summary.getByEmoji().values()) {
            for (final Person.Id id : who) {
                names.putIfAbsent(id.getValue(), displayNameOrId(id.getValue()));
            }
        }
        return names;
    }

    private String authorDisplayName(final Person.Id authorId) {
        return authorId == null ? "Someone" : displayNameOrId(authorId.getValue());
    }

    public List<String> palette() {
        return ChatEmoji.parsePalette(config.getString(KnownSettings.CHAT_REACTIONS_PALETTE));
    }

    private Trip tripOf(final ChatChannel channel) {
        if (channel == null || channel.getTripId() == null) {
            return null;
        }
        return dao().getTrip(channel.getTripId()).orElse(null);
    }

    private static String snippetOf(final String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 120 ? body : body.substring(0, 120);
    }

    private DAO dao() {
        return DAO.getInstance();
    }
}
