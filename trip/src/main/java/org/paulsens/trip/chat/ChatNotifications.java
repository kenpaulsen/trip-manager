package org.paulsens.trip.chat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.util.TripThreads;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatAttachment;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMembership;
import org.paulsens.trip.model.chat.ChatMentions;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatNotifyPref;
import org.paulsens.trip.model.chat.ChatQuote;
import org.paulsens.trip.push.PushChatNotifier;
import org.paulsens.trip.push.PushLinks;
import org.paulsens.trip.cache.Cached;

/**
 * Decides who hears about a message, and whether they are told what it said.
 *
 * <p>Separate from {@link ChatNotifier} on purpose: the notifier knows how to deliver, this knows the policy. The
 * policy is the part with the judgement in it, and it needs the channel's settings, which no delivery route sees.
 */
@Slf4j
public final class ChatNotifications {

    /**
     * Retention below which a notification carries no message text.
     *
     * <p>An inbox keeps mail for years. Emailing a body out of a channel configured to forget it within the week
     * makes that content outlive the retention policy, somewhere no administrator can reach to remove it — so the
     * mention still goes out, and it just says who mentioned you and where.
     */
    private static final Duration CONTENT_FREE_BELOW = Duration.ofDays(7);

    /**
     * A test's override. Null means the default fan-out. Kept apart from the default itself: a lazily-built
     * shared field was clobbered once by a late background dispatch that had read null, built the composite
     * and stored it AFTER the next test had installed its capturing notifier (every message now dispatches an
     * ALL_MESSAGES event, so such a thread is routine). Class-init below cannot be raced that way.
     */
    private static volatile ChatNotifier overrideNotifier;

    private ChatNotifications() {
    }

    /** Built once by class initialisation, which the JVM serialises; never reassigned. */
    private static final class DefaultHolder {
        private static final ChatNotifier DEFAULT =
                new CompositeChatNotifier(List.of(new EmailChatNotifier(), new PushChatNotifier()));
    }

    /** The process-wide fan-out. Composite so a push route can be added without touching any caller. */
    public static ChatNotifier notifier() {
        final ChatNotifier override = overrideNotifier;
        return override != null ? override : DefaultHolder.DEFAULT;
    }

    /** Test seam: null restores the default fan-out. */
    public static void setNotifier(final ChatNotifier notifier) {
        overrideNotifier = notifier;
    }

    /**
     * Notifies anyone mentioned in a freshly saved message.
     *
     * <p>Dispatched onto the shared pool rather than run inline: the sender's request is already finished as far as
     * they are concerned, and person lookups plus a template render plus an SES round trip have no business being on
     * the thread that is trying to return their message id.
     */
    public static void mentionsFor(
            final ChatMessage message, final ChatChannel channel, final Trip trip, final String authorName) {
        if (message == null || channel == null) {
            return;
        }
        final List<Person.Id> mentioned = new ArrayList<>(ChatMentions.extract(message.getBody()));
        if (ChatMentions.mentionsEveryone(message.getBody())) {
            // @all resolves to the roster at send time. Everyone still passes their own preference check below,
            // so it is a wider audience, not an override of anyone's choice.
            mentioned.addAll(everyoneIn(trip, message.getAuthorId()));
        }
        final List<Person.Id> alerted = new ArrayList<>();
        if (!mentioned.isEmpty()) {
            final ChatNotification notification = build(message, channel, trip, authorName, mentioned);
            if (!notification.getRecipients().isEmpty()) {
                alerted.addAll(notification.getRecipients());
                // startAs(system): the dispatch thread outlives the author's request, and the notification is the
                // application's act, not the author's (see EmailChatNotifier). Binding System here means anything
                // below that falls back to AuditActor.current() records System rather than nobody.
                TripThreads.startAs(AuditActor.system(), () -> dispatch(notification));
            }
        }
        replyFor(message, channel, trip, authorName, mentioned).ifPresent(alerted::add);
        everyMessageFor(message, channel, trip, authorName, alerted);
    }

    /**
     * The "a message was posted" event, for the routes that want every message: recipients are the explicit
     * rows whose push choice is ALL ({@code listChatMembers} -- only an explicit row can hold ALL), minus the
     * author and minus everyone already on this message's mention or reply notification. Dispatched for every
     * trip-channel message, recipients or not: the push route also runs its silent-refresh walk off it, so
     * a phone nobody named still learns there is something unread. Mail ignores the reason entirely.
     */
    private static void everyMessageFor(final ChatMessage message, final ChatChannel channel, final Trip trip,
            final String authorName, final List<Person.Id> alerted) {
        if (channel.getId().tripIdOrNull() == null || channel.getId().photoKeyOrNull() != null) {
            return;
        }
        final List<Person.Id> everyMessage = new ArrayList<>();
        for (final ChatMembership row : DAO.getInstance().listChatMembers(channel.getId(), Cached.NO)) {
            if (row.getState() == ChatMembership.MemberState.JOINED
                    && row.getNotify().getPushMode() == ChatNotifyPref.PushMode.ALL
                    && row.getPersonId() != null && !row.getPersonId().equals(message.getAuthorId())
                    && !alerted.contains(row.getPersonId()) && !everyMessage.contains(row.getPersonId())) {
                everyMessage.add(row.getPersonId());
            }
        }
        final ChatNotification notification = new ChatNotification(
                channel.getId(), message.getId(), channel.getTripId(),
                trip == null ? null : trip.getTitle(), message.getAuthorId(), authorName, everyMessage,
                includeContent(channel) ? snippet(message.getBody()) : null,
                ChatNotification.Reason.ALL_MESSAGES, null, message.getSentAt(),
                includeContent(channel) ? imageUrlFor(message, trip) : null);
        TripThreads.startAs(AuditActor.system(), () -> dispatch(notification));
    }

    /**
     * A reply addresses the person it quotes as surely as typing their name (user decision 2026-08-12), so
     * the quoted author is notified under exactly the mention rules — same membership/preference/address
     * gates via {@code eligible} — with reply wording rather than mention wording. Skipped when
     * they are ALSO named (or swept in by {@code @all}): they are already on the mention notification, and
     * the per-recipient dedupe key would drop a second mail for the same message anyway.
     */
    private static Optional<Person.Id> replyFor(final ChatMessage message, final ChatChannel channel,
            final Trip trip, final String authorName, final List<Person.Id> alreadyMentioned) {
        final ChatQuote quote = message.getQuote();
        if (quote == null || quote.getAuthorId() == null) {
            return Optional.empty();
        }
        final Person.Id repliedTo = quote.getAuthorId();
        if (repliedTo.equals(message.getAuthorId()) || alreadyMentioned.contains(repliedTo)) {
            return Optional.empty();
        }
        if (!eligible(channel.getId(), message.getAuthorId(), repliedTo)) {
            return Optional.empty();
        }
        final ChatNotification notification = new ChatNotification(
                channel.getId(), message.getId(), channel.getTripId(),
                trip == null ? null : trip.getTitle(), message.getAuthorId(), authorName,
                List.of(repliedTo), includeContent(channel) ? snippet(message.getBody()) : null,
                ChatNotification.Reason.REPLY, null, message.getSentAt(),
                includeContent(channel) ? imageUrlFor(message, trip) : null);
        TripThreads.startAs(AuditActor.system(), () -> dispatch(notification));
        return Optional.of(repliedTo);
    }

    /**
     * Notifies anyone mentioned in a photo comment — and the PHOTO'S UPLOADER, because commenting on a
     * picture replies to it (user decision 2026-08-12): the owner is notified under the same mention rules,
     * with comment wording. Photo threads differ from trip chat in three deliberate ways: {@code @all} is
     * inert (a photo thread has no roster to broadcast to); nothing is mailed unless the COMMENTER has joined
     * at least one trip — the sender-trust gate, because accounts are self-registered and must not be able to
     * make the site email arbitrary members (user decision 2026-08-09), and it covers the owner mail too; and
     * the mention-email preference is read from the photo's TRIP channel, where people actually manage chat
     * email.
     *
     * @param photoOwner who uploaded the photo, or null when unknown (a reconciled row whose uploadedBy is
     *        not a person id resolves to nobody downstream and is simply never mailed)
     */
    public static void photoMentionsFor(
            final ChatMessage comment, final ChatChannel photoChannel, final Trip trip,
            final String authorName, final boolean senderTrusted, final Person.Id photoOwner) {
        if (comment == null || photoChannel == null || photoChannel.getTripId() == null) {
            return;
        }
        if (!senderTrusted) {
            log.debug("Photo mention email suppressed: commenter {} has not joined any trip",
                    comment.getAuthorId());
            return;
        }
        final ChatChannel.Id prefHome = ChatChannel.Id.forTrip(photoChannel.getTripId());
        final List<Person.Id> mentioned = ChatMentions.extract(comment.getBody());
        final List<Person.Id> recipients = new ArrayList<>();
        for (final Person.Id person : mentioned) {
            if (eligible(prefHome, comment.getAuthorId(), person)) {
                recipients.add(person);
            }
        }
        final String image = includeContent(photoChannel)
                ? PushLinks.imageUrl(photoChannel.getId().photoKeyOrNull(), trip, new ConfigCommands()) : null;
        if (!recipients.isEmpty()) {
            final ChatNotification notification = new ChatNotification(
                    photoChannel.getId(), comment.getId(), photoChannel.getTripId(),
                    trip == null ? null : trip.getTitle(), comment.getAuthorId(), authorName, recipients,
                    includeContent(photoChannel) ? snippet(comment.getBody()) : null,
                    ChatNotification.Reason.MENTION, null, comment.getSentAt(), image);
            TripThreads.startAs(AuditActor.system(), () -> dispatch(notification));
        }
        if (photoOwner == null || photoOwner.equals(comment.getAuthorId())
                || mentioned.contains(photoOwner)
                || !eligible(prefHome, comment.getAuthorId(), photoOwner)) {
            return;
        }
        final ChatNotification ownerNote = new ChatNotification(
                photoChannel.getId(), comment.getId(), photoChannel.getTripId(),
                trip == null ? null : trip.getTitle(), comment.getAuthorId(), authorName,
                List.of(photoOwner), includeContent(photoChannel) ? snippet(comment.getBody()) : null,
                ChatNotification.Reason.PHOTO_COMMENT, null, comment.getSentAt(), image);
        TripThreads.startAs(AuditActor.system(), () -> dispatch(ownerNote));
    }

    /**
     * Everyone on the trip PLUS everyone with an explicit JOINED membership row, minus the author. The union
     * is what gives family managers (full members via {@code isTripMember}, never on the roster) their
     * {@code @all} mail once they have interacted with the chat -- and it deliberately requires that row: a
     * parent who never opened the channel is not broadcast to, and no reverse who-manages-whom lookup is
     * needed at fan-out time. Downstream {@link #eligible} still applies its own row-state filter per
     * recipient, and each route its own preference. Public because the push route walks the same set for
     * its silent refresh.
     */
    public static List<Person.Id> everyoneIn(final Trip trip, final Person.Id author) {
        if (trip == null) {
            return List.of();
        }
        final java.util.LinkedHashSet<Person.Id> all = new java.util.LinkedHashSet<>(trip.getPeople());
        for (final org.paulsens.trip.model.chat.ChatMembership row
                : DAO.getInstance().listChatMembers(
                        org.paulsens.trip.model.chat.ChatChannel.Id.forTrip(trip.getId()), Cached.NO)) {
            if (row.getState() == org.paulsens.trip.model.chat.ChatMembership.MemberState.JOINED) {
                all.add(row.getPersonId());
            }
        }
        return all.stream().filter(id -> id != null && !id.equals(author)).toList();
    }

    private static void dispatch(final ChatNotification notification) {
        try {
            notifier().notify(notification);
        } catch (final RuntimeException ex) {
            log.warn("Chat notification dispatch failed for {}", notification.getMessageId(), ex);
        }
    }

    private static ChatNotification build(
            final ChatMessage message,
            final ChatChannel channel,
            final Trip trip,
            final String authorName,
            final List<Person.Id> mentioned) {
        final List<Person.Id> recipients = new ArrayList<>();
        for (final Person.Id person : mentioned) {
            if (eligible(channel.getId(), message.getAuthorId(), person) && !recipients.contains(person)) {
                recipients.add(person);
            }
        }
        return new ChatNotification(
                channel.getId(),
                message.getId(),
                channel.getTripId(),
                trip == null ? null : trip.getTitle(),
                message.getAuthorId(),
                authorName,
                recipients,
                includeContent(channel) ? snippet(message.getBody()) : null,
                ChatNotification.Reason.MENTION,
                null,
                message.getSentAt(),
                includeContent(channel) ? imageUrlFor(message, trip) : null);
    }

    /**
     * Whether this person is a route-neutral candidate for a mention-class notification: a real person, not
     * the author, and not someone who LEFT or was REMOVED from the channel.
     *
     * <p>Membership state only. Each route applies its own preference at delivery -- the email route reads
     * {@code mentionEmail} and needs a usable address, the push route reads {@code pushMode} and needs a
     * device -- so a person with a phone and no email address is still told, and vice versa. An
     * <b>implicit</b> member (JOINED with no row) is a candidate: reading "no row" as "no" would have excluded
     * exactly the people who never touch a settings page, which is most of a trip.
     */
    static boolean eligible(final ChatChannel.Id channelId, final Person.Id author, final Person.Id person) {
        if (person == null || person.equals(author)) {
            return false;
        }
        if (DAO.getInstance().getPerson(person, Cached.YES).isEmpty()) {
            return false;
        }
        final Optional<ChatMembership> row = DAO.getInstance()
                .getChatMembership(channelId, person, Cached.NO);
        if (row.isPresent()) {
            final ChatMembership member = row.get();
            return member.getState() != ChatMembership.MemberState.LEFT
                    && member.getState() != ChatMembership.MemberState.REMOVED;
        }
        // Absent row ⇒ JOINED with default preferences, which is what makes default opt-in free.
        return true;
    }

    /**
     * The display rendition of a media message's first attachment, absolute, for the rich push; null for a
     * text message. Deleted or hidden attachments are skipped.
     */
    static String imageUrlFor(final ChatMessage message, final Trip trip) {
        if (message.getAttachments() == null) {
            return null;
        }
        for (final ChatAttachment attachment : message.getAttachments()) {
            if (attachment != null && attachment.getDeletedAt() == null
                    && !attachment.isHidden()) {
                final String key = attachment.getThumbKey() != null ? attachment.getThumbKey()
                        : attachment.getS3Key();
                return PushLinks.imageUrl(key, trip, new ConfigCommands());
            }
        }
        return null;
    }

    /** False when the channel's retention is short enough that mailing the body would outlive the policy. */
    static boolean includeContent(final ChatChannel channel) {
        final Long retention = channel.getSettings().getRetentionSeconds();
        return retention == null || retention >= CONTENT_FREE_BELOW.toSeconds();
    }

    static String snippet(final String body) {
        if (body == null) {
            return null;
        }
        final int max = 200;
        if (body.codePointCount(0, body.length()) <= max) {
            return body;
        }
        // Cut on a code point boundary: truncating mid-surrogate produces invalid UTF-8 in the mail body.
        return body.substring(0, body.offsetByCodePoints(0, max)) + "…";
    }
}
