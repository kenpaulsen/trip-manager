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
        this.people = people;
        this.tripEvents = tripEvents;
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
}
