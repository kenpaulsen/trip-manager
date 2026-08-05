package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.util.StdConverter;
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
import lombok.Getter;
import lombok.Setter;
import org.paulsens.trip.dynamo.DAO;

@Data
@Builder
@AllArgsConstructor
public final class Trip implements Serializable {
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
    @JsonProperty("nonHostedTripUrl")
    private String nonHostedTripUrl;                    // For non-hosted trips, a URL for more info
    @JsonProperty("nonHostedRegNumber")
    private Integer nonHostedRegNumber;                 // For non-hosted trips, the number of people enrolled
    @JsonProperty("tripEvents")
    @JsonSerialize(converter = TripEventsSerializer.class)
    @JsonDeserialize(converter = TripEventsDeserializer.class)
    private List<TripEvent> tripEvents;                 // The stuff needed to book, airfare, hotel, etc.
    @JsonProperty("regOptions")
    private List<RegistrationOption> regOptions;        // Registration page questions

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

    public List<Person.Id> getPeople() {
        if (people == null) {
            people = new ArrayList<>();
        }
        return people;
    }

    public void setPeople(final List<Person.Id> people) {
        this.people = new ArrayList<>(people);
    }

    public List<TripEvent> getTripEvents() {
        if (tripEvents == null) {
            tripEvents = new ArrayList<>();
        }
        return tripEvents;
    }

    public void setTripEvents(final List<TripEvent> events) {
        this.tripEvents = new ArrayList<>(events);
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
    }

    public void editTripEvent(final TripEvent newTE) {
        final List<TripEvent> events = getTripEvents();
        // Ensure we have the TripEvent to edit
        final TripEvent oldTE = events.stream().filter(e -> e.getId().equals(newTE.getId())).findAny()
                .orElseThrow(() -> new IllegalArgumentException("TripEvent id (" + newTE.getId() + ") not found!"));
        events.remove(oldTE);
        events.add(newTE);
        events.sort(Comparator.comparing(TripEvent::getStart));
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
        private List<TripEvent> tripEvents = new ArrayList<>();
        private List<RegistrationOption> regOptions = new ArrayList<>();

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
        public TripBuilder tripEvents(final List<TripEvent> tripEvents) {
            this.tripEvents = (tripEvents == null) ? new ArrayList<>() : new ArrayList<>(tripEvents);
            return this;
        }
        public TripBuilder regOptions(final List<RegistrationOption> regOptions) {
            this.regOptions = (regOptions == null) ? new ArrayList<>() : new ArrayList<>(regOptions);
            return this;
        }
    }

    static class TripEventsSerializer extends StdConverter<List<TripEvent>, List<String>> {
        @Override
        public List<String> convert(final List<TripEvent> events) {
            if (events == null) {
                return Collections.emptyList();
            }
            return events.stream().map(TripEvent::getId).collect(Collectors.toList());
        }
    }

    static class TripEventsDeserializer extends StdConverter<List<String>, List<TripEvent>> {
        @Override
        public List<TripEvent> convert(final List<String> ids) {
            if (ids == null) {
                return Collections.emptyList();
            }
            // Fork per event id: the fan-out the CF era had, minus the nested joins that once forced the
            // persistence pool to be unbounded. Cache misses for a trip's events resolve concurrently (a
            // 50-event trip is one round trip's latency, not 50), each on a virtual thread whose blocking is
            // free, and the scope guarantees nothing outlives this deserialization.
            try (var scope = StructuredTaskScope.open()) {
                final List<StructuredTaskScope.Subtask<TripEvent>> tasks = new ArrayList<>(ids.size());
                for (final String id : ids) {
                    tasks.add(scope.fork(() -> DAO.getInstance().getTripEvent(id)));
                }
                scope.join();
                final List<TripEvent> events = new ArrayList<>(ids.size());
                for (final StructuredTaskScope.Subtask<TripEvent> task : tasks) {
                    if (task.get() != null) {
                        events.add(task.get());
                    }
                }
                return events;
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while loading trip events", ex);
            }
        }
    }
}