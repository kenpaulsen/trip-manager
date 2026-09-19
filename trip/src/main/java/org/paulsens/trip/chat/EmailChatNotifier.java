package org.paulsens.trip.chat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.action.MailCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.chat.ChatMembership;
import org.paulsens.trip.model.chat.ChatNotifyPref;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.site.SiteUrls;
import org.paulsens.trip.util.EmailAddresses;

/**
 * Sends chat notifications by email.
 *
 * <p>Only {@code MENTION} and {@code ADMIN_ANNOUNCEMENT} arrive immediately. Ordinary messages never do — that is
 * not a tuning choice but the difference between a feature people keep and one they mute in week one: a chatty
 * evening on a 200-person trip would otherwise be thousands of sends and an SES reputation problem.
 */
@Slf4j
public final class EmailChatNotifier implements ChatNotifier {


    private final MailCommands mail;
    private final CacheClient cacheClient;
    private final ConfigCommands config;

    public EmailChatNotifier() {
        this(new MailCommands(), DAO.getInstance().getCacheClient(), new ConfigCommands());
    }

    public EmailChatNotifier(
            final MailCommands mail, final CacheClient cacheClient, final ConfigCommands config) {
        this.mail = mail;
        this.cacheClient = cacheClient;
        this.config = config;
    }

    @Override
    public Channel channel() {
        return Channel.EMAIL;
    }

    /**
     * Off by default.
     *
     * <p>Deliberate: this ships dark so the code can deploy and be exercised before any pilgrim receives mail from
     * it, and so a runaway can be stopped from the config table without a deploy.
     */
    @Override
    public boolean isEnabled() {
        return config.getBoolean(KnownSettings.CHAT_MAIL_ENABLED);
    }

    @Override
    public void notify(final ChatNotification notification) {
        if (notification == null || notification.getReason() == ChatNotification.Reason.ALL_MESSAGES) {
            // ALL_MESSAGES is accepted by the model but never mailed immediately; it belongs to the digest.
            return;
        }
        for (final Person.Id recipient : notification.getRecipients()) {
            sendOne(notification, recipient);
        }
    }

    private void sendOne(final ChatNotification notification, final Person.Id recipient) {
        try {
            deliver(notification, recipient);
        } catch (final RuntimeException ex) {
            // Never propagate: the message this is about has already been acknowledged to its sender.
            log.warn("Unable to email chat notification to {}", recipient, ex);
        }
    }

    private void deliver(final ChatNotification notification, final Person.Id recipient) {
        if (!wantsMentionEmail(notification, recipient)) {
            return;
        }
        final Person person = DAO.getInstance().getPerson(recipient, Cached.NO).orElse(null);
        if (person == null) {
            return;
        }
        if (!EmailAddresses.isValid(person.getEmail())) {
            // Some people have no address and the field holds a bare name; a mention is not worth a failed
            // send per message. Their family managers answer for them instead (OnBehalf). Checked before the
            // claim so the push route's recipients cost nothing here.
            deliverToManagers(notification, person);
            return;
        }
        final String dedupe = ChatNotification.dedupeKeyFor(
                notification.getMessageId(), recipient, channel().name());
        sendClaimed(notification, person, dedupe, null);
    }

    /**
     * A person the route cannot reach is answered for by every mailable family manager who was not named
     * themselves, each under the manager's own mention preference and a dedupe key of their own that names
     * the child, with copy that says whose mention it is.
     */
    private void deliverToManagers(final ChatNotification notification, final Person person) {
        final java.util.List<Person> managers = OnBehalf.mailableManagers(person);
        if (managers.isEmpty()) {
            log.debug("Not emailing {} about a chat mention: no usable email address and no mailable manager",
                    person.getId());
            return;
        }
        for (final Person manager : managers) {
            if (notification.getRecipients().contains(manager.getId())
                    || !wantsMentionEmail(notification, manager.getId())) {
                continue;
            }
            final String dedupe = ChatNotification.dedupeKeyFor(notification.getMessageId(), manager.getId(),
                    OnBehalf.route(channel().name(), person.getId()));
            sendClaimed(notification, manager, dedupe, person);
        }
    }

    /**
     * Claim first. tryAcquireLock returns false when the marker exists, so the first caller wins and a retry or
     * a restart mid-fan-out cannot mail the same person twice.
     *
     * @param onBehalfOf the person this mail is really about when {@code to} is their family manager; null
     *                   when the recipient is being told about themselves
     */
    private void sendClaimed(final ChatNotification notification, final Person to, final String dedupe,
            final Person onBehalfOf) {
        if (!claim(dedupe)) {
            return;
        }
        final String address = mail.formatEmail(to);
        if (address == null) {
            log.debug("Skipping chat notification for {}: no usable email address", to.getId());
            return;
        }
        final String body = MailTemplates.render(templateFor(notification),
                mentionValues(notification, onBehalfOf));
        if (body == null) {
            // Template missing or unrenderable. Sending a half-built mail is worse than sending none.
            return;
        }
        // System: the notification is generated by the application in response to a message, and is delivered
        // asynchronously, so the author is not the one asking for this particular send.
        mail.send(from(), address, null, replyTo(notification), subjectFor(notification, onBehalfOf), body,
                AuditActor.system());
    }

    /**
     * The email preference, read from the trip channel (photo threads have none of their own). The default is
     * {@code MENTIONS}, so this is on unless someone turned it off -- including for an implicit member, who is
     * JOINED with no row and therefore holds the defaults. Recipients arrive route-neutral from
     * {@code ChatNotifications.eligible}; this is the email route's own half of the old combined check.
     */
    private static boolean wantsMentionEmail(final ChatNotification notification, final Person.Id recipient) {
        final String tripId = notification.getTripId();
        final boolean photo = notification.getChannelId() != null
                && notification.getChannelId().photoKeyOrNull() != null;
        final org.paulsens.trip.model.chat.ChatChannel.Id home = photo && tripId != null
                ? org.paulsens.trip.model.chat.ChatChannel.Id.forTrip(tripId) : notification.getChannelId();
        if (home == null) {
            return true;
        }
        return DAO.getInstance().getChatMembership(home, recipient, Cached.NO)
                .map(ChatMembership::getNotify)
                .map(ChatNotifyPref::isMentionEmail)
                .orElse(true);
    }

    private Map<String, Object> mentionValues(final ChatNotification notification, final Person onBehalfOf) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("authorName", notification.getAuthorName() == null ? "Someone" : notification.getAuthorName());
        values.put("tripTitle", notification.getTripTitle() == null ? "trip" : notification.getTripTitle());
        values.put("chatUrl", urlFor(notification));
        values.put("snippetBlock", snippetBlock(notification));
        values.put("addressee", onBehalfOf == null ? "you" : OnBehalf.nameOf(onBehalfOf));
        values.put("possessive", onBehalfOf == null ? "your" : OnBehalf.possessiveOf(onBehalfOf));
        values.put("footerNote", footerNote(notification, onBehalfOf));
        return values;
    }

    /** Why this mail arrived: the template's own footer for the person, or the family-manager line. */
    static String footerNote(final ChatNotification notification, final Person onBehalfOf) {
        if (onBehalfOf != null) {
            final String name = OnBehalf.nameOf(onBehalfOf);
            return "You are receiving this as " + name + "'s family manager: " + name
                    + " has no email address of their own. Mention emails can be turned off on the trip's chat page.";
        }
        return switch (templateFor(notification)) {
            case "chat-reply" -> "You are receiving this because someone replied to a message you wrote. Replies "
                    + "follow your mention notification setting, which can be turned off on the chat page.";
            case "photo-comment" -> "You are receiving this because someone commented on a photo you uploaded. "
                    + "These follow your mention notification setting, which can be turned off on the trip's "
                    + "chat page.";
            case "photo-mention" -> "You are receiving this because someone mentioned you by name in a photo "
                    + "comment. Mention emails can be turned off on the trip's chat page.";
            default -> "You are receiving this because you turned on mention notifications for this trip's "
                    + "chat. You can turn them off on the chat page.";
        };
    }

    /** Whether this notification is about a photo comment rather than the trip chat, read off the channel id. */
    private static boolean isPhotoNotification(final ChatNotification notification) {
        return notification.getChannelId() != null && notification.getChannelId().photoKeyOrNull() != null;
    }

    private static String templateFor(final ChatNotification notification) {
        if (notification.getReason() == ChatNotification.Reason.REPLY) {
            return "chat-reply";
        }
        if (notification.getReason() == ChatNotification.Reason.PHOTO_COMMENT) {
            return "photo-comment";
        }
        return isPhotoNotification(notification) ? "photo-mention" : "chat-mention";
    }

    /**
     * Where the mail's link lands: the chat page, or — for a photo comment — the trip album deep-linked to the
     * photo (`?photo=` auto-opens the viewer there).
     */
    private String urlFor(final ChatNotification notification) {
        final String photoKey = notification.getChannelId() == null
                ? null : notification.getChannelId().photoKeyOrNull();
        if (photoKey == null) {
            return chatUrl(notification.getTripId());
        }
        final String tripId = notification.getTripId() == null ? "" : notification.getTripId();
        return baseUrlFor(tripId) + "/trip/tripMedia.jsf?trip=" + tripId
                + "&photo=" + URLEncoder.encode(photoKey, StandardCharsets.UTF_8);
    }

    /**
     * The quoted body, or nothing at all.
     *
     * <p>A short-retention channel sends a content-free notification: an inbox keeps a message for years, so putting
     * the body in an email from a channel configured to forget it in an hour makes the content outlive the retention
     * policy in a place no administrator can reach. {@link ChatNotification#hasSnippet()} carries that decision from
     * the builder, which is the only place that knows the channel's settings.
     */
    private MailTemplates.Raw snippetBlock(final ChatNotification notification) {
        if (!notification.hasSnippet()) {
            return new MailTemplates.Raw("");
        }
        return new MailTemplates.Raw("<blockquote style=\"border-left:3px solid #6c8;padding-left:0.75rem;"
                + "color:#444;margin:1rem 0;\">" + ChatBodyHtml.html(notification.getSnippet())
                + "</blockquote>");
    }

    /** The subject, addressed to the person ("you") or naming the family member a manager is told about. */
    static String subjectFor(final ChatNotification notification, final Person onBehalfOf) {
        final String trip = notification.getTripTitle() == null ? "your trip" : notification.getTripTitle();
        final String whom = onBehalfOf == null ? "you" : OnBehalf.nameOf(onBehalfOf);
        final String whose = onBehalfOf == null ? "your" : OnBehalf.possessiveOf(onBehalfOf);
        if (notification.getReason() == ChatNotification.Reason.ADMIN_ANNOUNCEMENT) {
            return onBehalfOf == null ? "Announcement in the " + trip + " chat"
                    : "Announcement for " + whom + " in the " + trip + " chat";
        }
        final String author = notification.getAuthorName() == null ? "Someone" : notification.getAuthorName();
        if (notification.getReason() == ChatNotification.Reason.REPLY) {
            return author + " replied to " + whom + " in the " + trip + " chat";
        }
        if (notification.getReason() == ChatNotification.Reason.PHOTO_COMMENT) {
            return author + " commented on " + whose + " photo from the " + trip + " trip";
        }
        if (isPhotoNotification(notification)) {
            return author + " mentioned " + whom + " on a photo from the " + trip + " trip";
        }
        return author + " mentioned " + whom + " in the " + trip + " chat";
    }

    private boolean claim(final String dedupeKey) {
        return cacheClient.tryAcquireLock(
                CacheKeys.chatNotifySentKey(dedupeKey), CacheKeys.CHAT_NOTIFY_SENT_TTL);
    }

    String chatUrl(final String tripId) {
        return baseUrlFor(tripId) + "/trip/chat.jsf?trip=" + (tripId == null ? "" : tripId);
    }

    /**
     * The site the link belongs on: the trip's organization's own site when it has one, else the chat
     * base-URL setting. Derived from the TRIP -- this runs off-request under the system context, where no
     * host is bound, and the recipient must land on the site their trip lives on.
     */
    private String baseUrlFor(final String tripId) {
        final org.paulsens.trip.model.Trip trip = (tripId == null || tripId.isBlank()) ? null
                : DAO.getInstance().getTrip(tripId, Cached.YES).orElse(null);
        return SiteUrls.baseUrlForTrip(trip, KnownSettings.CHAT_MAIL_BASE_URL, config);
    }

    private String from() {
        return new org.paulsens.trip.action.MailAddressCommands(config)
                .from(KnownSettings.CHAT_MAIL_FROM);
    }

    /**
     * Reply-To resolves per notification: in {@code org} mode it is the owning organization's contact
     * email, so the trip is looked up from the notification (null-safe -- photo/support channels and a
     * deleted trip fall back to the site email).
     */
    private String replyTo(final ChatNotification notification) {
        final String tripId = notification.getTripId();
        final org.paulsens.trip.model.Trip trip = (tripId == null || tripId.isBlank()) ? null
                : DAO.getInstance().getTrip(tripId, Cached.YES).orElse(null);
        return new org.paulsens.trip.action.MailAddressCommands(config)
                .replyTo(KnownSettings.CHAT_MAIL_REPLY_TO, trip);
    }
}
