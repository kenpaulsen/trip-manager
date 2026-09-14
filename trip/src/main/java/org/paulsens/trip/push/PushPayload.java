package org.paulsens.trip.push;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Value;

/**
 * One notification, transport-neutral, rendered to APNs or Web Push JSON at send time
 * ({@code docs/push-notifications.md} "Wire contract"). Built where the event is understood (the chat
 * notifier, the facade), then handed to {@link PushSender}, which adds what only it knows: the badge count
 * and whether quiet hours make it passive.
 */
@Value
public class PushPayload {

    public static final String KIND_CHAT_MENTION = "chat.mention";
    public static final String KIND_CHAT_REPLY = "chat.reply";
    public static final String KIND_CHAT_PHOTO_COMMENT = "chat.photoComment";
    public static final String KIND_CHAT_ANNOUNCEMENT = "chat.announcement";
    public static final String KIND_CHAT_MESSAGE = "chat.message";
    public static final String KIND_REGISTRATION_APPROVED = "registration.approved";
    public static final String KIND_PAYMENT_RECORDED = "payment.recorded";
    public static final String KIND_SUPPORT_REQUEST = "support.request";
    public static final String KIND_TEST = "test";
    /** The one kind that carries no alert: {@code {"aps":{"content-available":1}}}. */
    public static final String KIND_SILENT = "silent";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    String kind;
    String title;
    String subtitle;
    String body;
    /** Groups notifications in the tray ({@code thread-id} / web {@code tag}); the channel id for chat. */
    String threadId;
    /** App deep link ({@code unitetrip://...}); null when the app has no route for it. */
    String link;
    /** Absolute website URL: what a browser notification opens, and the app's fallback. */
    String url;
    String channelId;
    String messageId;
    /** Absolute URL of a display-sized image: rich push on iOS, {@code image} on the web. */
    String imageUrl;
    /** Web only: the notification icon (the org's logo or the site logo), absolute. */
    String iconUrl;
    /** iOS only, set by the sender: unread chats. Null leaves the badge alone. */
    Integer badge;
    /** {@code apns-collapse-id}: a later push with the same id replaces this one. */
    String collapseId;
    /** Quiet hours: still an alert, but no sound and {@code interruption-level: passive}. */
    boolean passive;

    public PushPayload(final String kind, final String title, final String subtitle, final String body,
            final String threadId, final String link, final String url, final String channelId,
            final String messageId, final String imageUrl, final String iconUrl, final Integer badge,
            final String collapseId, final boolean passive) {
        this.kind = kind;
        this.title = title;
        this.subtitle = subtitle;
        this.body = body;
        this.threadId = threadId;
        this.link = link;
        this.url = url;
        this.channelId = channelId;
        this.messageId = messageId;
        this.imageUrl = imageUrl;
        this.iconUrl = iconUrl;
        this.badge = badge;
        this.collapseId = collapseId;
        this.passive = passive;
    }

    /** An alert with the fields every kind has; the rest come through the {@code with*} copies. */
    public static PushPayload alert(final String kind, final String title, final String subtitle,
            final String body, final String threadId, final String link, final String url) {
        return new PushPayload(kind, title, subtitle, body, threadId, link, url, null, null, null, null, null,
                null, false);
    }

    /** The background refresh: no alert, collapsed so a burst of activity wakes the app once. */
    public static PushPayload silent() {
        return new PushPayload(KIND_SILENT, null, null, null, null, null, null, null, null, null, null, null,
                "refresh", false);
    }

    public boolean isSilent() {
        return KIND_SILENT.equals(kind);
    }

    public PushPayload withChat(final String newChannelId, final String newMessageId) {
        return new PushPayload(kind, title, subtitle, body, threadId, link, url, newChannelId, newMessageId,
                imageUrl, iconUrl, badge, collapseId, passive);
    }

    public PushPayload withImage(final String newImageUrl) {
        return new PushPayload(kind, title, subtitle, body, threadId, link, url, channelId, messageId,
                newImageUrl, iconUrl, badge, collapseId, passive);
    }

    public PushPayload withIcon(final String newIconUrl) {
        return new PushPayload(kind, title, subtitle, body, threadId, link, url, channelId, messageId,
                imageUrl, newIconUrl, badge, collapseId, passive);
    }

    public PushPayload withBadge(final Integer newBadge) {
        return new PushPayload(kind, title, subtitle, body, threadId, link, url, channelId, messageId,
                imageUrl, iconUrl, newBadge, collapseId, passive);
    }

    public PushPayload withPassive(final boolean newPassive) {
        return new PushPayload(kind, title, subtitle, body, threadId, link, url, channelId, messageId,
                imageUrl, iconUrl, badge, collapseId, newPassive);
    }

    /**
     * The APNs JSON. {@code image} and {@code mutable-content} ride only when there is an image: a
     * mutable-content push with nothing to mutate still wakes the service extension for nothing.
     */
    public String toApns() {
        final Map<String, Object> aps = new LinkedHashMap<>();
        if (isSilent()) {
            aps.put("content-available", 1);
            return json(Map.of("aps", aps));
        }
        final Map<String, Object> alert = new LinkedHashMap<>();
        putIfSet(alert, "title", title);
        putIfSet(alert, "subtitle", subtitle);
        putIfSet(alert, "body", body);
        aps.put("alert", alert);
        if (badge != null) {
            aps.put("badge", badge);
        }
        if (!passive) {
            aps.put("sound", "default");
        }
        putIfSet(aps, "thread-id", threadId);
        aps.put("interruption-level", passive ? "passive" : "active");
        if (imageUrl != null) {
            aps.put("mutable-content", 1);
        }
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("aps", aps);
        root.put("kind", kind);
        putIfSet(root, "link", link);
        putIfSet(root, "url", url);
        putIfSet(root, "channelId", channelId);
        putIfSet(root, "messageId", messageId);
        putIfSet(root, "image", imageUrl);
        return json(root);
    }

    /**
     * The Web Push JSON the service worker shows: {@code {title, body, icon, image?, url, tag, kind}}. A web
     * notification has no subtitle line, so the subtitle leads the body.
     */
    public String toWebPush() {
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("title", title == null ? "" : title);
        root.put("body", webBody());
        putIfSet(root, "icon", iconUrl);
        putIfSet(root, "image", imageUrl);
        putIfSet(root, "url", url);
        putIfSet(root, "tag", threadId);
        root.put("kind", kind);
        return json(root);
    }

    String webBody() {
        if (subtitle == null || subtitle.isBlank()) {
            return body == null ? "" : body;
        }
        return body == null || body.isBlank() ? subtitle : subtitle + ": " + body;
    }

    private static void putIfSet(final Map<String, Object> target, final String key, final String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private static String json(final Map<String, Object> root) {
        try {
            return MAPPER.writeValueAsString(root);
        } catch (final JsonProcessingException ex) {
            throw new IllegalStateException("Push payload is not serializable", ex);
        }
    }
}
