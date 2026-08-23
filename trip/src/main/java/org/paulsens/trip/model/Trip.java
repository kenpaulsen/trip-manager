package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.sun.jsft.util.Util;
import jakarta.faces.context.FacesContext;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.StructuredTaskScope;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.cache.Cached;

@Data
@Builder
@AllArgsConstructor
public final class Trip implements Serializable {
    /**
     * Pinned so the computed UID can never drift again: a field change on this class once invalidated
     * every Kryo-serialized session at deploy (2026-08-14 outage). Trip itself is banned from sessions
     * now (SessionScopePolicyIT), but the pin closes the class of accident for anything that slips.
     */
    private static final long serialVersionUID = 1L;

    @JsonProperty("id")
    private String id;   // Trip ID
    @JsonProperty("title")
    private String title;                               // Title of trip
    @JsonProperty("openToPublic")
    private Boolean openToPublic;                       // True if people can register themselves
    /**
     * Whether this trip has a chat at all. Stored as a nullable Boolean, read through the accessor below.
     *
     * <p>Null means enabled, because chat is <b>opt-out</b>: every trip that existed before this setting has no
     * value stored, and those trips already have working chats with messages in them. A primitive here would
     * have read as {@code false} for every one of them and silently taken the tab away.
     */
    @JsonProperty("chatEnabled")
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private Boolean chatEnabled;
    @JsonProperty("description")
    private String description;                         // Describes the trip
    @JsonProperty("startDate")
    private LocalDateTime startDate;                    // Start of trip
    @JsonProperty("endDate")
    private LocalDateTime endDate;                      // End of trip
    @JsonProperty("people")
    private List<Person.Id> people;                     // UserIds
    @JsonProperty("regLimit")
    private Integer regLimit;                           // Number of people allowed on the trip (soft limit)
    @JsonProperty("provider")
    private String provider;                            // Who is offering the pilgrimage (i.e. "CFPW")
    @JsonProperty("lang")
    private Language language;                          // Language of the pilgrimage
    @JsonProperty("estPrice")
    private String estimatedPrice;                      // Estimated price (non-binding)
    @JsonProperty("director")
    private String director;                            // The leader of the trip (i.e. Spiritual Director)
    @JsonProperty("guide")
    private String localGuide;                          // The local guide of the trip
    @JsonProperty("facilitators")
    private String facilitators;                        // The facilitators or "organizers" of the trip;
    @JsonProperty("flyerUrl")
    private String flyerUrl;                            // Optional printable flyer (PDF or image) for the trip
    @JsonProperty("nonHostedTripUrl")
    private String nonHostedTripUrl;                    // For non-hosted trips, a URL for more info
    @JsonProperty("nonHostedRegNumber")
    private Integer nonHostedRegNumber;                 // For non-hosted trips, the number of people enrolled
    /**
     * The trip's event ids -- the authoritative stored list (the row and every cache blob store ids only;
     * event bodies live in the {@code trip_events} table). Events resolve lazily on the first
     * {@link #getTripEvents()} call per instance. They used to resolve inside a Jackson converter on EVERY
     * materialization, which made "deserialize a Trip" silently cost one DAO read per event -- the traffic
     * amplifier behind the 2026-08-18 cache incident. Serialized through the accessor pair below so a
     * failed event read can never shrink this list on save.
     */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private List<String> tripEventIds;
    /** Resolved event instances, memoized per instance; null until first access. Never stored. */
    @JsonIgnore
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private transient List<TripEvent> resolvedEvents;
    /** Ids whose event read failed during resolution -- preserved so a later save cannot drop them. */
    @JsonIgnore
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private transient List<String> unresolvedIds;
    @JsonProperty("regOptions")
    private List<RegistrationOption> regOptions;        // Registration page questions
    @JsonProperty("badgeImages")
    private List<BadgeImage> badgeImages;               // Manager-uploaded itinerary-badge pictures
    /**
     * The {@link Organization} (tenancy boundary) offering this trip; null on legacy rows until the org
     * migration backfills. While both exist, {@link #provider} is a denormalized DISPLAY string synced from
     * the org's name on save ({@code TripCommands.saveTrip}) for the public renderers
     * ({@code pilgrimageRow.xhtml}); {@link #isCfpw()} keys on the org itself and only falls back to
     * provider for org-less legacy rows.
     */
    @JsonProperty("orgId")
    private String orgId;
    /**
     * Trip-level payment configuration overrides; null fields inherit from the org's defaults and then the
     * site settings (resolved by {@code OrgCommands.effectivePaymentConfig}). Read through the lazy getter
     * (the {@code getRegOptions} pattern) so the trip editor's dialog can bind before anything was saved.
     */
    @JsonProperty("paymentConfig")
    @Getter(AccessLevel.NONE)
    private TripPaymentConfig paymentConfig;

    private Trip() {
    }

    /**
     * Whether the chat tab appears for this trip.
     *
     * <p>Hand-written rather than generated, and paired with a {@code boolean} setter, so the bean property is a
     * plain {@code boolean} both ways: a {@code Boolean} getter would hand EL a null for every pre-existing trip,
     * which reads as false and hides the tab. Turning chat off hides it everywhere and refuses the API; it does
     * not delete anything, so turning it back on restores the conversation intact.
     */
    public boolean getChatEnabled() {
        return chatEnabled == null || chatEnabled;
    }

    public void setChatEnabled(final boolean enabled) {
        this.chatEnabled = enabled;
    }

    /**
     * The owning {@link Organization} resolved from {@link #orgId}, or null. A derived DAO-backed property
     * (the {@code Person.getTrips()} precedent) so the org autocomplete can bind straight to the edit draft
     * -- the picker's value is the Organization, the stored field stays the id string.
     */
    @JsonIgnore
    public Organization getOrganization() {
        if (orgId == null || orgId.isBlank()) {
            return null;
        }
        return DAO.getInstance().getOrganization(Organization.Id.from(orgId), Cached.YES).orElse(null);
    }

    public void setOrganization(final Organization organization) {
        this.orgId = (organization == null) ? null : organization.getId().getValue();
    }

    public TripPaymentConfig getPaymentConfig() {
        if (paymentConfig == null) {
            paymentConfig = new TripPaymentConfig();
        }
        return paymentConfig;
    }

    public List<Person.Id> getPeople() {
        if (people == null) {
            people = new ArrayList<>();
        }
        return people;
    }

    public void setPeople(final List<Person.Id> people) {
        this.people = new ArrayList<>(people);
    }

    /**
     * The trip's events, resolved from {@link #tripEventIds} on FIRST call and memoized: every caller of
     * this instance gets the SAME mutable list, so the mutate-then-{@code saveTrip} contract holds exactly
     * as it did when deserialization materialized the objects -- and a trip whose events are never touched
     * never pays the fan-out. Must stay {@code @JsonIgnore}: the JSON property "tripEvents" is the id list
     * (accessor pair below), and this getter's bean property would collide with it and re-introduce object
     * serialization.
     */
    @JsonIgnore
    public List<TripEvent> getTripEvents() {
        if (resolvedEvents == null) {
            resolveEvents();
        }
        return resolvedEvents;
    }

    /** The caller supplies the full authoritative list; any unresolved ids are deliberately discarded. */
    public void setTripEvents(final List<TripEvent> events) {
        this.resolvedEvents = new ArrayList<>(events);
        this.unresolvedIds = null;
        this.tripEventIds = eventIds(this.resolvedEvents);
    }

    /**
     * The resolved instances if resolution ever ran on this instance, else null. Save paths use this to
     * skip trips that never materialized their events: unresolved copies cannot carry mutations, and
     * resolving just to rewrite identical rows would reintroduce the per-save fan-out.
     */
    @JsonIgnore
    public List<TripEvent> getResolvedTripEvents() {
        return resolvedEvents;
    }

    private void resolveEvents() {
        final List<String> ids = (tripEventIds == null) ? List.of() : List.copyOf(tripEventIds);
        final List<TripEvent> events = new ArrayList<>(ids.size());
        final List<String> failed = new ArrayList<>();
        // Fork per id: cache misses for a trip's events resolve concurrently (a 50-event trip is one round
        // trip's latency, not 50), each on a virtual thread, and the scope outlives nothing.
        try (var scope = StructuredTaskScope.open()) {
            final List<StructuredTaskScope.Subtask<TripEvent>> tasks = new ArrayList<>(ids.size());
            for (final String eventId : ids) {
                tasks.add(scope.fork(() -> DAO.getInstance().getTripEvent(eventId, Cached.YES)));
            }
            scope.join();
            // join() reports an interrupt only when it actually parks; once every fork has finished it
            // returns normally and an interrupt set by our caller goes unseen. Check it explicitly so an
            // interrupted resolve always fails the same way instead of by scheduling luck.
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Interrupted while loading trip events");
            }
            for (int i = 0; i < ids.size(); i++) {
                collectResolved(ids.get(i), tasks.get(i).get(), events, failed);
            }
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while loading trip events", ex);
        }
        this.resolvedEvents = events;
        this.unresolvedIds = failed;
    }

    private static void collectResolved(final String eventId, final TripEvent event,
            final List<TripEvent> events, final List<String> failed) {
        if (event == null) {
            failed.add(eventId);
        } else {
            events.add(event);
        }
    }

    /**
     * The JSON property {@code "tripEvents"}: ids only, exactly the shape the row and cache blobs have
     * always had (no data migration). An untouched instance round-trips its stored ids unchanged; a
     * resolved instance serializes its current events' ids PLUS any ids whose read failed -- the old
     * converter dropped those silently, and the next save made the loss permanent.
     */
    /**
     * The raw event-row ids, resolved or not. The delete cascade needs these BEFORE the trip row goes --
     * {@code trip_events} rows carry no tripId, so this list is the only way to ever find them again.
     */
    @JsonIgnore
    public List<String> getTripEventIds() {
        return serializedEventIds().stream().toList();
    }

    @JsonProperty("tripEvents")
    private List<String> serializedEventIds() {
        if (resolvedEvents == null) {
            return (tripEventIds == null) ? Collections.emptyList() : tripEventIds;
        }
        final List<String> ids = eventIds(resolvedEvents);
        if (unresolvedIds != null) {
            ids.addAll(unresolvedIds);
        }
        return ids;
    }

    @JsonProperty("tripEvents")
    private void setSerializedEventIds(final List<String> ids) {
        this.tripEventIds = (ids == null) ? new ArrayList<>() : new ArrayList<>(ids);
    }

    private static List<String> eventIds(final List<TripEvent> events) {
        return events.stream().map(TripEvent::getId).collect(Collectors.toList());
    }

    /** Keeps the stored id list in step after a structural event mutation on a resolved instance. */
    private void syncEventIds() {
        if (resolvedEvents != null) {
            this.tripEventIds = serializedEventIds();
        }
    }

    public List<RegistrationOption> getRegOptions() {
        if (regOptions == null) {
            regOptions = new ArrayList<>();
        }
        return regOptions;
    }

    public void setRegOptions(final List<RegistrationOption> options) {
        this.regOptions = new ArrayList<>(options);
    }

    public List<BadgeImage> getBadgeImages() {
        if (badgeImages == null) {
            badgeImages = new ArrayList<>();
        }
        return badgeImages;
    }

    public void setBadgeImages(final List<BadgeImage> badgeImages) {
        this.badgeImages = new ArrayList<>(badgeImages);
    }

    /** This trip's badge image for the given object key, or null — the belongs-to-THIS-trip check. */
    @JsonIgnore
    public BadgeImage getBadgeImage(final String key) {
        if (key == null) {
            return null;
        }
        return getBadgeImages().stream().filter(img -> key.equals(img.getKey())).findAny().orElse(null);
    }

    /**
     * Returns {@code true} if the the given person can join this Trip. This requires the Person to not already be on
     * the trip, and for the trip to not yet be started.
     */
    public boolean canJoin(final Person.Id userId) {
        return !people.contains(userId) && startDate.isAfter(LocalDateTime.now());
    }

    public String addTripEvent(
            final TripEvent.Type type,
            final String title,
            final String notes,
            final LocalDateTime start,
            final LocalDateTime end) {
        final List<TripEvent> events = getTripEvents();
        // Very simple validation check...
        events.stream().filter(te -> matchingTE(te, title, start)).findAny().ifPresent(te -> {
            throw new IllegalStateException(
                    "Trip Event with title (" + title + ") and date (" + start + ") already exists!");
        });
        // Add it
        final String id = UUID.randomUUID().toString();
        events.add(new TripEvent(id, type, title, notes, start, end, null, null));
        events.sort(Comparator.comparing(TripEvent::getStart));
        syncEventIds();
        return id;
    }

    /**
     * This trip's own instance of an event, by id.
     *
     * <p>Identity matters here, not equality. {@code saveTrip} serializes the objects held in {@link #tripEvents},
     * so a change made to any <em>other</em> instance of the same event -- one from a converter, a DAO read,
     * anywhere -- is not part of what gets written. Anything meaning to modify an event as part of saving the
     * trip must reach it through this, not through a lookup that returns a fresh copy.
     */
    @JsonIgnore
    public TripEvent getTripEvent(final String teId) {
        return getTripEvents().stream().filter(e -> e.getId().equals(teId)).findAny().orElse(null);
    }

    public List<TripEvent> getTripEventsForUser(final Person.Id userId) {
        return getTripEvents().stream().filter(te -> te.getParticipants().contains(userId)).toList();
    }

    /**
     * The title without its leading "sort prefix" -- editors date-prefix titles ({@code "2026 Sep: Holy
     * Angels"}) so lists sort chronologically, but public pages want just the name. Strips one leading
     * segment ending in a colon when that colon falls within the first 12 characters; any other title
     * (no colon, or a colon later in a real name) is returned whole. Replaces the old
     * {@code title.substring(10)} page hack, which threw on short titles.
     */
    @JsonIgnore
    public String getShortTitle() {
        if (title == null) {
            return "";
        }
        return title.replaceFirst("^\\s*[^:]{1,12}:\\s*", "");
    }

    /**
     * Whether CFPW hosts this trip (vs. an external provider's pilgrimage we merely list). Decided by the
     * owning org's {@link Organization#getShortName() short name}, never by {@link #provider}: provider is a
     * display string re-synced from the org's full NAME on every save, and the production CFPW org is named
     * "Center for Peace West" -- keying recognition on provider silently dropped a trip from the CFPW badge
     * and sidebar the first time it was re-saved after the org migration (2026-08). The provider compare
     * remains only as the fallback for legacy rows that predate {@link #orgId}.
     */
    @JsonIgnore
    public boolean isCfpw() {
        final Organization owner = getOrganization();
        if (owner != null) {
            return "CFPW".equalsIgnoreCase(owner.getShortName());
        }
        return "CFPW".equalsIgnoreCase(provider);
    }

    /** Whether this is an externally-hosted pilgrimage: registration happens at {@link #nonHostedTripUrl}. */
    @JsonIgnore
    public boolean isExternal() {
        return nonHostedTripUrl != null && !nonHostedTripUrl.isBlank();
    }

    /** The registration count to display: the externally-reported number when set, else our roster size. */
    @JsonIgnore
    public int getShownRegCount() {
        return nonHostedRegNumber != null ? nonHostedRegNumber : getPeople().size();
    }

    /**
     * The start date as a plain ISO date ({@code 2026-08-10}), for {@code data-} attributes the sidebar's
     * countdown script reads. Deliberately date-only and zone-naive: the BROWSER computes "days until" in
     * its own timezone, so the server never has to know where the viewer is.
     */
    @JsonIgnore
    public String getStartDateIso() {
        return startDate == null ? "" : startDate.toLocalDate().toString();
    }

    /**
     * The start month's abbreviation in this trip's own language ("Sep" / "sep"), for compact sidebar
     * labels. Unlike {@link #getTripDateRange()}, which renders in the <em>viewer's</em> locale, a Spanish
     * pilgrimage's label should read Spanish for everyone.
     */
    @JsonIgnore
    public String getStartMonthAbbrev() {
        if (startDate == null) {
            return "";
        }
        final Locale locale = (language == null ? Language.English : language).getLocale();
        return startDate.getMonth().getDisplayName(TextStyle.SHORT, locale);
    }

    @JsonIgnore
    public String getTripDateRange() {
        final Locale locale = Util.getLocale(FacesContext.getCurrentInstance());
        final String startMonth = startDate.getMonth().getDisplayName(TextStyle.SHORT, locale) + ' ';
        final String endMonth = (startDate.getMonth() == endDate.getMonth()) ? "" :
                endDate.getMonth().getDisplayName(TextStyle.SHORT, locale) + ' ';
        return startMonth + startDate.getDayOfMonth() + " - "
                + endMonth + endDate.getDayOfMonth() + ", " + endDate.getYear();
    }

    @JsonIgnore
    public void deleteTripEvent(final TripEvent te) {
        getTripEvents().remove(te);
        syncEventIds();
    }

    public void editTripEvent(final TripEvent newTE) {
        final List<TripEvent> events = getTripEvents();
        // Ensure we have the TripEvent to edit
        final TripEvent oldTE = events.stream().filter(e -> e.getId().equals(newTE.getId())).findAny()
                .orElseThrow(() -> new IllegalArgumentException("TripEvent id (" + newTE.getId() + ") not found!"));
        events.remove(oldTE);
        events.add(newTE);
        events.sort(Comparator.comparing(TripEvent::getStart));
        syncEventIds();
    }

    public void addTripOption() {
        getRegOptions().add(
                new RegistrationOption(getRegOptions().size(), "New Option", "New Option Description", false));
    }

    private boolean matchingTE(final TripEvent te, final String title, final LocalDateTime date) {
        return title.equals(te.getTitle()) && date.equals(te.getStart());
    }

    public static class TripBuilder {
        // Set TripBuilder values here to provide a default values
        private String id = UUID.randomUUID().toString();   // Trip ID
        private Boolean openToPublic = Boolean.TRUE;        // True if people can register themselves
        private LocalDateTime startDate = LocalDateTime.now().plusDays(90);     // Start of trip
        private LocalDateTime endDate = LocalDateTime.now().plusDays(100);      // Start of trip
        private List<Person.Id> people = new ArrayList<>();
        private List<String> tripEventIds = new ArrayList<>();
        private List<TripEvent> resolvedEvents;
        private List<RegistrationOption> regOptions = new ArrayList<>();
        private List<BadgeImage> badgeImages = new ArrayList<>();

        public TripBuilder id(final String id) {
            this.id = (id == null) ? UUID.randomUUID().toString() : id;
            return this;
        }
        public TripBuilder openToPublic(final Boolean isOpen) {
            this.openToPublic = (isOpen == null) ? Boolean.FALSE : isOpen;
            return this;
        }
        public TripBuilder startDate(final LocalDateTime date) {
            this.startDate = (date == null) ? LocalDateTime.now().plusDays(90) : date;
            return this;
        }
        public TripBuilder endDate(final LocalDateTime date) {
            this.endDate = (date == null) ? LocalDateTime.now().plusDays(100) : date;
            return this;
        }
        public TripBuilder people(final List<Person.Id> people) {
            this.people = (people == null) ? new ArrayList<>() : new ArrayList<>(people);
            return this;
        }
        /** Event OBJECTS: the built trip is resolved (FakeData/tests semantics -- save persists them). */
        public TripBuilder tripEvents(final List<TripEvent> tripEvents) {
            final List<TripEvent> events = (tripEvents == null) ? new ArrayList<>() : new ArrayList<>(tripEvents);
            this.resolvedEvents = events;
            this.tripEventIds = events.stream().map(TripEvent::getId).collect(Collectors.toList());
            return this;
        }

        /** Event IDS only: the built trip is unresolved and resolves lazily on first access. */
        public TripBuilder tripEventIds(final List<String> ids) {
            this.tripEventIds = (ids == null) ? new ArrayList<>() : new ArrayList<>(ids);
            this.resolvedEvents = null;
            return this;
        }
        public TripBuilder regOptions(final List<RegistrationOption> regOptions) {
            this.regOptions = (regOptions == null) ? new ArrayList<>() : new ArrayList<>(regOptions);
            return this;
        }
        public TripBuilder badgeImages(final List<BadgeImage> badgeImages) {
            this.badgeImages = (badgeImages == null) ? new ArrayList<>() : new ArrayList<>(badgeImages);
            return this;
        }
    }

}