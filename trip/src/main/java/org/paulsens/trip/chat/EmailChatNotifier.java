package org.paulsens.trip.chat;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.action.MailCommands;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;

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
        final String dedupe = ChatNotification.dedupeKeyFor(
                notification.getMessageId(), recipient, channel().name());
        // Claim first. tryAcquireLock returns false when the marker exists, so the first caller wins and a retry or
        // a restart mid-fan-out cannot mail the same person twice.
        if (!claim(dedupe)) {
            return;
        }
        final Person person = DAO.getInstance().getPerson(recipient).join().orElse(null);
        final String to = person == null ? null : mail.formatEmail(person);
        if (to == null) {
            log.debug("Skipping chat notification for {}: no usable email address", recipient);
            return;
        }
        final String body = MailTemplates.render("chat-mention", mentionValues(notification));
        if (body == null) {
            // Template missing or unrenderable. Sending a half-built mail is worse than sending none.
            return;
        }
        mail.send(from(), to, null, replyTo(), subjectFor(notification), body);
    }

    private Map<String, Object> mentionValues(final ChatNotification notification) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("authorName", notification.getAuthorName() == null ? "Someone" : notification.getAuthorName());
        values.put("tripTitle", notification.getTripTitle() == null ? "trip" : notification.getTripTitle());
        values.put("chatUrl", chatUrl(notification.getTripId()));
        values.put("snippetBlock", snippetBlock(notification));
        return values;
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
                + "color:#444;margin:1rem 0;\">" + MailTemplates.escape(notification.getSnippet())
                + "</blockquote>");
    }

    private String subjectFor(final ChatNotification notification) {
        final String trip = notification.getTripTitle() == null ? "your trip" : notification.getTripTitle();
        if (notification.getReason() == ChatNotification.Reason.ADMIN_ANNOUNCEMENT) {
            return "Announcement in the " + trip + " chat";
        }
        return (notification.getAuthorName() == null ? "Someone" : notification.getAuthorName())
                + " mentioned you in the " + trip + " chat";
    }

    private boolean claim(final String dedupeKey) {
        return cacheClient.tryAcquireLock(
                CacheKeys.chatNotifySentKey(dedupeKey), CacheKeys.CHAT_NOTIFY_SENT_TTL).join();
    }

    String chatUrl(final String tripId) {
        final String base = config.getString(KnownSettings.CHAT_MAIL_BASE_URL);
        return base + "/trip/chat.jsf?trip=" + (tripId == null ? "" : tripId);
    }

    private String from() {
        return config.getString(KnownSettings.CHAT_MAIL_FROM);
    }

    private String replyTo() {
        return config.getString(KnownSettings.CHAT_MAIL_REPLY_TO);
    }
}
