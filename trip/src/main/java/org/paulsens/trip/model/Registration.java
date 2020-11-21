package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.Data;

@Data
public final class Registration implements Serializable {
    private String id;                  // Registration ID
    private String tripId;              // The trip id
    private LocalDateTime created;      // When they first registered
    private Map<String, String> notes;  // Extra information

    public Registration(
            @JsonProperty("id") String id,
            @JsonProperty("tripId") String tripId,
            @JsonProperty("created") LocalDateTime created,
            @JsonProperty("notes") Map<String, String> notes) {
        this.id = (id == null) ? UUID.randomUUID().toString() : id;
        this.tripId = tripId;
        this.created = (created == null) ? LocalDateTime.now() : created;
        this.notes = notes;
    }

    public Registration(final String tripId) {
        this(null, tripId, null, new HashMap<>());
    }
}