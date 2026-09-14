package org.paulsens.trip.push;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.chat.ChatNotification;
import org.paulsens.trip.chat.ChatNotifications;
import org.paulsens.trip.chat.ChatNotifier;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMembership;
import org.paulsens.trip.model.chat.ChatNotifyPref;

/**
 * The push route of the chat notifier chain ({@code ChatNotifications.notifier()}): mentions, replies,
 * photo comments and announcements to everyone whose per-channel {@code pushMode} is not OFF; every-message
 * alerts to the explicit ALL rows the policy layer already selected; then the silent refresh walk that keeps
 * badges fresh on every other phone on the trip.
 *
 * <p>Enabled by {@code push.enabled} alone -- the per-person switch, quiet hours and devices are
 * {@link PushSender}'s gates. Photo-comment preferences are read from the photo's TRIP channel, where people
 * actually manage chat notifications (the mail route's rule).
 */
@Slf4j
public final class PushChatNotifier implements ChatNotifier {

    static final String OPEN_THE_CHAT = "Open the chat to read it";
    static final String PHOTO_BODY = "📷 Photo";

    private final PushSender sender;
    private final ConfigCommands config;

    public PushChatNotifier() {
        this(PushSender.getInstance(), new ConfigCommands());
    }

    public PushChatNotifier(final PushSender sender, final ConfigCommands config) {
        this.sender = sender;
        this.config = config;
    }

    @Override
    public Channel channel() {
        return Channel.PUSH;
    }

    @Override
    public boolean isEnabled() {
        return config.getBoolean(KnownSettings.PUSH_ENABLED);
    }

    @Override
    public void notify(final ChatNotification notification) {
        if (notification == null || notification.getReason() == ChatNotification.Reason.DIGEST
                || notification.getChannelId() == null) {
            return;
        }
        try {
            deliver(notification);
        } catch (final RuntimeException ex) {
            // Never propagate: the message this is about has already been acknowledged to its sender.
            log.warn("Push chat notification failed for {}", notification.getMessageId(), ex);
        }
    }

    private void deliver(final ChatNotification notification) {
        final Trip trip = tripOf(notification.getTripId());
        final PushPayload payload = payloadFor(notification, trip);
        final List<Person.Id> alerted = new ArrayList<>();
        for (final Person.Id recipient : notification.getRecipients()) {
            if (!wantsPush(notification, recipient)) {
                continue;
            }
            final String dedupe = ChatNotification.dedupeKeyFor(notification.getMessageId(), recipient,
                    channel().name());
            final PushSender.Report report = sender.sendAlert(recipient, payload, dedupe);
            if (report.skipped() == null) {
                alerted.add(recipient);
            }
        }
        if (notification.getReason() == ChatNotification.Reason.ALL_MESSAGES && isTripChannel(notification)) {
            silentRefresh(trip, notification, alerted);
        }
    }

    /**
     * The per-channel choice, read from the trip channel (photo threads have no preferences of their own).
     * An implicit member has no row and therefore holds the default, MENTIONS -- not "off".
     */
    private boolean wantsPush(final ChatNotification notification, final Person.Id recipient) {
        final ChatNotifyPref.PushMode mode = modeFor(prefHome(notification), recipient);
        if (mode == ChatNotifyPref.PushMode.OFF) {
            return false;
        }
        return notification.getReason() != ChatNotification.Reason.ALL_MESSAGES
                || mode == ChatNotifyPref.PushMode.ALL;
    }

    private static ChatNotifyPref.PushMode modeFor(final ChatChannel.Id home, final Person.Id recipient) {
        final Optional<ChatMembership> row = DAO.getInstance().getChatMembership(home, recipient, Cached.NO);
        return row.map(ChatMembership::getNotify).map(ChatNotifyPref::getPushMode)
                .orElse(ChatNotifyPref.PushMode.MENTIONS);
    }

    private static ChatChannel.Id prefHome(final ChatNotification notification) {
        final String tripId = notification.getTripId();
        if (notification.getChannelId().photoKeyOrNull() != null && tripId != null) {
            return ChatChannel.Id.forTrip(tripId);
        }
        return notification.getChannelId();
    }

    private static boolean isTripChannel(final ChatNotification notification) {
        return notification.getChannelId().tripIdOrNull() != null
                && notification.getChannelId().photoKeyOrNull() == null;
    }

    /**
     * Everyone on the trip who was not just alerted gets a coalesced background push, so the badge and the
     * unread state on a phone that nobody named still update without opening the app.
     */
    private void silentRefresh(final Trip trip, final ChatNotification notification,
            final List<Person.Id> alerted) {
        if (trip == null) {
            return;
        }
        for (final Person.Id person : ChatNotifications.everyoneIn(trip, notification.getAuthorId())) {
            if (!alerted.contains(person)) {
                sender.sendSilent(person);
            }
        }
    }

    PushPayload payloadFor(final ChatNotification notification, final Trip trip) {
        final String tripId = notification.getTripId();
        final String photoKey = notification.getChannelId().photoKeyOrNull();
        final boolean photo = photoKey != null;
        final String title = notification.getTripTitle() == null ? "Trip chat" : notification.getTripTitle();
        final String link = photo ? PushLinks.photosLink(tripId) : PushLinks.chatLink(tripId);
        final String url = photo ? PushLinks.photoUrl(trip, tripId, photoKey, config)
                : PushLinks.chatUrl(trip, tripId, config);
        return PushPayload.alert(kindOf(notification), title, subtitleOf(notification), bodyOf(notification),
                        notification.getChannelId().getValue(), link, url)
                .withChat(notification.getChannelId().getValue(),
                        notification.getMessageId() == null ? null : notification.getMessageId().getValue())
                .withImage(notification.getImageUrl())
                .withIcon(PushLinks.iconFor(trip, config));
    }

    static String kindOf(final ChatNotification notification) {
        return switch (notification.getReason()) {
            case REPLY -> PushPayload.KIND_CHAT_REPLY;
            case PHOTO_COMMENT -> PushPayload.KIND_CHAT_PHOTO_COMMENT;
            case ADMIN_ANNOUNCEMENT -> PushPayload.KIND_CHAT_ANNOUNCEMENT;
            case ALL_MESSAGES -> PushPayload.KIND_CHAT_MESSAGE;
            default -> PushPayload.KIND_CHAT_MENTION;
        };
    }

    static String subtitleOf(final ChatNotification notification) {
        final String author = notification.getAuthorName() == null ? "Someone" : notification.getAuthorName();
        return switch (notification.getReason()) {
            case REPLY -> author + " replied to you";
            case PHOTO_COMMENT -> author + " commented on your photo";
            case ADMIN_ANNOUNCEMENT -> "Announcement";
            case ALL_MESSAGES -> author;
            default -> author + " mentioned you";
        };
    }

    /** The snippet; the content-free line for a short-retention channel; a camera for a bare photo. */
    static String bodyOf(final ChatNotification notification) {
        if (notification.hasSnippet()) {
            return notification.getSnippet();
        }
        return notification.getImageUrl() != null ? PHOTO_BODY : OPEN_THE_CHAT;
    }

    private static Trip tripOf(final String tripId) {
        if (tripId == null || tripId.isBlank()) {
            return null;
        }
        return DAO.getInstance().getTrip(tripId, Cached.YES).orElse(null);
    }
}
