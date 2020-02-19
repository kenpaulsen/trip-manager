package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Data;

@Data
public final class TripEvent implements Serializable {
    String title;                   // Event title
    LocalDateTime start;            // Start of the event
    Map<String, String> people;     // Mapping of userId to Status

    public TripEvent(
            @JsonProperty("title") String title,
            @JsonProperty("start") LocalDateTime start,
            @JsonProperty("people") Map<String, String> peopleStatus) {
        this.title = title;
        this.start = start;
        this.people = people;
    }

    public TripEvent() {
        this.title = null;
        this.start = LocalDateTime.now().plusDays(30);
        this.people = new ConcurrentHashMap<>();
    }
}
