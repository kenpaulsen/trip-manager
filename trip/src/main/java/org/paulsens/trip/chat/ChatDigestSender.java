package org.paulsens.trip.chat;

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
import org.paulsens.trip.action.MailCommands;
import org.paulsens.trip.action.PersonCommands;
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
import org.paulsens.trip.util.EmailAddresses;

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

    private static final String CFG_FROM = "chat.mail.from";
    private static final String CFG_REPLY_TO = "chat.mail.replyTo";
    private static final String CFG_BASE_URL = "chat.mail.baseUrl";
    private static final int MAX_MESSAGES_PER_DIGEST = 50;

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
        // Active trips only, and that bounds the work: a trip that ended long ago has an archived chat nobody is
        // waiting on, so scanning every trip that ever existed would cost more every year for no new mail.
        final LocalDateTime cutoff = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        for (final Trip trip : dao.getActiveTrips(cutoff).join()) {
            collectForTrip(out, trip, now);
        }
        return out;
    }

    private void collectForTrip(final List<Candidate> out, final Trip trip, final Instant now) {
        final ChatChannel channel = dao.getChatChannel(ChatChannel.Id.forTrip(trip.getId())).join().orElse(null);
        if (channel == null || !digestAllowed(channel)) {
            return;
        }
        for (final ChatMembership member : dao.listChatMembers(channel.getId()).join()) {
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
        final ChatMessage.Id floor = digestFloor(channel, member);
        final ChatPage page = dao.getChatMessagesSince(
                channel.getId(), floor, MAX_MESSAGES_PER_DIGEST, member, channel, trip, now).join();
        if (page.getMessages().isEmpty()) {
            return;
        }
        out.add(new Candidate(channel, trip, member.getPersonId(), member, page));
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
        if (member.getNotify().getEmail() != ChatNotifyPref.DeliveryMode.DIGEST_DAILY) {
            return false;
        }
        // this.dao, not DAO.getInstance(): the injected one is what the tests substitute.
        return dao.getPerson(member.getPersonId()).join()
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
    private ChatMessage.Id digestFloor(final ChatChannel channel, final ChatMembership member) {
        final ChatMessage.Id cursor = dao.getChatCursor(channel.getId(), member.getPersonId()).join().orElse(null);
        final ChatMessage.Id watermark = watermark(channel, member.getPersonId());
        if (cursor == null) {
            return watermark;
        }
        if (watermark == null) {
            return cursor;
        }
        return cursor.compareTo(watermark) >= 0 ? cursor : watermark;
    }

    private ChatMessage.Id watermark(final ChatChannel channel, final Person.Id personId) {
        return cacheClient.getValue(CacheKeys.chatDigestWatermarkKey(
                        channel.getId().getValue(), personId.getValue()))
                .join()
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
        final Person person = dao.getPerson(candidate.personId()).join().orElse(null);
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
        mail.send(from(), to, null, replyTo(),
                "New messages in the " + tripTitle(candidate) + " chat", body);
        advanceWatermark(candidate);
        return true;
    }

    private Map<String, Object> digestValues(final Candidate candidate) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("tripTitle", tripTitle(candidate));
        values.put("chatUrl", chatUrl(candidate.channel().getTripId()));
        values.put("messageCount", candidate.page().getMessages().size());
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
        for (final ChatMessage message : candidate.page().getMessages()) {
            appendMessage(html, message, people);
        }
        return new MailTemplates.Raw(html.append("</div>").toString());
    }

    private void appendMessage(
            final StringBuilder html, final ChatMessage message, final PersonCommands people) {
        final String author = authorName(message, people);
        final String body = message.isDeleted()
                ? "<i>Message removed by an administrator</i>"
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
        return config.getString(CFG_BASE_URL, "https://my.centermirmedjugorje.com")
                + "/trip/chat.jsf?trip=" + (tripId == null ? "" : tripId);
    }

    private String from() {
        return config.getString(CFG_FROM, "Trip Chat <no-reply@visitqueenofpeace.com>");
    }

    private String replyTo() {
        return config.getString(CFG_REPLY_TO, "no-reply@visitqueenofpeace.com");
    }
}
