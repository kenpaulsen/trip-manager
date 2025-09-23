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
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import org.paulsens.trip.dynamo.DAO;

@Data
@Builder
@AllArgsConstructor
public final class Trip implements Serializable {
    @Builder.Default()
    @JsonProperty("id")
    private String id = UUID.randomUUID().toString();   // Trip ID
    @JsonProperty("title")
    private String title;                               // Title of trip
    @Builder.Default()
    @JsonProperty("openToPublic")
    private Boolean openToPublic = Boolean.TRUE;        // True if people can register themselves
    @JsonProperty("description")
    private String description;                         // Describes the trip
    @Builder.Default()
    @JsonProperty("startDate")
    private LocalDateTime startDate =
            LocalDateTime.now().plusDays(90);           // Start of trip
    @Builder.Default()
    @JsonProperty("endDate")
    private LocalDateTime endDate =
            LocalDateTime.now().plusDays(100);          // End of trip
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

    public Trip() {
    }

    public List<Person.Id> getPeople() {
        if (people == null) {
            people = new ArrayList<>();
        }
        return people;
    }

    public List<TripEvent> getTripEvents() {
        if (tripEvents == null) {
            tripEvents = new ArrayList<>();
        }
        return tripEvents;
    }

    public List<RegistrationOption> getRegOptions() {
        if (regOptions == null) {
            regOptions = new ArrayList<>();
        }
        return regOptions;
    }

    /**
     * Returns {@code true} if the the given person can join this Trip. This requires the Person to not already be on
     * the trip, and for the trip to not yet be started.
     */
    public boolean canJoin(final Person.Id userId) {
        return !getPeople().contains(userId) && startDate.isAfter(LocalDateTime.now());
    }

    public String addTripEvent(final String title, final String notes, final LocalDateTime date) {
        final List<TripEvent> events = getTripEvents();
        // Very simple validation check...
        events.stream().filter(te -> matchingTE(te, title, date)).findAny().ifPresent(te -> {
            throw new IllegalStateException(
                    "Trip Event with title (" + title + ") and date (" + date + ") already exists!");
        });
        // Add it
        final String id = UUID.randomUUID().toString();
        events.add(new TripEvent(id, title, notes, date, null, null));
        events.sort(Comparator.comparing(TripEvent::getStart));
        return id;
    }

    @JsonIgnore
    public TripEvent getTripEvent(final String teId) {
        return getTripEvents().stream().filter(e -> e.getId().equals(teId)).findAny().orElse(null);
    }

    @JsonIgnore
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
        regOptions.add(new RegistrationOption(regOptions.size(), "New Option", "New Option Description", false));
    }

    private boolean matchingTE(final TripEvent te, final String title, final LocalDateTime date) {
        return title.equals(te.getTitle()) && date.equals(te.getStart());
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
            final CompletableFuture<?>[] tes = ids.stream()
                    .map(DAO.getInstance()::getTripEvent)
                    .toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(tes).thenApply(v -> Arrays.stream(tes)
                    .map(fut -> (TripEvent) fut.join())
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList()))
                    .join();
        }
    }
}