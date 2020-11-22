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
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import lombok.Data;
import org.paulsens.trip.dynamo.DynamoUtils;

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
    private List<RegistrationQuestion> regOptions;    // Registration page questions

    public Trip(
            @JsonProperty("id") String id,
            @JsonProperty("title") String title,
            @JsonProperty("openToPublic") Boolean openToPublic,
            @JsonProperty("description") String description,
            @JsonProperty("startDate") LocalDateTime startDate,
            @JsonProperty("endDate") LocalDateTime endDate,
            @JsonProperty("people") List<Person.Id> people,
            @JsonProperty("tripEvents") List<TripEvent> tripEvents,
            @JsonProperty("regOptions") List<RegistrationQuestion> regOptions) {
        this.id = id;
        this.title = title;
        this.openToPublic = openToPublic == null || openToPublic;
        this.description = description;
        this.startDate = startDate;
        this.endDate = endDate;
        this.people = (people == null) ? new ArrayList<>() : people;
        this.tripEvents = (tripEvents == null) ? new ArrayList<>() : new ArrayList<>(tripEvents);
        this.regOptions = (regOptions == null) ? new ArrayList<>() : regOptions;
    }

    public Trip() {
        this.id = UUID.randomUUID().toString();
        this.title = null;
        this.description = null;
        this.startDate = LocalDateTime.now().plusDays(60);
        this.endDate = LocalDateTime.now().plusDays(70);
        this.people = new ArrayList<>();
        this.tripEvents = new ArrayList<>();
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
        return id;
    }

    @JsonIgnore
    public TripEvent getTripEvent(final String teId) {
        return tripEvents.stream().filter(e -> e.getId().equals(teId)).findAny().orElse(null);
    }

    @JsonIgnore
    public List<TripEvent> getTripEventsForUser(final String userId) {
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
            final DynamoUtils dynamo = DynamoUtils.getInstance();
            final CompletableFuture<?>[] tes = ids.stream().map(dynamo::getTripEvent).toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(tes).thenApply(v -> Arrays.stream(tes)
                    .map(fut -> (TripEvent) fut.join())
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList()))
                    .join();
        }
    }
}