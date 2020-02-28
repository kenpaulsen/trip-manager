package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
public final class TripEvent implements Serializable {
    private final String id;                    // Trip ID
    private String title;                       // Event title
    private String notes;                       // Event notes
    private LocalDateTime start;                // Start of the event
    private final Map<String, String> people;   // Mapping of userId to Status

    private static final String NOTES_DEFAULT = "Enter Event Notes";

    public TripEvent(
            @JsonProperty("id") String id,
            @JsonProperty("title") String title,
            @JsonProperty("notes") String notes,
            @JsonProperty("start") LocalDateTime start,
            @JsonProperty("people") Map<String, String> peopleStatus) {
        if ((title == null) || (id == null)) {
            throw new IllegalArgumentException("Title and id are both required!");
        }
        this.id = id;
        this.title = title;
        this.notes = (notes == null) ? NOTES_DEFAULT : notes;
        this.start = (start == null) ? LocalDateTime.now().plusDays(30) : start;
        this.people = (peopleStatus == null) ? new ConcurrentHashMap<>() : peopleStatus;
    }

    public TripEvent() {
        this.id = UUID.randomUUID().toString();
        this.title = null;
        this.notes = NOTES_DEFAULT;
        this.start = LocalDateTime.now().plusDays(30);
        this.people = new ConcurrentHashMap<>();
    }
}
