package org.paulsens.trip.model.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import lombok.Builder;
import lombok.Value;

/**
 * Per-channel operational knobs. One nullable {@link #retentionSeconds} replaces a persistence-mode enum.
 * {@link #retentionDaysAfterTripEnd} deletes; {@link #archiveAfterTripEndDays} freezes — keep them distinct.
 *
 * <p><b>Defaults live in {@link ChatSettingsBuilder}, not only in the constructor.</b> The constructor normalizes
 * nulls, which covers deserialization -- but a Lombok builder over {@code int}/{@code boolean} fields initializes
 * them to {@code 0}/{@code false} and autoboxes those to non-null, so the constructor's null branch never fires.
 * Without the hand-written builder below, {@code builder().build()} silently produced
 * {@code maxMessageChars = 0} (rejects every message), {@code burstLimit = 0} (mutes the channel through a
 * control not labelled "mute"), and {@code fullHistoryForNewMembers = false} (hides all history) -- three
 * different outages from one omission. {@code ChatSettingsBuilderTest} pins this.
 */
@Value
@Builder(toBuilder = true)
public class ChatSettings implements Serializable {

    public static final int DEFAULT_BUFFER_MINUTES = 60;
    public static final int DEFAULT_BUFFER_MAX_MESSAGES = 300;
    public static final int DEFAULT_MAX_MESSAGE_CHARS = 4000;
    public static final int DEFAULT_ARCHIVE_AFTER_TRIP_END_DAYS = 90;
    public static final int DEFAULT_BURST_LIMIT = 5;
    public static final int DEFAULT_BURST_WINDOW_SECONDS = 10;
    public static final int DEFAULT_SUSTAINED_LIMIT = 60;
    public static final int DEFAULT_SUSTAINED_WINDOW_SECONDS = 300;
    public static final int DEFAULT_MAX_ATTACHMENTS_PER_MESSAGE = 10;
    public static final long DEFAULT_MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024;

    public enum PostPolicy {
        ALL_MEMBERS,
        ADMINS_ONLY,
        /** Reserved; not enforced in v1. */
        MODERATED
    }

    /** null = forever; 0 = ephemeral (lives bufferMinutes); N = TTL seconds from send. */
    Long retentionSeconds;
    /** null = never auto-delete after trip end. Distinct from archive. */
    Integer retentionDaysAfterTripEnd;
    PostPolicy postPolicy;
    int bufferMinutes;
    int bufferMaxMessages;
    int maxMessageChars;
    int maxAttachmentsPerMessage;
    long maxAttachmentBytes;
    int slowModeSeconds;
    int archiveAfterTripEndDays;
    boolean allowReactions;
    boolean allowMedia;
    boolean allowEdit;
    boolean fullHistoryForNewMembers;
    int burstLimit;
    int burstWindowSeconds;
    int sustainedLimit;
    int sustainedWindowSeconds;
    /**
     * Chat pane background, as a CSS colour. The channel's default; a member may override it for themselves.
     * Null means the stylesheet's own colour.
     */
    String backgroundColor;
    /**
     * Chat pane background image, {@code http}/{@code https} only. Rendered at 50% transparency.
     *
     * <p><b>Mutually exclusive with the colour above</b>, matching {@code ChatAppearance}: an image covers the
     * pane, so a colour under one never appears and the administrator who set it has nothing on screen telling
     * them why. Setting both keeps the colour and drops this.
     *
     * <p>Scheme-checked on save for the same reason {@code TripLink} is: a stored {@code javascript:} URL is
     * stored XSS with an administrator as its author.
     */
    String backgroundImageUrl;

    @JsonCreator
    public ChatSettings(
            @JsonProperty("retentionSeconds") final Long retentionSeconds,
            @JsonProperty("retentionDaysAfterTripEnd") final Integer retentionDaysAfterTripEnd,
            @JsonProperty("postPolicy") final PostPolicy postPolicy,
            @JsonProperty("bufferMinutes") final Integer bufferMinutes,
            @JsonProperty("bufferMaxMessages") final Integer bufferMaxMessages,
            @JsonProperty("maxMessageChars") final Integer maxMessageChars,
            @JsonProperty("maxAttachmentsPerMessage") final Integer maxAttachmentsPerMessage,
            @JsonProperty("maxAttachmentBytes") final Long maxAttachmentBytes,
            @JsonProperty("slowModeSeconds") final Integer slowModeSeconds,
            @JsonProperty("archiveAfterTripEndDays") final Integer archiveAfterTripEndDays,
            @JsonProperty("allowReactions") final Boolean allowReactions,
            @JsonProperty("allowMedia") final Boolean allowMedia,
            @JsonProperty("allowEdit") final Boolean allowEdit,
            @JsonProperty("fullHistoryForNewMembers") final Boolean fullHistoryForNewMembers,
            @JsonProperty("burstLimit") final Integer burstLimit,
            @JsonProperty("burstWindowSeconds") final Integer burstWindowSeconds,
            @JsonProperty("sustainedLimit") final Integer sustainedLimit,
            @JsonProperty("sustainedWindowSeconds") final Integer sustainedWindowSeconds,
            @JsonProperty("backgroundColor") final String backgroundColor,
            @JsonProperty("backgroundImageUrl") final String backgroundImageUrl) {
        this.retentionSeconds = retentionSeconds;
        this.retentionDaysAfterTripEnd = retentionDaysAfterTripEnd;
        this.postPolicy = postPolicy == null ? PostPolicy.ALL_MEMBERS : postPolicy;
        this.bufferMinutes = bufferMinutes == null ? DEFAULT_BUFFER_MINUTES : bufferMinutes;
        this.bufferMaxMessages = bufferMaxMessages == null ? DEFAULT_BUFFER_MAX_MESSAGES : bufferMaxMessages;
        this.maxMessageChars = maxMessageChars == null ? DEFAULT_MAX_MESSAGE_CHARS : maxMessageChars;
        // Media landed (P4): unset means the defaults, and 0 attachments is a real admin choice ("text
        // only") rather than the reserved-field marker it was in v1.
        //
        // The v1 FINGERPRINT below exists because production channel rows DID predate this (the 8/5-8/6
        // deploys shipped chat, and every channel row froze the reserved fields as false/0/0 -- which then
        // beat the new defaults and hid the Photo button on every live trip). That exact triple can only be
        // the old serialized default: no admin UI has ever been able to write these three fields, and a
        // modern "photos off" writes allowMedia=false WITH the new caps, a different shape. So the triple is
        // read as "reserved, never chosen" and upgraded; everything else is honoured as stored.
        final boolean v1ReservedMedia = Boolean.FALSE.equals(allowMedia)
                && maxAttachmentsPerMessage != null && maxAttachmentsPerMessage == 0
                && maxAttachmentBytes != null && maxAttachmentBytes == 0L;
        this.maxAttachmentsPerMessage = (maxAttachmentsPerMessage == null || v1ReservedMedia)
                ? DEFAULT_MAX_ATTACHMENTS_PER_MESSAGE : Math.max(0, maxAttachmentsPerMessage);
        this.maxAttachmentBytes = (maxAttachmentBytes == null || maxAttachmentBytes <= 0)
                ? DEFAULT_MAX_ATTACHMENT_BYTES : maxAttachmentBytes;
        this.slowModeSeconds = slowModeSeconds == null ? 0 : slowModeSeconds;
        this.archiveAfterTripEndDays = archiveAfterTripEndDays == null
                ? DEFAULT_ARCHIVE_AFTER_TRIP_END_DAYS : archiveAfterTripEndDays;
        this.allowReactions = allowReactions == null || allowReactions;
        this.allowMedia = v1ReservedMedia || allowMedia == null || allowMedia;
        this.allowEdit = allowEdit == null || allowEdit;
        this.fullHistoryForNewMembers = fullHistoryForNewMembers == null || fullHistoryForNewMembers;
        this.burstLimit = burstLimit == null ? DEFAULT_BURST_LIMIT : burstLimit;
        this.burstWindowSeconds = burstWindowSeconds == null ? DEFAULT_BURST_WINDOW_SECONDS : burstWindowSeconds;
        this.sustainedLimit = sustainedLimit == null ? DEFAULT_SUSTAINED_LIMIT : sustainedLimit;
        this.sustainedWindowSeconds = sustainedWindowSeconds == null
                ? DEFAULT_SUSTAINED_WINDOW_SECONDS : sustainedWindowSeconds;
        this.backgroundColor = blankToNull(backgroundColor);
        this.backgroundImageUrl = this.backgroundColor == null ? blankToNull(backgroundImageUrl) : null;
    }

    /** Blank and null mean the same thing here — "not set" — and a form posts blank, not null. */
    private static String blankToNull(final String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    public static ChatSettings defaults() {
        return new ChatSettings(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Hand-written so every primitive field carries its default (see the class comment for why the constructor
     * cannot cover this). Lombok fills in the setters and {@code build()} around these initializers. Any new
     * primitive field added above must be initialized here too.
     */
    public static class ChatSettingsBuilder {
        private int bufferMinutes = DEFAULT_BUFFER_MINUTES;
        private int bufferMaxMessages = DEFAULT_BUFFER_MAX_MESSAGES;
        private int maxMessageChars = DEFAULT_MAX_MESSAGE_CHARS;
        private int archiveAfterTripEndDays = DEFAULT_ARCHIVE_AFTER_TRIP_END_DAYS;
        private int burstLimit = DEFAULT_BURST_LIMIT;
        private int burstWindowSeconds = DEFAULT_BURST_WINDOW_SECONDS;
        private int sustainedLimit = DEFAULT_SUSTAINED_LIMIT;
        private int sustainedWindowSeconds = DEFAULT_SUSTAINED_WINDOW_SECONDS;
        private boolean allowReactions = true;
        private boolean allowEdit = true;
        private boolean allowMedia = true;
        private boolean fullHistoryForNewMembers = true;
        private int maxAttachmentsPerMessage = DEFAULT_MAX_ATTACHMENTS_PER_MESSAGE;
        private long maxAttachmentBytes = DEFAULT_MAX_ATTACHMENT_BYTES;
        // Deliberately left at the JVM default: slowModeSeconds (0 = off).
    }
}
