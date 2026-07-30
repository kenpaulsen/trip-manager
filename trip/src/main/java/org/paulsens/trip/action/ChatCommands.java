package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.chat.ChatNotifications;
import org.paulsens.trip.chat.ChatRateLimiter;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatEmoji;
import org.paulsens.trip.model.chat.ChatMembership;
import org.paulsens.trip.model.chat.ChatMentions;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatNotifyPref;
import org.paulsens.trip.model.chat.ChatPage;
import org.paulsens.trip.model.chat.ChatQuote;
import org.paulsens.trip.model.chat.ChatReaction;
import org.paulsens.trip.model.chat.ChatReactionSummary;
import org.paulsens.trip.model.chat.ChatSettings;
import org.paulsens.trip.model.chat.ChatVisibility;
import org.paulsens.trip.util.ScopeUtil;

/**
 * Chat operations for both JSF pages and the JAX-RS resource. Capture {@link AuditActor} at the top of any
 * method that may hop threads.
 */
@Slf4j
@Named("chat")
@ApplicationScoped
public class ChatCommands {

    public static final String MEDIA_TYPE_V1 = "application/vnd.trip.chat.v1+json";
    public static final String CSRF_HEADER = "X-Trip-Chat";

    /**
     * The instance used off the JSF request path (the REST edge, and any future socket). Held statically because
     * {@code getChatCommands()} previously constructed a fresh instance per call whenever no {@code FacesContext}
     * existed -- which is every REST request. Anything instance-scoped therefore reset on every request.
     */
    private static volatile ChatCommands shared;

    private final ChatRateLimiter rateLimiter;

    public ChatCommands() {
        this.rateLimiter = new ChatRateLimiter(DAO.getInstance().getCacheClient());
    }

    /** Test constructor. */
    public ChatCommands(final ChatRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    public static ChatCommands getChatCommands() {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx != null) {
            final Map<String, Object> appMap = ctx.getExternalContext().getApplicationMap();
            return (ChatCommands) appMap.computeIfAbsent("chat", key -> new ChatCommands());
        }
        return sharedInstance();
    }

    private static ChatCommands sharedInstance() {
        ChatCommands local = shared;
        if (local == null) {
            synchronized (ChatCommands.class) {
                local = shared;
                if (local == null) {
                    local = new ChatCommands();
                    shared = local;
                }
            }
        }
        return local;
    }

    // --- channel lifecycle ---

    public ChatChannel ensureChannel(final String tripId, final AuditActor actor) {
        final ChatChannel.Id id = ChatChannel.Id.forTrip(tripId);
        final Optional<ChatChannel> existing = dao().getChatChannel(id).join();
        if (existing.isPresent()) {
            return existing.get();
        }
        final Trip trip = dao().getTrip(tripId).join().orElse(null);
        final String title = trip == null || trip.getTitle() == null ? "Trip chat" : trip.getTitle() + " chat";
        final ChatChannel created = new ChatChannel(
                id, tripId, ChatChannel.Kind.TRIP, title, null, null,
                ChatSettings.defaults(), Instant.now(),
                actor == null ? null : actor.id(), null, null);
        dao().saveChatChannel(created).join();
        audit(AuditAction.CHAT_ADMIN, actor, AuditEventBuilder.TARGET_CHAT_CHANNEL, id.getValue(),
                "channel created");
        return created;
    }

    /**
     * The channel for rendering a page: the stored one if it exists, otherwise an unsaved default so the page has
     * something to read settings from.
     *
     * <p>Deliberately does <b>not</b> persist. Rendering a page is not an administrative act, and creating here
     * wrote a "channel created" audit record attributed to whoever opened the tab first -- or, when the page passed
     * no actor, to nobody at all. The channel becomes real on the first send.
     */
    public ChatChannel channelForPage(final String tripId) {
        final ChatChannel existing = getChannel(tripId);
        if (existing != null) {
            return existing;
        }
        final Trip trip = dao().getTrip(tripId).join().orElse(null);
        final String title = trip == null || trip.getTitle() == null ? "Trip chat" : trip.getTitle() + " chat";
        return new ChatChannel(ChatChannel.Id.forTrip(tripId), tripId, ChatChannel.Kind.TRIP, title,
                null, null, ChatSettings.defaults(), Instant.EPOCH, null, null, null);
    }

    public ChatChannel getChannel(final String tripId) {
        return dao().getChatChannel(ChatChannel.Id.forTrip(tripId)).join().orElse(null);
    }

    // --- authorization helpers ---

    public boolean canAdminister(final String tripId, final Person.Id me) {
        return canAdminister(tripId, me, false);
    }

    /**
     * @param siteAdminHint whether the caller has already established site-admin status from a source this method
     *     cannot reach. Required because the site-admin role lives in the HTTP session and the usual check reads it
     *     through {@code FacesContext}, which does not exist on the REST edge -- so without the hint a site admin
     *     is silently refused every privileged action there. The REST resource supplies it from the session.
     */
    public boolean canAdminister(final String tripId, final Person.Id me, final boolean siteAdminHint) {
        if (me == null) {
            return false;
        }
        if (siteAdminHint || PersonCommands.getPersonCommands().hasRole("admin")) {
            return true;
        }
        final PrivilegeCommands priv = new PrivilegeCommands();
        return priv.check("chatAdmin", null, me) || priv.check("chatMgr", tripId, me);
    }

    /**
     * Gate for every moderation operation. Enforced <b>here, in the bean</b>, not only at the REST resource and
     * certainly not only by an XHTML {@code rendered=} attribute -- that hides a button, it does not stop a
     * postback. Every mutating admin method below calls this before touching anything, so a caller that forgets
     * cannot escalate. Denials are audited as failures, because an attempted moderation is worth seeing.
     */
    private boolean denyUnlessAdmin(final String tripId, final AuditActor who, final String what) {
        return denyUnlessAdmin(tripId, who, what, false);
    }

    private boolean denyUnlessAdmin(
            final String tripId, final AuditActor who, final String what, final boolean siteAdminHint) {
        final Person.Id me = who == null || who.id() == null ? currentUserId() : Person.Id.from(who.id());
        if (me != null && canAdminister(tripId, me, siteAdminHint)) {
            return false;
        }
        log.warn("Denied chat admin operation '{}' on trip {} for actor {}", what, tripId,
                who == null ? null : who.email());
        Audit.log(Audit.builder(AuditAction.CHAT_ADMIN, AuditOutcome.FAILURE)
                .actor(who == null ? AuditActor.current() : who)
                .target(AuditEventBuilder.TARGET_CHAT_CHANNEL, ChatChannel.Id.forTrip(tripId).getValue())
                .message("denied: " + what + " (chatMgr required)")
                .build());
        growlError("You do not have permission to administer this chat.");
        return true;
    }

    public boolean hasChatMgr(final String tripId) {
        final List<Person.Id> holders = new PrivilegeCommands()
                .getPeopleWithPriv(List.of("chatMgr"), tripId);
        return !holders.isEmpty();
    }

    public boolean isTripMember(final String tripId, final Person.Id personId) {
        if (tripId == null || personId == null) {
            return false;
        }
        final Trip trip = dao().getTrip(tripId).join().orElse(null);
        if (trip == null) {
            return false;
        }
        if (trip.getPeople().contains(personId)) {
            return true;
        }
        final PrivilegeCommands priv = new PrivilegeCommands();
        final PersonCommands people = PersonCommands.getPersonCommands();
        return people.hasRole("admin") || priv.check("tripView", tripId, personId);
    }

    /**
     * The reader's membership row, or empty for an implicit member (absent row ⇒ JOINED, which is what makes
     * default opt-in free). Empty is therefore <em>not</em> "no access" — callers must pair this with
     * {@link #isTripMember} and {@link #canRead}, and pass the result to {@code ChatVisibility}, which is the one
     * place allowed to decide what an implicit member may see.
     */
    public Optional<ChatMembership> membershipRow(final ChatChannel.Id channelId, final Person.Id personId) {
        return dao().getChatMembership(channelId, personId).join();
    }

    /**
     * The membership row or {@code null}, for XHTML/JSFT expressions.
     *
     * <p>JSFT's EL has no notion of {@code Optional}: {@code membership != null} is true for an <em>empty</em>
     * Optional, so the next {@code .state} dereference throws and aborts the whole {@code initPage} block -- which
     * surfaces as the page silently redirecting home rather than as an error. Pages must use this, never the
     * Optional-returning form.
     */
    public ChatMembership membershipFor(final ChatChannel.Id channelId, final Person.Id personId) {
        return membershipRow(channelId, personId).orElse(null);
    }

    /*
     * The three accessors below exist so the chat pages can put ONLY strings and booleans in viewScope.
     *
     * A model object in viewScope is a model object in the HTTP session, and the session is replicated through the
     * Redisson session manager, which runs in Tomcat's SHARED classloader -- it cannot see webapp classes at all.
     * Storing a ChatChannel produced a steady drip of
     * "SEVERE [redisson-3-14] Unable to handle topic message ... ClassNotFoundException:
     * org.paulsens.trip.model.chat.ChatChannel"
     * because the attribute broadcast is deserialised on a Redisson thread. Note the failure mode: not a changed
     * class, not a serialVersionUID mismatch -- the class is simply absent from that classloader, so it can never
     * work, for any webapp type, no matter how Serializable it is.
     *
     * This is the rule the design already stated ("only channel id, admin flag and trip go in viewScope") and it
     * is easy to break, because putting the object there reads as the obvious thing to do.
     */

    /** The channel's history setting, for the page banner, without putting a channel in viewScope. */
    public boolean fullHistoryForTrip(final String tripId) {
        final ChatChannel channel = channelForPage(tripId);
        return channel != null && channel.getSettings().isFullHistoryForNewMembers();
    }

    /**
     * This person's membership state as a plain string, or {@code ""} when they have no row.
     *
     * <p>Empty is not "no access": an absent row means implicitly JOINED (§4). The page uses this only to decide
     * between the composer, a Rejoin button and "an administrator must re-add you".
     */
    public String membershipStateForTrip(final String tripId, final Person.Id personId) {
        final ChatMembership row = membershipFor(ChatChannel.Id.forTrip(tripId), personId);
        return row == null ? "" : row.getState().name();
    }

    /** This person's email preference as a plain string, for the page's select menu. */
    public String emailPrefForTrip(final String tripId, final Person.Id personId) {
        return emailPref(ChatChannel.Id.forTrip(tripId), personId);
    }

    public boolean canRead(final ChatChannel channel, final Person.Id me) {
        return readDenial(channel, me) == null;
    }

    /**
     * Why this person may not read, as a wire error code, or {@code null} when they may.
     *
     * <p>Returns the <em>specific</em> reason rather than a boolean because the remedies differ and the client
     * cannot infer them: a person who left gets a Rejoin button, a person who was removed must not, and a person
     * no longer on the trip gets neither. See {@code ChatErrors}.
     */
    public String readDenial(final ChatChannel channel, final Person.Id me) {
        if (channel == null || me == null) {
            return "NOT_AUTHENTICATED";
        }
        if (!isTripMember(channel.getTripId(), me)) {
            return "NOT_A_TRIP_MEMBER";
        }
        final Optional<ChatMembership> row = dao().getChatMembership(channel.getId(), me).join();
        if (row.isEmpty()) {
            return null; // implicit JOINED -- the default opt-in
        }
        return switch (row.get().getState()) {
            case LEFT -> "LEFT_CHANNEL";
            case REMOVED -> "REMOVED_FROM_CHANNEL";
            default -> null;
        };
    }

    // --- send / feed ---

    public SendResult send(
            final String tripId,
            final Person.Id authorId,
            final String body,
            final String clientMessageId,
            final ChatMessage.Id replyToId,
            final AuditActor actor) {
        final AuditActor who = actor != null ? actor : AuditActor.current();
        final Instant now = Instant.now();
        final ChatChannel channel = ensureChannel(tripId, who);
        // One membership read serves the whole send: the authorization chain, the denial reason, and the reply
        // quote's visibility check. It was read four times per send, each a separate blocking round trip.
        final ChatMembership row = dao().getChatMembership(channel.getId(), authorId).join().orElse(null);
        final SendResult denial = postDenial(channel, authorId, row, now);
        if (denial != null) {
            return denial;
        }
        final String text = body == null ? "" : body;
        final int cps = text.codePointCount(0, text.length());
        final int max = channel.getSettings().getMaxMessageChars();
        if (cps == 0) {
            return SendResult.fail("empty", "Message cannot be empty.");
        }
        if (cps > max) {
            return SendResult.fail("too_long", "Message is too long (max " + max + " characters).");
        }

        final ChatRateLimiter.Decision decision = rateLimiter.check(channel, authorId, now);
        if (decision.getAutoMuteUntil() != null) {
            applyMute(tripId, authorId, decision.getAutoMuteUntil(),
                    "auto-mute after repeated rate-limit hits", who);
            // Report it as a mute, not a rate limit: the two must stay distinguishable to the user, and from
            // here on it is a mute that is blocking them.
            growlWarn("You have been muted until " + decision.getAutoMuteUntil()
                    + " after repeated rate-limit hits.");
            return SendResult.fail("muted", "You are muted and cannot post right now.");
        }
        if (!decision.isAllowed()) {
            growlWarn(decision.userMessage());
            return SendResult.rateLimited(decision);
        }

        ChatQuote quote = null;
        if (replyToId != null) {
            final Optional<ChatMessage> original = dao().getVisibleChatMessage(
                    channel.getId(), replyToId, row, channel, tripOf(channel), now).join();
            if (original.isPresent()) {
                final Person author = PersonCommands.getPersonCommands().getPerson(original.get().getAuthorId());
                final String name = author == null ? "Someone" : author.getPreferredName();
                quote = ChatQuote.from(original.get(), name);
            }
        }

        final ChatMessage draft = new ChatMessage(
                null, channel.getId(), authorId, null,
                ChatMessage.MessageKind.TEXT, text, quote, null, null,
                null, null, null, null, clientMessageId, null);
        final Optional<ChatMessage> saved = dao().saveChatMessage(draft, channel, tripOf(channel))
                .orTimeout(10, TimeUnit.SECONDS)
                .exceptionally(this::logSaveFailure)
                .join();
        if (saved.isEmpty()) {
            growlError("Message was not delivered. Try again.");
            return SendResult.fail("store", "Message was not delivered. Try again.");
        }
        // AFTER the durable write, never before: a notification about a message that failed to save would point at
        // nothing. Everything past this point is fire-and-forget on a pool thread and cannot fail the send.
        ChatNotifications.mentionsFor(saved.get(), channel, tripOf(channel), authorDisplayName(authorId));
        return SendResult.ok(saved.get());
    }

    /**
     * Why this person may not post, as a {@link SendResult}, or {@code null} when they may. Takes the membership row
     * so the caller reads it once.
     *
     * <p>Order is deliberate: a mute is reported before anything else that would also be true, because a mute must
     * always reach the user AS a mute -- never as a rate limit and never hidden behind a channel-wide condition.
     */
    private SendResult postDenial(
            final ChatChannel channel, final Person.Id me, final ChatMembership row, final Instant now) {
        if (row != null && row.isMuted(now)) {
            return SendResult.fail("muted", "You are muted and cannot post right now.");
        }
        if (!isTripMember(channel.getTripId(), me)) {
            return SendResult.fail("forbidden", "You cannot post in this chat.");
        }
        if (row != null && (row.getState() == ChatMembership.MemberState.LEFT
                || row.getState() == ChatMembership.MemberState.REMOVED)) {
            return SendResult.fail("forbidden", "You are not in this chat.");
        }
        if (ChatVisibility.isArchived(channel, tripOf(channel), now)) {
            return SendResult.fail("archived", "This chat is archived and read-only.");
        }
        if (channel.getSettings().getPostPolicy() == ChatSettings.PostPolicy.ADMINS_ONLY
                && !canAdminister(channel.getTripId(), me)) {
            return SendResult.fail("forbidden", "Only administrators can post in this chat.");
        }
        return null;
    }

    public ChatPage feed(
            final String tripId,
            final Person.Id readerId,
            final ChatMessage.Id since,
            final int limit) {
        final ChatChannel channel = getChannel(tripId);
        if (channel == null || !canRead(channel, readerId)) {
            return ChatPage.empty();
        }
        final Optional<ChatMembership> member =
                dao().getChatMembership(channel.getId(), readerId).join();
        return withNames(dao().getChatMessagesSince(
                channel.getId(), since, limit, member.orElse(null), channel, tripOf(channel), Instant.now())
                .join());
    }

    public ChatPage history(
            final String tripId,
            final Person.Id readerId,
            final ChatMessage.Id before,
            final int limit) {
        final ChatChannel channel = getChannel(tripId);
        if (channel == null || !canRead(channel, readerId)) {
            return ChatPage.empty();
        }
        final Optional<ChatMembership> member =
                dao().getChatMembership(channel.getId(), readerId).join();
        return withNames(dao().getChatMessagesBefore(
                channel.getId(), before, limit, member.orElse(null), channel, tripOf(channel), Instant.now())
                .join());
    }

    /** The send is not acknowledged unless the durable write succeeded, so a failure must surface. */
    private Optional<ChatMessage> logSaveFailure(final Throwable ex) {
        log.error("Failed to save chat message", ex);
        return Optional.empty();
    }

    /**
     * Resolves every person id the page will render -- authors, quote authors and @mentions -- into display names.
     * Done once per page rather than per message so a 50-message page costs one pass, and done here rather than in
     * the DAO because resolving people is an action-layer concern.
     */
    private ChatPage withNames(final ChatPage page) {
        final Set<String> ids = new LinkedHashSet<>();
        for (final ChatMessage m : page.getMessages()) {
            if (m.getAuthorId() != null) {
                ids.add(m.getAuthorId().getValue());
            }
            if (m.getQuote() != null && m.getQuote().getAuthorId() != null) {
                ids.add(m.getQuote().getAuthorId().getValue());
            }
            for (final Person.Id mentioned : ChatMentions.extract(m.getBody())) {
                ids.add(mentioned.getValue());
            }
        }
        // Reactors too. They are frequently NOT authors on this page -- someone can react without ever posting --
        // so leaving them out puts a raw person id in the reaction chip's "who reacted" tooltip.
        for (final ChatReactionSummary summary : page.getReactions().values()) {
            addReactorIds(ids, summary);
        }
        if (ids.isEmpty()) {
            return page;
        }
        final PersonCommands people = PersonCommands.getPersonCommands();
        final Map<String, String> names = new LinkedHashMap<>();
        for (final String id : ids) {
            names.put(id, displayNameOrId(people, id));
        }
        return page.withDisplayNames(names);
    }

    /**
     * A person's display name, falling back to their id.
     *
     * <p>The fallback covers a null name, not just a missing person. {@code Person.getPreferredName()} returns
     * {@code first} when there is no nickname, and {@code first} is nullable — so a person with neither would put a
     * null into the display-name map, and {@code Map.copyOf} in the {@code ChatPage} constructor rejects null
     * values. That threw inside the feed, which means <b>one such person on a trip 500s the chat for everyone on
     * it</b>, not just for themselves.
     */
    private static String displayNameOrId(final PersonCommands people, final String id) {
        final Person person = people.getPerson(Person.Id.from(id));
        final String name = person == null ? null : person.getPreferredName();
        return name == null || name.isBlank() ? id : name;
    }

    /** The author's name for a notification subject line, resolved on the request thread while people are cheap. */
    private String authorDisplayName(final Person.Id authorId) {
        return authorId == null ? "Someone" : displayNameOrId(PersonCommands.getPersonCommands(), authorId.getValue());
    }

    private static void addReactorIds(final Set<String> ids, final ChatReactionSummary summary) {
        for (final List<Person.Id> who : summary.getByEmoji().values()) {
            for (final Person.Id id : who) {
                ids.add(id.getValue());
            }
        }
    }

    /** Display names for every person id appearing in a set of summaries, for the reaction-refetch response. */
    public Map<String, String> reactorNames(final Map<ChatMessage.Id, ChatReactionSummary> summaries) {
        final Set<String> ids = new LinkedHashSet<>();
        for (final ChatReactionSummary summary : summaries.values()) {
            addReactorIds(ids, summary);
        }
        final PersonCommands people = PersonCommands.getPersonCommands();
        final Map<String, String> names = new LinkedHashMap<>();
        for (final String id : ids) {
            names.put(id, displayNameOrId(people, id));
        }
        return names;
    }

    // --- author self-edit ---

    /**
     * How long an author may edit their own message. Short on purpose: an edit rewrites what other people have
     * already read, so the window is for fixing a typo, not for revising history.
     */
    // TODO(config-store): promote to config.getInt("chat.edit.windowMinutes", 15)
    private static final long EDIT_WINDOW_MINUTES = 15L;

    /**
     * Lets an author correct their own message inside {@link #EDIT_WINDOW_MINUTES}.
     *
     * <p>Author-only, and deliberately <b>not</b> available to administrators: an admin who dislikes a message can
     * remove it, which is visible as a tombstone and audited. Letting them silently rewrite someone else's words
     * would put unattributable text under that person's name — a different and much worse power.
     *
     * <p>Not audited, per the design: an author fixing their own typo inside 15 minutes is ordinary use, and
     * recording it would bury the moderation events the trail exists for.
     */
    public ReactResult editMessage(
            final String tripId, final Person.Id me, final ChatMessage.Id msgId, final String newBody) {
        if (msgId == null) {
            return ReactResult.fail("not_found", "Message not found.");
        }
        final Instant now = Instant.now();
        final ChatChannel channel = getChannel(tripId);
        if (channel == null) {
            return ReactResult.fail("not_found", "No chat for this trip.");
        }
        final String denial = editDenial(channel, me, now);
        if (denial != null) {
            return ReactResult.fail(denial, reactDenialMessage(denial));
        }
        final ChatMembership row = membershipFor(channel.getId(), me);
        final Optional<ChatMessage> target = dao().getVisibleChatMessage(
                channel.getId(), msgId, row, channel, tripOf(channel), now).join();
        if (target.isEmpty() || target.get().isDeleted()) {
            return ReactResult.fail("not_found", "Message not found.");
        }
        final ChatMessage original = target.get();
        if (original.getAuthorId() == null || !original.getAuthorId().equals(me)) {
            return ReactResult.fail("FORBIDDEN", "You can only edit your own messages.");
        }
        if (!withinEditWindow(original.getSentAt(), now)) {
            return ReactResult.fail("EDIT_WINDOW_CLOSED",
                    "Messages can only be edited within " + EDIT_WINDOW_MINUTES + " minutes of sending.");
        }
        final String text = newBody == null ? "" : newBody;
        final int cps = text.codePointCount(0, text.length());
        if (cps == 0) {
            return ReactResult.fail("empty", "Message cannot be empty.");
        }
        if (cps > channel.getSettings().getMaxMessageChars()) {
            return ReactResult.fail("too_long",
                    "Message is too long (max " + channel.getSettings().getMaxMessageChars() + " characters).");
        }
        return dao().editChatMessage(channel.getId(), msgId, text).join().isPresent()
                ? ReactResult.success()
                : ReactResult.fail("store", "Edit was not saved. Try again.");
    }

    /**
     * Why this person may not edit here, or {@code null} when they may.
     *
     * <p>A mute blocks editing. Without that, a muted author could keep publishing by repeatedly rewriting a
     * message they sent before the mute — a hole that turns the edit window into an unmoderated channel.
     */
    private String editDenial(final ChatChannel channel, final Person.Id me, final Instant now) {
        final String readDenial = readDenial(channel, me);
        if (readDenial != null) {
            return readDenial;
        }
        if (!channel.getSettings().isAllowEdit()) {
            return "EDIT_DISABLED";
        }
        if (ChatVisibility.isArchived(channel, tripOf(channel), now)) {
            return "CHANNEL_ARCHIVED";
        }
        final ChatMembership row = membershipFor(channel.getId(), me);
        if (row != null && row.isMuted(now)) {
            return "MUTED";
        }
        return null;
    }

    /**
     * Whether a message sent at {@code sentAt} may still be edited at {@code now}. A missing timestamp is outside
     * the window: without one there is no evidence the message is recent, and the safe reading is "too late".
     */
    static boolean withinEditWindow(final Instant sentAt, final Instant now) {
        return sentAt != null && !sentAt.plus(EDIT_WINDOW_MINUTES, ChronoUnit.MINUTES).isBefore(now);
    }

    /** How long an author has to edit, for the client to decide whether to offer the button. */
    public long getEditWindowMinutes() {
        return EDIT_WINDOW_MINUTES;
    }

    // --- notification preferences ---

    /**
     * Sets this person's email preference for a channel, materialising their membership row if they were only ever
     * an implicit member.
     *
     * <p>Materialising is required, not incidental: an absent row means JOINED with defaults, and the default is
     * {@code OFF} — so without writing a row the opt-in would appear to work and then silently not.
     */
    public boolean setEmailPref(final String tripId, final Person.Id me, final String mode) {
        final ChatChannel channel = getChannel(tripId);
        if (channel == null || me == null || !canRead(channel, me)) {
            return false;
        }
        final ChatNotifyPref.DeliveryMode wanted = parseMode(mode);
        if (wanted == null) {
            return false;
        }
        final ChatMembership row = membershipRow(channel.getId(), me)
                .orElseGet(() -> ChatMembership.joining(channel.getId(), me, channel.getCreated()));
        final ChatNotifyPref pref = row.getNotify();
        final ChatMembership updated = row.withNotify(new ChatNotifyPref(
                pref.isInApp(), wanted, pref.getPush(),
                pref.getQuietHoursStart(), pref.getQuietHoursEnd(), pref.getTimeZone()));
        return dao().saveChatMembership(updated).join();
    }

    private static ChatNotifyPref.DeliveryMode parseMode(final String mode) {
        if (mode == null || mode.isBlank()) {
            return null;
        }
        try {
            return ChatNotifyPref.DeliveryMode.valueOf(mode.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (final IllegalArgumentException ex) {
            return null;
        }
    }

    /** The current email preference for the page's select, as a plain name for JSFT EL. */
    public String emailPref(final ChatChannel.Id channelId, final Person.Id personId) {
        // An implicit member has no row and therefore holds the DEFAULTS -- not OFF. Showing OFF here would be a
        // lie: the notifier reads the same defaults and would mail them anyway, so the screen would disagree with
        // the behaviour for everyone who has never opened it, which is most of a trip.
        return membershipRow(channelId, personId)
                .map(row -> row.getNotify().getEmail().name())
                .orElse(ChatNotifyPref.defaults().getEmail().name());
    }

    /** Saves the preference chosen on the chat page for the signed-in user. */
    public void saveEmailPrefFromUi(final String mode) {
        final String tripId = currentTripId();
        if (tripId == null || !setEmailPref(tripId, currentUserId(), mode)) {
            growlError("Unable to save your notification preference.");
            return;
        }
        growlWarn("Notification preference saved.");
    }

    // --- read cursor and unread ---

    /**
     * Records how far this person has read. Valkey-authoritative and lazily written by the client, because the
     * alternative — a DynamoDB write per user per poll — is the most expensive way to store the least important
     * data. A lost cursor costs an unread dot, never a message.
     */
    public boolean markRead(final String tripId, final Person.Id me, final ChatMessage.Id cursor) {
        final ChatChannel channel = getChannel(tripId);
        if (channel == null || me == null || cursor == null || !canRead(channel, me)) {
            return false;
        }
        return dao().saveChatCursor(channel.getId(), me, cursor).join();
    }

    /**
     * Whether this person has anything new — a dot, not a count.
     *
     * <p>An exact count would need {@code ZCOUNT}, which the cache SPI does not have, plus a fallback for a cold
     * cache, to render a number nobody acts on differently than a dot.
     */
    public boolean hasUnread(final String tripId, final Person.Id me) {
        final ChatChannel channel = getChannel(tripId);
        if (channel == null || me == null || !canRead(channel, me)) {
            return false;
        }
        return unreadAgainst(channel, me, dao().getChatLastActivity().join());
    }

    private boolean unreadAgainst(
            final ChatChannel channel, final Person.Id me, final Map<String, String> lastActivity) {
        final long activity = parseLong(lastActivity.get(channel.getId().getValue()), 0L);
        if (activity <= 0L) {
            return false;
        }
        final ChatMessage.Id cursor = dao().getChatCursor(channel.getId(), me).join().orElse(null);
        // No cursor means they have never opened this chat. Anything at all is unread -- which is the correct
        // first-time signal, and it is also why an absent cursor must not be read as "caught up".
        return cursor == null || cursor.getEpochMilli() < activity;
    }

    /** Unread state for the trip tab, addressed by trip id so the XHTML needs no person id. */
    public boolean isUnreadForCurrentUser(final String tripId) {
        return hasUnread(tripId, currentUserId());
    }

    // --- reactions ---

    /**
     * Adds a reaction. Idempotent: the row's key <em>is</em> {@code (message, person, emoji)}, so a double click is
     * a no-op rather than a double count, and no lock is needed anywhere in this path.
     */
    public ReactResult react(
            final String tripId, final Person.Id me, final ChatMessage.Id msgId, final String emoji) {
        return toggle(tripId, me, msgId, emoji, true);
    }

    /**
     * Removes a reaction. It genuinely disappears — unlike a deleted message, a removed reaction has no historical
     * value worth a tombstone.
     */
    public ReactResult unreact(
            final String tripId, final Person.Id me, final ChatMessage.Id msgId, final String emoji) {
        return toggle(tripId, me, msgId, emoji, false);
    }

    private ReactResult toggle(
            final String tripId,
            final Person.Id me,
            final ChatMessage.Id msgId,
            final String emoji,
            final boolean add) {
        if (!ChatEmoji.isAllowed(emoji)) {
            return ReactResult.fail("bad_emoji", "That reaction is not available.");
        }
        if (msgId == null) {
            return ReactResult.fail("not_found", "Message not found.");
        }
        final Instant now = Instant.now();
        final ChatChannel channel = getChannel(tripId);
        if (channel == null) {
            return ReactResult.fail("not_found", "No chat for this trip.");
        }
        final String denial = reactDenial(channel, me, now);
        if (denial != null) {
            return ReactResult.fail(denial, reactDenialMessage(denial));
        }
        // The target must be visible to this reader, not merely present. Reacting to a message they cannot see
        // would confirm it exists and put their name on it in everyone else's chip tooltip.
        final ChatMembership row = membershipFor(channel.getId(), me);
        final Optional<ChatMessage> target = dao().getVisibleChatMessage(
                channel.getId(), msgId, row, channel, tripOf(channel), now).join();
        if (target.isEmpty()) {
            return ReactResult.fail("not_found", "Message not found.");
        }
        final boolean ok = add
                ? dao().putChatReaction(new ChatReaction(
                        channel.getId(), msgId, me, emoji, now, target.get().getExpiresAt())).join()
                : dao().deleteChatReaction(channel.getId(), msgId, me, emoji).join();
        return ok ? ReactResult.success() : ReactResult.fail("store", "Reaction was not saved. Try again.");
    }

    /**
     * Why this person may not react, as a wire code, or {@code null} when they may.
     *
     * <p>A mute blocks reacting as well as posting. A muted person who could still react keeps a voice in the
     * channel through a control that was never meant to be one, which defeats the moderation action.
     */
    private String reactDenial(final ChatChannel channel, final Person.Id me, final Instant now) {
        final String readDenial = readDenial(channel, me);
        if (readDenial != null) {
            return readDenial;
        }
        if (!channel.getSettings().isAllowReactions()) {
            return "REACTIONS_DISABLED";
        }
        if (ChatVisibility.isArchived(channel, tripOf(channel), now)) {
            return "CHANNEL_ARCHIVED";
        }
        final ChatMembership row = membershipFor(channel.getId(), me);
        if (row != null && row.isMuted(now)) {
            return "MUTED";
        }
        return null;
    }

    private static String reactDenialMessage(final String code) {
        return switch (code) {
            case "REACTIONS_DISABLED" -> "Reactions are turned off in this chat.";
            case "EDIT_DISABLED" -> "Editing is turned off in this chat.";
            case "CHANNEL_ARCHIVED" -> "This chat is archived and read-only.";
            case "MUTED" -> "You are muted and cannot post, react or edit right now.";
            default -> "You cannot do that in this chat.";
        };
    }

    /**
     * Summaries for a window of messages, for a client refetching after {@code reactionsVersion} changed. Bounded by
     * the window the client actually has on screen rather than the whole channel.
     */
    public Map<ChatMessage.Id, ChatReactionSummary> reactionWindow(
            final String tripId, final Person.Id me, final ChatMessage.Id oldest, final ChatMessage.Id newest) {
        final ChatChannel channel = getChannel(tripId);
        if (channel == null || !canRead(channel, me)) {
            return Map.of();
        }
        return dao().getChatReactionWindow(channel.getId(), oldest, newest).join();
    }

    public long reactionsVersion(final String tripId) {
        final ChatChannel channel = getChannel(tripId);
        return channel == null ? 0L : dao().getChatReactionsVersion(channel.getId()).join();
    }

    /** The reaction palette, for the picker. */
    public List<String> getEmojiPalette() {
        return ChatEmoji.palette();
    }

    // --- join / leave ---

    public boolean leave(final String tripId, final Person.Id personId, final AuditActor actor) {
        final AuditActor who = actor != null ? actor : AuditActor.current();
        final ChatChannel channel = ensureChannel(tripId, who);
        final Instant now = Instant.now();
        final ChatMembership existing = dao().getChatMembership(channel.getId(), personId).join()
                .orElseGet(() -> new ChatMembership(
                        channel.getId(), personId, ChatMembership.MemberState.JOINED,
                        ChatMembership.MemberRole.MEMBER, channel.getCreated(), null, null, null,
                        null, null, null, null, null, null, null, null));
        final ChatMembership left = existing.withLeft(now, "self");
        final boolean ok = dao().saveChatMembership(left).join();
        if (ok) {
            audit(AuditAction.CHAT_LEAVE, who, AuditEventBuilder.TARGET_CHAT_CHANNEL,
                    channel.getId().getValue(), "left");
        }
        return ok;
    }

    public boolean rejoin(final String tripId, final Person.Id personId, final AuditActor actor) {
        final AuditActor who = actor != null ? actor : AuditActor.current();
        final ChatChannel channel = ensureChannel(tripId, who);
        final Instant now = Instant.now();
        final Optional<ChatMembership> existing = dao().getChatMembership(channel.getId(), personId).join();
        if (existing.isPresent() && existing.get().getState() == ChatMembership.MemberState.REMOVED) {
            return false; // admin must re-add
        }
        final ChatMembership base = existing.orElseGet(() -> new ChatMembership(
                channel.getId(), personId, ChatMembership.MemberState.JOINED,
                ChatMembership.MemberRole.MEMBER, now, null, null, null,
                null, null, null, null, null, null, null, null));
        // joinedAt immutable: withRejoined keeps original joinedAt
        final ChatMembership rejoined = existing.isEmpty()
                ? base
                : base.withRejoined(now, who.id());
        final boolean ok = dao().saveChatMembership(rejoined).join();
        if (ok) {
            final String msg = existing.isPresent() && existing.get().getLeftAt() != null
                    ? "re-joined after leaving on " + existing.get().getLeftAt()
                    : "joined";
            audit(AuditAction.CHAT_JOIN, who, AuditEventBuilder.TARGET_CHAT_CHANNEL,
                    channel.getId().getValue(), msg);
        }
        return ok;
    }

    // --- admin ---

    public boolean deleteMessage(
            final String tripId, final ChatMessage.Id msgId, final AuditActor actor) {
        return deleteMessage(tripId, msgId, actor, false);
    }

    public boolean deleteMessage(
            final String tripId, final ChatMessage.Id msgId, final AuditActor actor,
            final boolean siteAdminHint) {
        final AuditActor who = actor != null ? actor : AuditActor.current();
        if (denyUnlessAdmin(tripId, who, "delete message " + (msgId == null ? "?" : msgId.getValue()),
                siteAdminHint)) {
            return false;
        }
        final ChatChannel channel = getChannel(tripId);
        if (channel == null) {
            return false;
        }
        final Optional<ChatMessage> before = dao().getChatMessage(channel.getId(), msgId).join();
        final Optional<ChatMessage> tomb = dao().tombstoneChatMessage(
                channel.getId(), msgId, who.id() == null ? who.email() : who.id()).join();
        if (tomb.isPresent()) {
            final String snap = before.map(m -> snapshot(m.getBody(), 120)).orElse("");
            final String author = before.map(m -> m.getAuthorId() == null ? "?" : m.getAuthorId().getValue())
                    .orElse("?");
            audit(AuditAction.CHAT_ADMIN, who, AuditEventBuilder.TARGET_CHAT_MESSAGE, msgId.getValue(),
                    "message deleted; author=" + author + "; body=" + snap);
            return true;
        }
        return false;
    }

    /** JSF helper: mute for N minutes from now. */
    public boolean muteMinutes(
            final String tripId, final String personId, final Integer minutes,
            final String reason) {
        if (personId == null || minutes == null || minutes <= 0) {
            growlError("Person id and positive mute minutes are required.");
            return false;
        }
        return mute(tripId, Person.Id.from(personId),
                Instant.now().plusSeconds(minutes * 60L), reason, AuditActor.current());
    }

    public boolean unmuteUi(final String tripId, final String personId) {
        if (personId == null) {
            return false;
        }
        return unmute(tripId, Person.Id.from(personId), AuditActor.current());
    }

    public boolean removeMemberUi(final String tripId, final String personId, final String reason) {
        if (personId == null) {
            return false;
        }
        return removeMember(tripId, Person.Id.from(personId), reason, AuditActor.current());
    }

    public boolean saveSettingsFromUi(
            final String tripId,
            final Boolean fullHistory,
            final String postPolicy,
            final Long retentionSeconds,
            final Integer slowModeSeconds,
            final Integer burstLimit,
            final Integer burstWindowSeconds,
            final Integer sustainedLimit,
            final Integer sustainedWindowSeconds,
            final Integer maxMessageChars,
            final Boolean allowReactions,
            final String retentionPreset,
            final Integer archiveAfterTripEndDays) {
        final ChatChannel channel = ensureChannel(tripId, AuditActor.current());
        final ChatSettings updated = channel.getSettings().toBuilder()
                .fullHistoryForNewMembers(fullHistory == null || fullHistory)
                .postPolicy(postPolicy == null ? ChatSettings.PostPolicy.ALL_MEMBERS
                        : ChatSettings.PostPolicy.valueOf(postPolicy))
                .retentionSeconds(retentionFromPreset(retentionPreset, retentionSeconds))
                .archiveAfterTripEndDays(archiveAfterTripEndDays == null
                        ? ChatSettings.DEFAULT_ARCHIVE_AFTER_TRIP_END_DAYS : archiveAfterTripEndDays)
                .slowModeSeconds(slowModeSeconds == null ? 0 : slowModeSeconds)
                .burstLimit(burstLimit == null ? ChatSettings.DEFAULT_BURST_LIMIT : burstLimit)
                .burstWindowSeconds(burstWindowSeconds == null
                        ? ChatSettings.DEFAULT_BURST_WINDOW_SECONDS : burstWindowSeconds)
                .sustainedLimit(sustainedLimit == null
                        ? ChatSettings.DEFAULT_SUSTAINED_LIMIT : sustainedLimit)
                .sustainedWindowSeconds(sustainedWindowSeconds == null
                        ? ChatSettings.DEFAULT_SUSTAINED_WINDOW_SECONDS : sustainedWindowSeconds)
                .maxMessageChars(maxMessageChars == null
                        ? ChatSettings.DEFAULT_MAX_MESSAGE_CHARS : maxMessageChars)
                .allowReactions(allowReactions == null || allowReactions)
                .build();
        return updateSettings(tripId, updated, AuditActor.current());
    }

    public boolean mute(
            final String tripId, final Person.Id target, final Instant until,
            final String reason, final AuditActor actor) {
        final AuditActor who = actor != null ? actor : AuditActor.current();
        if (denyUnlessAdmin(tripId, who, "mute " + target.getValue())) {
            return false;
        }
        return applyMute(tripId, target, until, reason, who);
    }

    /**
     * Writes a mute without the admin gate. Separate from {@link #mute} because the automatic mute is applied on
     * behalf of the <em>offending</em> user's own request: the actor on that thread is the person being muted, so
     * running it through the admin gate would deny the system's own enforcement action.
     */
    private boolean applyMute(
            final String tripId, final Person.Id target, final Instant until,
            final String reason, final AuditActor who) {
        final ChatChannel channel = ensureChannel(tripId, who);
        final ChatMembership member = materialize(channel, target, Instant.now());
        final ChatMembership muted = member.withMute(until, who.id(), reason);
        final boolean ok = dao().saveChatMembership(muted).join();
        if (ok) {
            audit(AuditAction.CHAT_ADMIN, who, AuditEventBuilder.TARGET_CHAT_CHANNEL,
                    channel.getId().getValue(),
                    "mute person=" + target.getValue() + " until=" + until + " reason=" + reason);
        }
        return ok;
    }

    public boolean unmute(final String tripId, final Person.Id target, final AuditActor actor) {
        final AuditActor who = actor != null ? actor : AuditActor.current();
        if (denyUnlessAdmin(tripId, who, "unmute " + target.getValue())) {
            return false;
        }
        final ChatChannel channel = ensureChannel(tripId, who);
        final Optional<ChatMembership> row = dao().getChatMembership(channel.getId(), target).join();
        if (row.isEmpty()) {
            return true;
        }
        final boolean ok = dao().saveChatMembership(row.get().withUnmuted()).join();
        if (ok) {
            audit(AuditAction.CHAT_ADMIN, who, AuditEventBuilder.TARGET_CHAT_CHANNEL,
                    channel.getId().getValue(), "unmute person=" + target.getValue());
        }
        return ok;
    }

    public boolean removeMember(
            final String tripId, final Person.Id target, final String reason, final AuditActor actor) {
        final AuditActor who = actor != null ? actor : AuditActor.current();
        if (denyUnlessAdmin(tripId, who, "remove " + target.getValue())) {
            return false;
        }
        final ChatChannel channel = ensureChannel(tripId, who);
        final Instant now = Instant.now();
        // Materialise even for implicit members — absent row ⇒ JOINED, so REMOVE must write a row.
        final ChatMembership member = materialize(channel, target, now);
        final ChatMembership removed = member.withRemoved(now, reason, who.id());
        final boolean ok = dao().saveChatMembership(removed).join();
        if (ok) {
            audit(AuditAction.CHAT_ADMIN, who, AuditEventBuilder.TARGET_CHAT_CHANNEL,
                    channel.getId().getValue(),
                    "member removed person=" + target.getValue() + " reason=" + reason);
        }
        return ok;
    }

    /**
     * Admin add/re-add. {@code acknowledgement} is recorded verbatim in the audit trail.
     */
    public boolean addMember(
            final String tripId, final Person.Id target, final String acknowledgement, final AuditActor actor) {
        final AuditActor who = actor != null ? actor : AuditActor.current();
        if (denyUnlessAdmin(tripId, who, "add " + target.getValue())) {
            return false;
        }
        // The acknowledgement IS the control here: the requirement is that a person is never re-enabled without
        // their permission, so an add with nothing confirmed must fail rather than be recorded as unacknowledged.
        if (acknowledgement == null || acknowledgement.isBlank()) {
            growlError("Confirm you have this person's permission before adding them.");
            return false;
        }
        final ChatChannel channel = ensureChannel(tripId, who);
        final Instant now = Instant.now();
        final Optional<ChatMembership> existing = dao().getChatMembership(channel.getId(), target).join();
        final ChatMembership base = existing.orElseGet(() -> new ChatMembership(
                channel.getId(), target, ChatMembership.MemberState.JOINED,
                ChatMembership.MemberRole.MEMBER, now, null, null, null,
                null, null, null, null, null, null, null, null));
        final ChatMembership added = existing.isPresent()
                ? base.withRejoined(now, who.id())
                : base.withState(ChatMembership.MemberState.JOINED);
        final boolean ok = dao().saveChatMembership(added).join();
        if (ok) {
            final String prior = existing.map(m -> m.getState().name()).orElse("implicit");
            audit(AuditAction.CHAT_ADMIN, who, AuditEventBuilder.TARGET_CHAT_CHANNEL,
                    channel.getId().getValue(),
                    "member added person=" + target.getValue() + " prior=" + prior
                            + " ack=" + acknowledgement);
        }
        return ok;
    }

    /** Records an export as the bulk disclosure it is: who, which channel, and how many messages left the app. */
    public void auditExport(final String tripId, final int messageCount, final AuditActor actor) {
        audit(AuditAction.CHAT_ADMIN, actor, AuditEventBuilder.TARGET_CHAT_CHANNEL,
                ChatChannel.Id.forTrip(tripId).getValue(),
                "transcript exported; messages=" + messageCount);
    }

    public String addWarningText(final ChatChannel channel, final Person.Id target) {
        final Optional<ChatMembership> row = dao().getChatMembership(channel.getId(), target).join();
        final Person person = PersonCommands.getPersonCommands().getPerson(target);
        final String name = person == null ? target.getValue() : person.getPreferredName();
        if (row.isEmpty()) {
            return "Add " + name + " to this chat? Confirm you have their permission.";
        }
        final ChatMembership m = row.get();
        if (m.getState() == ChatMembership.MemberState.LEFT) {
            return name + " chose to leave this chat on " + m.getLeftAt()
                    + ". Adding them back will resume notifications and expose the full history. "
                    + "Confirm you have their permission.";
        }
        if (m.getState() == ChatMembership.MemberState.REMOVED) {
            // Names the administrator AND the reason. Also states plainly that re-adding exposes what was said
            // while they were out -- which may include discussion of the removal itself. Making that a conscious
            // human decision is this warning's whole job.
            final String by = m.getRemovedBy() == null ? "an administrator" : displayName(m.getRemovedBy());
            return name + " was removed by " + by + " on " + m.getLeftAt()
                    + (m.getLeftReason() == null ? "" : " (" + m.getLeftReason() + ")")
                    + ". Adding them back lets them read everything said while they were removed. "
                    + "Confirm you intend to reverse that.";
        }
        return "Add " + name + " to this chat? Confirm you have their permission.";
    }

    private String displayName(final String personId) {
        final Person person = PersonCommands.getPersonCommands().getPerson(Person.Id.from(personId));
        return person == null ? personId : person.getPreferredName();
    }

    public boolean updateSettings(
            final String tripId,
            final ChatSettings newSettings,
            final AuditActor actor) {
        final AuditActor who = actor != null ? actor : AuditActor.current();
        if (denyUnlessAdmin(tripId, who, "update settings")) {
            return false;
        }
        final ChatChannel channel = ensureChannel(tripId, who);
        final String validation = validateSettings(newSettings);
        if (validation != null) {
            growlError(validation);
            return false;
        }
        final ChatSettings before = channel.getSettings();
        final ChatChannel updated = channel.withSettings(newSettings);
        // Turning fullHistory off backfills roster with joinedAt = channel.created
        if (before.isFullHistoryForNewMembers() && !newSettings.isFullHistoryForNewMembers()) {
            backfillRoster(channel);
        }
        final boolean ok = dao().saveChatChannel(updated).join();
        if (ok) {
            audit(AuditAction.CHAT_ADMIN, who, AuditEventBuilder.TARGET_CHAT_CHANNEL,
                    channel.getId().getValue(), describeSettingsChange(before, newSettings));
        }
        return ok;
    }

    /**
     * Turns the admin page's retention preset into the stored number.
     *
     * <p>{@code "forever"} maps to {@code null}, and that distinction is load-bearing: {@code null} means no expiry
     * at all, while {@code 0} means "expires with the hot buffer" — visually adjacent in a dropdown, opposite in
     * effect. An unrecognised preset falls back to the caller's existing value rather than guessing, because
     * guessing here either deletes history or keeps what an admin asked to expire.
     */
    static Long retentionFromPreset(final String preset, final Long existing) {
        if (preset == null || preset.isBlank()) {
            return existing;
        }
        if ("forever".equalsIgnoreCase(preset.trim())) {
            return null;
        }
        try {
            return Long.valueOf(preset.trim());
        } catch (final NumberFormatException ex) {
            log.warn("Unrecognised chat retention preset {}; keeping the existing setting", preset);
            return existing;
        }
    }

    public static String validateSettings(final ChatSettings s) {
        if (s == null) {
            return "Settings required";
        }
        if (s.getBurstLimit() < 1 || s.getSustainedLimit() < 1) {
            return "Rate limits must be at least 1 (0 would mute the channel).";
        }
        if (s.getBurstLimit() > 10_000 || s.getSustainedLimit() > 10_000) {
            return "Rate limits cannot exceed 10,000.";
        }
        if (s.getBurstWindowSeconds() < 1 || s.getBurstWindowSeconds() > 3600
                || s.getSustainedWindowSeconds() < 1 || s.getSustainedWindowSeconds() > 3600) {
            return "Rate-limit windows must be between 1 second and 1 hour.";
        }
        if (s.getMaxMessageChars() < 1) {
            return "maxMessageChars must be at least 1.";
        }
        return null;
    }

    public String describeSettingsChange(final ChatSettings before, final ChatSettings after) {
        final StringBuilder sb = new StringBuilder("Saved settings");
        diff(sb, "retention", before.getRetentionSeconds(), after.getRetentionSeconds());
        diff(sb, "fullHistoryForNewMembers", before.isFullHistoryForNewMembers(),
                after.isFullHistoryForNewMembers());
        diff(sb, "postPolicy", before.getPostPolicy(), after.getPostPolicy());
        diff(sb, "burstLimit", before.getBurstLimit(), after.getBurstLimit());
        diff(sb, "slowModeSeconds", before.getSlowModeSeconds(), after.getSlowModeSeconds());
        return sb.toString();
    }

    private static void diff(final StringBuilder sb, final String name, final Object a, final Object b) {
        if (!Objects.equals(a, b)) {
            sb.append("; ").append(name).append(' ').append(a).append("→").append(b);
        }
    }

    private void backfillRoster(final ChatChannel channel) {
        final Trip trip = tripOf(channel);
        if (trip == null) {
            return;
        }
        for (final Person.Id pid : trip.getPeople()) {
            final Optional<ChatMembership> row = dao().getChatMembership(channel.getId(), pid).join();
            if (row.isEmpty()) {
                final ChatMembership m = ChatMembership.joining(channel.getId(), pid, channel.getCreated());
                dao().saveChatMembership(m).join();
            }
        }
    }

    // --- my chats ---

    public List<ChatSummary> myChats(final Person.Id personId) {
        final List<Trip> trips = dao().getTripsForUser(personId).join();
        final Map<String, String> lastAct = dao().getCacheClient().getHash(CacheKeys.CHAT_LAST_ACTIVITY).join();
        final List<ChatSummary> out = new ArrayList<>();
        for (final Trip trip : trips) {
            final ChatChannel.Id cid = ChatChannel.Id.forTrip(trip.getId());
            final ChatChannel channel = dao().getChatChannel(cid).join().orElse(null);
            if (channel == null) {
                continue;
            }
            if (!canRead(channel, personId)) {
                continue;
            }
            final long activity = parseLong(lastAct.get(cid.getValue()), 0L);
            out.add(new ChatSummary(channel, trip.getTitle(), activity,
                    unreadAgainst(channel, personId, lastAct)));
        }
        out.sort(Comparator.comparingLong(ChatSummary::lastActivityMillis).reversed());
        return out;
    }

    public List<ChatMembership> roster(final String tripId) {
        final ChatChannel channel = getChannel(tripId);
        if (channel == null) {
            return List.of();
        }
        return dao().listChatMembers(channel.getId()).join();
    }

    // --- JSF helpers ---

    public void sendFromUi(final String body) {
        final Person.Id me = currentUserId();
        final String tripId = currentTripId();
        if (me == null || tripId == null) {
            return;
        }
        final String clientId = ScopeUtil.getInstance().getViewMap("chatClientMessageId");
        final Object reply = ScopeUtil.getInstance().getViewMap("chatReplyTo");
        final ChatMessage.Id replyId = reply instanceof String s && !s.isBlank()
                ? ChatMessage.Id.from(s) : null;
        final SendResult result = send(tripId, me, body, clientId, replyId, AuditActor.current());
        if (result.isOk()) {
            final FacesContext ctx = FacesContext.getCurrentInstance();
            if (ctx != null && ctx.getViewRoot() != null) {
                ctx.getViewRoot().getViewMap().put("chatDraft", "");
                ctx.getViewRoot().getViewMap().put("chatReplyTo", null);
            }
        } else if (result.getMessage() != null) {
            growlWarn(result.getMessage());
        }
    }

    public void leaveFromUi() {
        final Person.Id me = currentUserId();
        final String tripId = currentTripId();
        if (me != null && tripId != null) {
            leave(tripId, me, AuditActor.current());
        }
    }

    public void rejoinFromUi() {
        final Person.Id me = currentUserId();
        final String tripId = currentTripId();
        if (me != null && tripId != null) {
            rejoin(tripId, me, AuditActor.current());
        }
    }

    // --- internals ---

    /**
     * The membership row, creating one for an implicit member if needed. Every state change must materialise a row:
     * because an absent row means JOINED, a removal or mute that fails to write one silently evaporates on the
     * person's next read.
     */
    private ChatMembership materialize(final ChatChannel channel, final Person.Id personId, final Instant now) {
        // An implicit member has been in the channel since it existed, so that is their joinedAt floor.
        final Instant since = channel.getCreated() == null ? now : channel.getCreated();
        return dao().getChatMembership(channel.getId(), personId).join()
                .orElseGet(() -> ChatMembership.joining(channel.getId(), personId, since));
    }

    private Trip tripOf(final ChatChannel channel) {
        if (channel == null || channel.getTripId() == null) {
            return null;
        }
        return dao().getTrip(channel.getTripId()).join().orElse(null);
    }

    private DAO dao() {
        return DAO.getInstance();
    }

    private Person.Id currentUserId() {
        final Object id = ScopeUtil.getInstance().getSessionMap(PersonCommands.ACTIVE_USER_ID);
        if (id instanceof Person.Id pid) {
            return pid;
        }
        if (id != null) {
            return Person.Id.from(id.toString());
        }
        return null;
    }

    private String currentTripId() {
        final Object trip = ScopeUtil.getInstance().getViewMap("theTrip");
        if (trip instanceof Trip t) {
            return t.getId();
        }
        final Object param = ScopeUtil.getInstance().getRequestMap("trip");
        // Also try request parameter map via FacesContext
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (param == null && ctx != null) {
            final String q = ctx.getExternalContext().getRequestParameterMap().get("trip");
            return q;
        }
        return param == null ? null : param.toString();
    }

    private void audit(
            final AuditAction action, final AuditActor actor,
            final String targetType, final String targetId, final String message) {
        Audit.log(Audit.builder(action, AuditOutcome.SUCCESS)
                .actor(actor == null ? AuditActor.current() : actor)
                .target(targetType, targetId)
                .message(message)
                .build());
    }

    private static String snapshot(final String body, final int maxChars) {
        if (body == null) {
            return "";
        }
        return body.length() <= maxChars ? body : body.substring(0, maxChars);
    }

    private static long parseLong(final String s, final long dflt) {
        if (s == null) {
            return dflt;
        }
        try {
            return Long.parseLong(s);
        } catch (final NumberFormatException ex) {
            return dflt;
        }
    }

    private void growlWarn(final String msg) {
        TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_WARN, msg, null);
    }

    private void growlError(final String msg) {
        TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, msg, null);
    }

    public record ChatSummary(
            ChatChannel channel, String tripTitle, long lastActivityMillis, boolean unread) {
    }

    /** Outcome of a reaction toggle. Carries a code so the REST edge can map it to a status without re-deciding. */
    public record ReactResult(boolean ok, String code, String message) {

        public static ReactResult success() {
            return new ReactResult(true, null, null);
        }

        public static ReactResult fail(final String code, final String message) {
            return new ReactResult(false, code, message);
        }
    }

    public static final class SendResult {
        private final boolean ok;
        private final String code;
        private final String message;
        private final ChatMessage messageObj;
        private final ChatRateLimiter.Decision decision;

        private SendResult(
                final boolean ok, final String code, final String message,
                final ChatMessage messageObj, final ChatRateLimiter.Decision decision) {
            this.ok = ok;
            this.code = code;
            this.message = message;
            this.messageObj = messageObj;
            this.decision = decision;
        }

        public static SendResult ok(final ChatMessage msg) {
            return new SendResult(true, null, null, msg, null);
        }

        public static SendResult fail(final String code, final String message) {
            return new SendResult(false, code, message, null, null);
        }

        public static SendResult rateLimited(final ChatRateLimiter.Decision decision) {
            return new SendResult(false, "rate_limit", decision.userMessage(), null, decision);
        }

        public boolean isOk() {
            return ok;
        }

        public String getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }

        public ChatMessage getMessageObj() {
            return messageObj;
        }

        public ChatRateLimiter.Decision getDecision() {
            return decision;
        }
    }
}
