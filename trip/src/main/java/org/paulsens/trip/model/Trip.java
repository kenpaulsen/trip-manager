package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Data;

@Data
public final class Trip implements Serializable {
    private String id;                  // Trip ID
    private String title;               // Title of trip
    private String description;         // Describes the trip
    private LocalDate startDate;        // Start of trip
    private LocalDate endDate;          // End of trip
    private List<String> people;        // UserIds
    private List<TripEvent> tripEvents; // The stuff needed to book, airfare, hotel, etc. w/ conf #'s or yes/no/NA/?

    public Trip(
            @JsonProperty("id") String id,
            @JsonProperty("title") String title,
            @JsonProperty("description") String description,
            @JsonProperty("startDate") LocalDate startDate,
            @JsonProperty("endDate") LocalDate endDate,
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
        this.startDate = LocalDate.now(ZoneId.of("PST")).plusDays(60);
        this.endDate = LocalDate.now(ZoneId.of("PST")).plusDays(70);
        this.people = new ArrayList<>();
        this.tripEvents = new ArrayList<>();
    }
}
