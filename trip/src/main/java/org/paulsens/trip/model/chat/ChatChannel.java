package org.paulsens.trip.model.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import lombok.Value;
import org.paulsens.trip.model.TripLink;

/**
 * One chat channel. Trip channels use a deterministic id ({@code trip:{tripId}}) so "does this trip have a
 * channel?" is a GetItem and no GSI is needed. DM/TOPIC kinds are reserved for later phases.
 */
@Value
public class ChatChannel implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    public static final int CURRENT_SCHEMA = 1;

    public enum Kind {
        TRIP,
        /** Reserved. Id shape: {@code dm:{lowUserId}:{highUserId}}. */
        DM,
        /** Reserved. */
        TOPIC,
        /** One photo's comment thread. Id shape: {@code photo:{s3Key}}. */
        PHOTO,
        /**
         * The site-wide technical-support channel ({@code support:main}). No trip, no implicit roster:
         * explicit JOINED membership rows ARE the admin list. Requesters post into it without joining.
         */
        SUPPORT
    }

    @Value
    public static class Id implements Serializable, Comparable<Id> {
        @Serial
        private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

        @JsonValue
        String value;

        public static Id from(final String value) {
            return new Id(value);
        }

        public static Id forTrip(final String tripId) {
            if (tripId == null || tripId.isBlank()) {
                throw new IllegalArgumentException("tripId is required");
            }
            return new Id("trip:" + tripId);
        }

        public static Id forPhoto(final String s3Key) {
            if (s3Key == null || s3Key.isBlank()) {
                throw new IllegalArgumentException("s3Key is required");
            }
            return new Id("photo:" + s3Key);
        }

        /** The one site-wide support channel. */
        public static Id forSupport() {
            return new Id("support:main");
        }

        @JsonIgnore
        public boolean isSupport() {
            return value != null && value.startsWith("support:");
        }

        @JsonIgnore
        public String tripIdOrNull() {
            if (value != null && value.startsWith("trip:")) {
                return value.substring("trip:".length());
            }
            return null;
        }

        @JsonIgnore
        public String photoKeyOrNull() {
            if (value != null && value.startsWith("photo:")) {
                return value.substring("photo:".length());
            }
            return null;
        }

        @Override
        public int compareTo(final Id o) {
            return value.compareTo(o.getValue());
        }
    }

    Id id;
    String tripId;
    Kind kind;
    String title;
    String description;
    List<TripLink> links;
    ChatSettings settings;
    Instant created;
    String createdBy;
    /** Stored value; may also be computed from trip end + archiveAfterTripEndDays on read. */
    Instant archivedAt;
    /** PHOTO channels only: the trip channel holding the message this photo was attached to; else null. */
    Id parentChannelId;
    /** PHOTO channels only: the message the photo was attached to; null = no roll-up target. */
    ChatMessage.Id parentMsgId;
    int schemaVersion;

    @JsonCreator
    public ChatChannel(
            @JsonProperty("id") final Id id,
            @JsonProperty("tripId") final String tripId,
            @JsonProperty("kind") final Kind kind,
            @JsonProperty("title") final String title,
            @JsonProperty("description") final String description,
            @JsonProperty("links") final List<TripLink> links,
            @JsonProperty("settings") final ChatSettings settings,
            @JsonProperty("created") final Instant created,
            @JsonProperty("createdBy") final String createdBy,
            @JsonProperty("archivedAt") final Instant archivedAt,
            @JsonProperty("parentChannelId") final Id parentChannelId,
            @JsonProperty("parentMsgId") final ChatMessage.Id parentMsgId,
            @JsonProperty("schemaVersion") final Integer schemaVersion) {
        this.id = id;
        this.tripId = tripId;
        this.kind = kind == null ? Kind.TRIP : kind;
        this.title = title;
        this.description = description;
        this.links = links == null ? List.of() : List.copyOf(links);
        this.settings = settings == null ? ChatSettings.defaults() : settings;
        // EPOCH, not now(): `created` is the history floor backfilled into joinedAt when
        // fullHistoryForNewMembers is turned off, so it has to be stable across reads. Defaulting to now() would
        // hand a different floor to every deserialization of a row that predates the field.
        this.created = created == null ? Instant.EPOCH : created;
        this.createdBy = createdBy;
        this.archivedAt = archivedAt;
        this.parentChannelId = parentChannelId;
        this.parentMsgId = parentMsgId;
        this.schemaVersion = schemaVersion == null ? CURRENT_SCHEMA : schemaVersion;
    }

    /** Pre-photo signature, kept so trip/DM channel call sites don't carry two always-null parent fields. */
    public ChatChannel(final Id id, final String tripId, final Kind kind, final String title,
            final String description, final List<TripLink> links, final ChatSettings settings,
            final Instant created, final String createdBy, final Instant archivedAt,
            final Integer schemaVersion) {
        this(id, tripId, kind, title, description, links, settings, created, createdBy, archivedAt,
                null, null, schemaVersion);
    }

    public ChatChannel withSettings(final ChatSettings newSettings) {
        return new ChatChannel(id, tripId, kind, title, description, links, newSettings,
                created, createdBy, archivedAt, parentChannelId, parentMsgId, schemaVersion);
    }

    public ChatChannel withTitle(final String newTitle) {
        return new ChatChannel(id, tripId, kind, newTitle, description, links, settings,
                created, createdBy, archivedAt, parentChannelId, parentMsgId, schemaVersion);
    }

    public ChatChannel withDescription(final String newDescription) {
        return new ChatChannel(id, tripId, kind, title, newDescription, links, settings,
                created, createdBy, archivedAt, parentChannelId, parentMsgId, schemaVersion);
    }

    public ChatChannel withLinks(final List<TripLink> newLinks) {
        return new ChatChannel(id, tripId, kind, title, description, newLinks, settings,
                created, createdBy, archivedAt, parentChannelId, parentMsgId, schemaVersion);
    }

    public ChatChannel withArchivedAt(final Instant newArchivedAt) {
        return new ChatChannel(id, tripId, kind, title, description, links, settings,
                created, createdBy, newArchivedAt, parentChannelId, parentMsgId, schemaVersion);
    }

    @JsonIgnore
    public boolean isArchived() {
        return archivedAt != null;
    }
}
