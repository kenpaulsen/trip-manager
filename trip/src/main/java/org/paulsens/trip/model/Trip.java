package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Data;

@Data
public final class Trip implements Serializable {
    private String id;                  // Trip ID
    private String title;               // Title of trip
    private String description;         // Describes the trip
    private LocalDateTime startDate;    // Start of trip
    private LocalDateTime endDate;      // End of trip
    private List<String> people;        // UserIds
    private List<TripEvent> tripEvents; // The stuff needed to book, airfare, hotel, etc. w/ conf #'s or yes/no/NA/?

    public Trip(
            @JsonProperty("id") String id,
            @JsonProperty("title") String title,
            @JsonProperty("description") String description,
            @JsonProperty("startDate") LocalDateTime startDate,
            @JsonProperty("endDate") LocalDateTime endDate,
            @JsonProperty("people") List<String> people,
            @JsonProperty("tripEvents") List<TripEvent> tripEvents) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.startDate = startDate;
        this.endDate = endDate;
        this.people = (people == null) ? new ArrayList<>() : people;
        this.tripEvents = (tripEvents == null) ? new ArrayList<>() : tripEvents;
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

    public void addTripEvent(final String title, final String notes, final LocalDateTime date) {
        // Very simple validation check...
        tripEvents.stream().filter(te -> matchingTE(te, title, date)).findAny().ifPresent(te -> {
            throw new IllegalStateException(
                    "Trip Event with title (" + title + ") and date (" + date + ") already exists!");
        });
        // Add it
        tripEvents.add(new TripEvent(UUID.randomUUID().toString(), title, notes, date, null));
    }

    public void deleteTripEvent(final TripEvent te) {
        tripEvents.remove(te);
    }

// FIXME: Move this somewhere else
    public void editTripEvent(final String id, final String title, final String notes, final LocalDateTime date) {
        // Ensure we have the TripEvent to edit
        final TripEvent te = tripEvents.stream().filter(e -> e.getId().equals(id)).findAny()
                .orElseThrow(() -> new IllegalArgumentException("TripEvent id (" + id + ") not found!"));
        te.setTitle(title);
        te.setNotes(notes);
        te.setStart(date);
    }

    private boolean matchingTE(final TripEvent te, final String title, final LocalDateTime date) {
        return title.equals(te.getTitle()) && date.equals(te.getStart());
    }
}
