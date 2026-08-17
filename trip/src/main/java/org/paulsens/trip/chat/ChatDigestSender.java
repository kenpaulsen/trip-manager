package org.paulsens.trip.chat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.action.MailCommands;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMembership;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatNotifyPref;
import org.paulsens.trip.model.chat.ChatPage;
import org.paulsens.trip.model.chat.ChatVisibility;
import org.paulsens.trip.util.EmailAddresses;
import software.amazon.awssdk.services.ses.model.SendEmailResponse;
import org.paulsens.trip.cache.Cached;

/**
 * Works out who is owed a digest and sends it.
 *
 * <p>Two conditions, both required, both checked per person per run: they <b>opted in</b> ({@code DIGEST_DAILY} —
 * the email default is {@code MENTIONS}, never a digest, so silence is never consent to a daily rollup), and they
 * <b>have something to receive</b>. An empty digest is never sent — "here is your nothing" is the fastest way to
 * make someone turn off a thing they asked for. A third, quieter condition: they must have a usable email address.
 */
@Slf4j
public class ChatDigestSender {

    private static final int MAX_MESSAGES_PER_DIGEST = 50;
    /**
     * Nothing older than this is ever summarised, whatever the cursor and watermark say (or fail to say).
     * Matches the cache TTL those two live under, so the worst case is a week of catch-up, never the archive.
     */
    private static final Duration MAX_LOOKBACK = Duration.ofDays(7);
    /** How far back to look for trips whose chat may still be open; isArchived makes the real decision. */
    private static final Duration CLOSED_TRIP_LOOKBACK = Duration.ofDays(400);

    private final DAO dao;
    private final CacheClient cacheClient;
    private final MailCommands mail;
    private final ConfigCommands config;

    public ChatDigestSender() {
        this(DAO.getInstance(), DAO.getInstance().getCacheClient(), new MailCommands(), new ConfigCommands());
    }

    public ChatDigestSender(
            final DAO dao,
            final CacheClient cacheClient,
            final MailCommands mail,
            final ConfigCommands config) {
        this.dao = dao;
        this.cacheClient = cacheClient;
        this.mail = mail;
        this.config = config;
    }

    /** One person owed a digest, with everything needed to send it. */
    public record Candidate(
            ChatChannel channel, Trip trip, Person.Id personId, ChatMembership member, ChatPage page) {

        /** Stable per (run, person, channel) so progress can be recorded before the send is attempted. */
        public String progressField() {
            return channel.getId().getValue() + "|" + personId.getValue();
        }
    }

    /**
     * Everyone owed a digest right now, across every trip.
     *
     * <p>Computed fresh on each attempt rather than snapshotted at the start of a run: a retry half an hour later
     * should reflect what is true then, and the progress hash — not this list — is what prevents a double send.
     */
    public List<Candidate> candidates(final Instant now) {
        final List<Candidate> out = new ArrayList<>();
        // Trips that have ENDED still have open chats: a chat stays writable until archiveAfterTripEndDays, which
        // defaults to 90. Asking only for currently-active trips stopped every digest the day a trip ended, which
        // is exactly when people are still talking about it. So look back far enough to cover any archive setting
        // and let isArchived decide per channel -- that is the setting that actually closes a chat.
        final LocalDateTime cutoff = LocalDateTime.ofInstant(now.minus(CLOSED_TRIP_LOOKBACK), ZoneOffset.UTC);
        for (final Trip trip : dao.getActiveTrips(cutoff, Cached.NO)) {
            collectForTrip(out, trip, now);
        }
        return out;
    }

    private void collectForTrip(final List<Candidate> out, final Trip trip, final Instant now) {
        if (!trip.getChatEnabled()) {
            return;
        }
        final ChatChannel channel = dao.getChatChannel(ChatChannel.Id.forTrip(trip.getId()), Cached.NO).orElse(null);
        if (channel == null || !digestAllowed(channel)) {
            return;
        }
        // A closed chat is read-only and finished; summarising it daily forever would be mail nobody can act on.
        if (ChatVisibility.isArchived(channel, trip, now)) {
            return;
        }
        for (final ChatMembership member : dao.listChatMembers(channel.getId(), Cached.NO)) {
            collectForMember(out, channel, trip, member, now);
        }
    }

    private void collectForMember(
            final List<Candidate> out,
            final ChatChannel channel,
            final Trip trip,
            final ChatMembership member,
            final Instant now) {
        if (!wantsDigest(member)) {
            return;
        }
        final ChatMessage.Id floor = digestFloor(channel, member, now);
        final ChatPage page = dao.getChatMessagesSince(
                channel.getId(), floor, MAX_MESSAGES_PER_DIGEST, member, channel, trip, now, Cached.NO);
        // Tombstones do not count as news. They stay visible in the app on purpose -- a client holding the message
        // must be told it was withdrawn -- but "1 new message" that turns out to be "Message removed" is a mail
        // nobody wanted, and a digest of nothing but removals is worse.
        if (newsIn(page).isEmpty()) {
            return;
        }
        out.add(new Candidate(channel, trip, member.getPersonId(), member, page));
    }

    /** The messages worth summarising: visible, and not withdrawn. */
    static List<ChatMessage> newsIn(final ChatPage page) {
        return page.getMessages().stream().filter(m -> m.getDeletedAt() == null).toList();
    }

    /**
     * A channel that keeps nothing durable gets no digest at all.
     *
     * <p>Not "an empty digest" — off. Mailing content out of a channel set to forget it immediately would defeat the
     * retention choice outright, and the mail would arrive describing messages nobody can go back and read.
     */
    static boolean digestAllowed(final ChatChannel channel) {
        final Long retention = channel.getSettings().getRetentionSeconds();
        return retention == null || retention > 0L;
    }

    /**
     * A digest is a positive opt-in — unlike mentions, which default on. Nobody is mailed a daily rollup they did
     * not ask for, so there is no implicit-member case here: no row means the default, and the default is not
     * {@code DIGEST_DAILY}.
     *
     * <p>An unusable address counts as opted out. Without this the run would attempt a send per person per day
     * for someone whose email field holds a bare name, and the retry protocol would keep re-attempting it.
     */
    private boolean wantsDigest(final ChatMembership member) {
        if (member.getState() == ChatMembership.MemberState.LEFT
                || member.getState() == ChatMembership.MemberState.REMOVED) {
            return false;
        }
        if (!member.getNotify().isDailyDigest()) {
            return false;
        }
        // this.dao, not DAO.getInstance(): the injected one is what the tests substitute.
        return dao.getPerson(member.getPersonId(), Cached.NO)
                .map(Person::getEmail)
                .filter(EmailAddresses::isValid)
                .isPresent();
    }

    /**
     * The later of what they have read and what they have already been told about.
     *
     * <p>Taking only the read cursor would repeat yesterday's digest to anyone who never opened the chat; taking only
     * the watermark would re-summarise messages they have since read in the app.
     */
    private ChatMessage.Id digestFloor(
            final ChatChannel channel, final ChatMembership member, final Instant now) {
        final ChatMessage.Id cursor = dao.getChatCursor(channel.getId(), member.getPersonId(), Cached.NO).orElse(null);
        final ChatMessage.Id watermark = watermark(channel, member.getPersonId());
        // A hard floor, always applied. Both the cursor and the watermark live in the cache under a 7-day TTL, so
        // "no floor at all" is not exotic -- it is what a quiet week, a cache flush or a brand-new member produces.
        // And a null floor is not "since yesterday": the query is an ASCENDING range with no lower bound, so it
        // returns the OLDEST messages in the channel. Someone would have received a digest of the trip's opening
        // conversation, months late, presented as new.
        final ChatMessage.Id lookback = ChatMessage.Id.of(now.minus(MAX_LOOKBACK).toEpochMilli());
        return latest(latest(cursor, watermark), lookback);
    }

    /** The later of two floors; nulls lose. */
    private static ChatMessage.Id latest(final ChatMessage.Id a, final ChatMessage.Id b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.compareTo(b) >= 0 ? a : b;
    }

    private ChatMessage.Id watermark(final ChatChannel channel, final Person.Id personId) {
        return cacheClient.getValue(CacheKeys.chatDigestWatermarkKey(
                        channel.getId().getValue(), personId.getValue()))
                .map(ChatMessage.Id::from)
                .orElse(null);
    }

    /**
     * Sends one person's digest.
     *
     * @return true when it was sent (or there was nothing worth sending); false when it failed and should be retried
     */
    public boolean send(final Candidate candidate) {
        try {
            return deliver(candidate);
        } catch (final RuntimeException ex) {
            log.warn("Chat digest failed for {}", candidate.personId(), ex);
            return false;
        }
    }

    private boolean deliver(final Candidate candidate) {
        final Person person = dao.getPerson(candidate.personId(), Cached.NO).orElse(null);
        final String to = person == null ? null : mail.formatEmail(person);
        if (to == null) {
            // Nothing to retry: they have no usable address. Treat as done so the run can finish.
            log.debug("Skipping chat digest for {}: no usable email address", candidate.personId());
            return true;
        }
        final String body = MailTemplates.render("chat-digest", digestValues(candidate));
        if (body == null) {
            return false;
        }
        // AWAITED, deliberately -- send() blocks until SES answers. Fire-and-forget reported success the moment
        // the request was handed to the SDK, so an SES rejection was recorded as a sent digest and that person
        // simply never heard from us -- and worse, the watermark below advanced anyway, so the retry found
        // nothing left to send and the day's summary was lost outright rather than retried. MailCommands maps a
        // failed send to a null response, and its client-level apiCallTimeout bounds a hung request, which
        // matters here: this runs on the single scheduler thread, so one hang would stall every remaining
        // recipient behind it.
        // System, not an empty actor: nobody asked for this send, the scheduler did. An unknown actor would
        // read as "we lost track of who did this"; System is an answer.
        final SendEmailResponse response = mail.send(from(), to, null, replyTo(),
                "New messages in the " + tripTitle(candidate) + " chat", body, AuditActor.system());
        if (response == null) {
            return false;
        }
        // Only after a confirmed send: the watermark is what stops tomorrow repeating today.
        advanceWatermark(candidate);
        return true;
    }

    private Map<String, Object> digestValues(final Candidate candidate) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("tripTitle", tripTitle(candidate));
        values.put("chatUrl", chatUrl(candidate.channel().getTripId()));
        // The count people see must match what the body lists: tombstones are in the page but are not news.
        values.put("messageCount", newsIn(candidate.page()).size());
        values.put("messageBlock", messageBlock(candidate));
        return values;
    }

    /**
     * The message list as HTML, escaped field by field.
     *
     * <p>Built here rather than looped in the template because this is where escaping happens, and because a
     * short-retention channel must produce a count-only digest: the body text would otherwise outlive the retention
     * policy inside people's inboxes.
     */
    private MailTemplates.Raw messageBlock(final Candidate candidate) {
        if (!ChatNotifications.includeContent(candidate.channel())) {
            return new MailTemplates.Raw(
                    "<p style=\"color:#666;\">Open the chat to read them — this chat does not keep messages "
                            + "long enough to include them here.</p>");
        }
        final PersonCommands people = PersonCommands.getPersonCommands();
        final StringBuilder html = new StringBuilder("<div>");
        for (final ChatMessage message : newsIn(candidate.page())) {
            appendMessage(html, message, people);
        }
        return new MailTemplates.Raw(html.append("</div>").toString());
    }

    private void appendMessage(
            final StringBuilder html, final ChatMessage message, final PersonCommands people) {
        final String author = authorName(message, people);
        final String body = message.isDeleted()
                // Not "by an administrator": authors can remove their own too, so that would often be untrue.
                ? "<i>Message removed</i>"
                : MailTemplates.escape(ChatNotifications.snippet(message.getBody()));
        html.append("<p style=\"margin:0.5rem 0;\"><b>")
                .append(MailTemplates.escape(author))
                .append("</b><br />")
                .append(body)
                .append("</p>");
    }

    private String authorName(final ChatMessage message, final PersonCommands people) {
        if (message.getAuthorId() == null) {
            return "System";
        }
        final Person person = people.getPerson(message.getAuthorId());
        final String name = person == null ? null : person.getPreferredName();
        return name == null || name.isBlank() ? message.getAuthorId().getValue() : name;
    }

    /**
     * Records how far this digest went, so tomorrow's starts after it.
     *
     * <p>Written after the send rather than before: if it were written first and the send then failed, the retry
     * would skip the very messages it was meant to deliver.
     */
    private void advanceWatermark(final Candidate candidate) {
        final Optional<ChatMessage> newest = candidate.page().getMessages().stream()
                .max(java.util.Comparator.comparing(ChatMessage::getId));
        newest.ifPresent(message -> cacheClient.putValue(
                CacheKeys.chatDigestWatermarkKey(
                        candidate.channel().getId().getValue(), candidate.personId().getValue()),
                message.getId().getValue(),
                CacheKeys.GC_TTL));
    }

    private String tripTitle(final Candidate candidate) {
        final Trip trip = candidate.trip();
        return trip == null || trip.getTitle() == null ? "trip" : trip.getTitle();
    }

    private String chatUrl(final String tripId) {
        return config.getString(KnownSettings.CHAT_MAIL_BASE_URL)
                + "/trip/chat.jsf?trip=" + (tripId == null ? "" : tripId);
    }

    private String from() {
        return config.getString(KnownSettings.CHAT_MAIL_FROM);
    }

    private String replyTo() {
        return config.getString(KnownSettings.CHAT_MAIL_REPLY_TO);
    }
}
