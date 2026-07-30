package org.paulsens.trip.model.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
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

    public static final int CURRENT_SCHEMA = 1;

    public enum Kind {
        TRIP,
        /** Reserved. Id shape: {@code dm:{lowUserId}:{highUserId}}. */
        DM,
        /** Reserved. */
        TOPIC
    }

    @Value
    public static class Id implements Serializable, Comparable<Id> {
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

        @JsonIgnore
        public String tripIdOrNull() {
            if (value != null && value.startsWith("trip:")) {
                return value.substring("trip:".length());
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
        this.schemaVersion = schemaVersion == null ? CURRENT_SCHEMA : schemaVersion;
    }

    public ChatChannel withSettings(final ChatSettings newSettings) {
        return new ChatChannel(id, tripId, kind, title, description, links, newSettings,
                created, createdBy, archivedAt, schemaVersion);
    }

    public ChatChannel withTitle(final String newTitle) {
        return new ChatChannel(id, tripId, kind, newTitle, description, links, settings,
                created, createdBy, archivedAt, schemaVersion);
    }

    public ChatChannel withDescription(final String newDescription) {
        return new ChatChannel(id, tripId, kind, title, newDescription, links, settings,
                created, createdBy, archivedAt, schemaVersion);
    }

    public ChatChannel withLinks(final List<TripLink> newLinks) {
        return new ChatChannel(id, tripId, kind, title, description, newLinks, settings,
                created, createdBy, archivedAt, schemaVersion);
    }

    public ChatChannel withArchivedAt(final Instant newArchivedAt) {
        return new ChatChannel(id, tripId, kind, title, description, links, settings,
                created, createdBy, newArchivedAt, schemaVersion);
    }

    @JsonIgnore
    public boolean isArchived() {
        return archivedAt != null;
    }
}
