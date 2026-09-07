package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.io.Serial;
import java.io.Serializable;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;
import lombok.Value;

/**
 * A place people stay: a hotel, a pansion, a retreat house. A row in {@code lodging_accommodations} (PK id).
 *
 * <p><b>Deliberately NOT owned by an organization</b> -- the one exception to the org-tenancy rule, decided
 * 2026-09-06: a hotel is used by every organization that stays there, and its owner will one day log in and
 * maintain the inventory once rather than once per org. {@code orgIds} records which organizations USE it
 * (appended the first time one of their trips creates an offer on it); it grants nothing. Who may EDIT an
 * accommodation is a privilege question ({@code accommodationAdmin} scoped to this id), never a field here.
 *
 * <p>Because it is global, a hotel must be findable before it is duplicated: {@code name}, {@code email},
 * {@code phone}, {@code website} and the postal address are all collected, and
 * {@link #matchesDiscovery} is the normalized comparison the create dialog runs first.
 *
 * <p>Room types, rooms and floor plans live INLINE (one optimistic-version put edits the whole inventory
 * atomically, and a room's type can never dangle across tables). Photos are media-library rows in slot
 * {@code lodging-{id}}; the lists here hold their ids in display order.
 */
@Data
public final class Accommodation implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    private Id id;
    private String name;
    private String description;
    private Address address;
    private String email;
    private String phone;
    private String website;
    private Person.Id contactId;
    private List<String> photoIds;
    private List<RoomType> roomTypes;
    private List<Room> rooms;
    private List<FloorMap> floorMaps;
    private List<Organization.Id> orgIds;
    private Person.Id createdBy;
    private LocalDateTime created;
    private long version;
    /**
     * Setter-populated (outside the creator): a retired hotel leaves pickers but keeps its history. The
     * explicit property name keeps Jackson from letting the {@code @JsonIgnore} on {@link #isRetired()} swallow
     * the stored flag (an is-getter and a get-getter share one property).
     */
    @JsonProperty("retired")
    private Boolean retired;

    @Builder
    @JsonCreator
    public Accommodation(
            @JsonProperty("id") final Id id,
            @JsonProperty("name") final String name,
            @JsonProperty("description") final String description,
            @JsonProperty("address") final Address address,
            @JsonProperty("email") final String email,
            @JsonProperty("phone") final String phone,
            @JsonProperty("website") final String website,
            @JsonProperty("contactId") final Person.Id contactId,
            @JsonProperty("photoIds") final List<String> photoIds,
            @JsonProperty("roomTypes") final List<RoomType> roomTypes,
            @JsonProperty("rooms") final List<Room> rooms,
            @JsonProperty("floorMaps") final List<FloorMap> floorMaps,
            @JsonProperty("orgIds") final List<Organization.Id> orgIds,
            @JsonProperty("createdBy") final Person.Id createdBy,
            @JsonProperty("created") final LocalDateTime created,
            @JsonProperty("version") final long version) {
        this.id = (id == null) ? Id.newInstance() : id;
        this.name = (name == null) ? null : name.trim();
        this.description = description;
        this.address = (address == null) ? new Address() : address;
        this.email = (email == null) ? null : email.trim().toLowerCase(Locale.ROOT);
        this.phone = (phone == null) ? null : phone.trim();
        this.website = (website == null) ? null : website.trim();
        this.contactId = contactId;
        this.photoIds = (photoIds == null) ? new ArrayList<>() : new ArrayList<>(photoIds);
        this.roomTypes = (roomTypes == null) ? new ArrayList<>() : new ArrayList<>(roomTypes);
        this.rooms = (rooms == null) ? new ArrayList<>() : new ArrayList<>(rooms);
        this.floorMaps = (floorMaps == null) ? new ArrayList<>() : new ArrayList<>(floorMaps);
        this.orgIds = (orgIds == null) ? new ArrayList<>() : new ArrayList<>(orgIds);
        this.createdBy = createdBy;
        this.created = created;
        this.version = version;
    }

    public Accommodation() {
        this(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, 0L);
    }

    @JsonIgnore
    public boolean isRetired() {
        return Boolean.TRUE.equals(retired);
    }

    @JsonIgnore
    public RoomType roomType(final String roomTypeId) {
        if (roomTypeId == null) {
            return null;
        }
        for (final RoomType type : roomTypes) {
            if (roomTypeId.equals(type.getId())) {
                return type;
            }
        }
        return null;
    }

    @JsonIgnore
    public Room room(final String roomId) {
        if (roomId == null) {
            return null;
        }
        for (final Room room : rooms) {
            if (roomId.equals(room.getId())) {
                return room;
            }
        }
        return null;
    }

    /** The room's number ("114"), or null when the id is unknown here. */
    @JsonIgnore
    public String roomLabel(final String roomId) {
        final Room room = room(roomId);
        return (room == null) ? null : room.getRoomNumber();
    }

    @JsonIgnore
    public List<Room> roomsOnFloor(final String floor) {
        final List<Room> result = new ArrayList<>();
        for (final Room room : rooms) {
            if (Objects.equals(floor, room.getFloor())) {
                result.add(room);
            }
        }
        return result;
    }

    /** Distinct floors, in first-seen room order, plus any floor that only has a plan image. */
    @JsonIgnore
    public List<String> floors() {
        final Set<String> floors = new LinkedHashSet<>();
        for (final Room room : rooms) {
            if (room.getFloor() != null && !room.getFloor().isBlank()) {
                floors.add(room.getFloor());
            }
        }
        for (final FloorMap map : floorMaps) {
            if (map.getFloor() != null && !map.getFloor().isBlank()) {
                floors.add(map.getFloor());
            }
        }
        return new ArrayList<>(floors);
    }

    @JsonIgnore
    public FloorMap floorMap(final String floor) {
        for (final FloorMap map : floorMaps) {
            if (Objects.equals(floor, map.getFloor())) {
                return map;
            }
        }
        return null;
    }

    @JsonIgnore
    public boolean usedBy(final Organization.Id orgId) {
        return orgId != null && orgIds.contains(orgId);
    }

    /**
     * The discovery test the create dialog runs before minting a second row for the same property: any ONE
     * exact match after normalization (case/space-insensitive name, lower-cased email, digits-only phone,
     * website host without {@code www.}, street+city+zip) says "this may already exist". A warning, not a
     * lock: an admin may knowingly create a sister property that shares a phone.
     */
    @JsonIgnore
    public boolean matchesDiscovery(final String otherName, final String otherEmail, final String otherPhone,
            final String otherWebsite, final Address otherAddress) {
        return sameNonBlank(normalizeName(name), normalizeName(otherName))
                || sameNonBlank(normalizeEmail(email), normalizeEmail(otherEmail))
                || sameNonBlank(normalizePhone(phone), normalizePhone(otherPhone))
                || sameNonBlank(normalizeWebsite(website), normalizeWebsite(otherWebsite))
                || sameNonBlank(normalizeAddress(address), normalizeAddress(otherAddress));
    }

    private static boolean sameNonBlank(final String a, final String b) {
        return a != null && !a.isEmpty() && a.equals(b);
    }

    static String normalizeName(final String value) {
        return (value == null) ? null : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    static String normalizeEmail(final String value) {
        return (value == null) ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    static String normalizePhone(final String value) {
        return (value == null) ? null : value.replaceAll("[^0-9]", "");
    }

    /** The host only, lower-cased, without a leading {@code www.}; a bare "pansion.ba" is accepted as a host. */
    static String normalizeWebsite(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        final String trimmed = value.trim().toLowerCase(Locale.ROOT);
        String host;
        try {
            final URI uri = URI.create(trimmed.contains("://") ? trimmed : "https://" + trimmed);
            host = (uri.getHost() == null) ? trimmed : uri.getHost();
        } catch (final IllegalArgumentException ex) {
            host = trimmed;
        }
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    static String normalizeAddress(final Address value) {
        if (value == null || value.getStreet() == null || value.getStreet().isBlank()) {
            return null;
        }
        return normalizeName(value.getStreet()) + "|" + normalizeName(value.getCity()) + "|"
                + normalizeName(value.getZip());
    }

    @Value
    public static class Id implements Serializable, Comparable<Id> {
        @Serial
        private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

        @JsonValue
        String value;

        public static Id from(final String id) {
            return new Id(id);
        }

        public static Id newInstance() {
            return new Id(UUID.randomUUID().toString());
        }

        @Override
        public int compareTo(final Id o) {
            return value.compareTo(o.getValue());
        }
    }
}
