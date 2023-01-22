package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.util.StdConverter;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import lombok.Data;
import org.paulsens.trip.dynamo.DAO;

@Data
public final class Trip implements Serializable {
    private String id;                              // Trip ID
    private String title;                           // Title of trip
    private Boolean openToPublic;                   // True if people can register themselves
    private String description;                     // Describes the trip
    private LocalDateTime startDate;                // Start of trip
    private LocalDateTime endDate;                  // End of trip
    private List<Person.Id> people;                 // UserIds
    @JsonSerialize(converter = TripEventsSerializer.class)
    @JsonDeserialize(converter = TripEventsDeserializer.class)
    private List<TripEvent> tripEvents;             // The stuff needed to book, airfare, hotel, etc.
    private List<RegistrationOption> regOptions;    // Registration page questions

    public Trip(
            @JsonProperty("id") String id,
            @JsonProperty("title") String title,
            @JsonProperty("openToPublic") Boolean openToPublic,
            @JsonProperty("description") String description,
            @JsonProperty("startDate") LocalDateTime startDate,
            @JsonProperty("endDate") LocalDateTime endDate,
            @JsonProperty("people") List<Person.Id> people,
            @JsonProperty("tripEvents") List<TripEvent> tripEvents,
            @JsonProperty("regOptions") List<RegistrationOption> regOptions) {
        this.id = id;
        this.title = title;
        this.openToPublic = openToPublic == null || openToPublic;
        this.description = description;
        this.startDate = startDate;
        this.endDate = endDate;
        this.people = (people == null) ? new ArrayList<>() : new ArrayList<>(people);
        this.tripEvents = (tripEvents == null) ? new ArrayList<>() : new ArrayList<>(tripEvents);
        this.regOptions = (regOptions == null) ? new ArrayList<>() : new ArrayList<>(regOptions);
    }

    public Trip() {
        this(UUID.randomUUID().toString(), null, null, null, LocalDateTime.now().plusDays(60),
                LocalDateTime.now().plusDays(70), null, null, null);
    }

    /**
     * Returns {@code true} if the the given person can join this Trip. This requires the Person to not already be on
     * the trip, and for the trip to not yet be started.
     */
    public boolean canJoin(final Person.Id userId) {
        return !people.contains(userId) && startDate.isAfter(LocalDateTime.now());
    }

    public String addTripEvent(final String title, final String notes, final LocalDateTime date) {
        // Very simple validation check...
        tripEvents.stream().filter(te -> matchingTE(te, title, date)).findAny().ifPresent(te -> {
            throw new IllegalStateException(
                    "Trip Event with title (" + title + ") and date (" + date + ") already exists!");
        });
        // Add it
        final String id = UUID.randomUUID().toString();
        tripEvents.add(new TripEvent(id, title, notes, date, null, null));
        tripEvents.sort(Comparator.comparing(TripEvent::getStart));
        return id;
    }

    @JsonIgnore
    public TripEvent getTripEvent(final String teId) {
        return tripEvents.stream().filter(e -> e.getId().equals(teId)).findAny().orElse(null);
    }

    @JsonIgnore
    public List<TripEvent> getTripEventsForUser(final Person.Id userId) {
        return tripEvents.stream().filter(te -> te.getParticipants().contains(userId)).collect(Collectors.toList());
    }

    @JsonIgnore
    public void deleteTripEvent(final TripEvent te) {
        tripEvents.remove(te);
    }

    public void editTripEvent(final TripEvent newTE) {
        // Ensure we have the TripEvent to edit
        final TripEvent oldTE = tripEvents.stream().filter(e -> e.getId().equals(newTE.getId())).findAny()
                .orElseThrow(() -> new IllegalArgumentException("TripEvent id (" + newTE.getId() + ") not found!"));
        tripEvents.remove(oldTE);
        tripEvents.add(newTE);
        tripEvents.sort(Comparator.comparing(TripEvent::getStart));
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