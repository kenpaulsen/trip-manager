package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import lombok.Value;

@Value
public class Registration implements Serializable {
    String tripId;              // The trip id (partition key)
    Person.Id userId;           // The user id (sort key)
    LocalDateTime created;      // When they first registered
    String status;              // Registration Status
    Map<String, String> notes;  // Extra information

    @JsonCreator
    public Registration(
            @JsonProperty("tripId") final String tripId,
            @JsonProperty("userId") final Person.Id userId,
            @JsonProperty("created") final LocalDateTime created,
            @JsonProperty("status") final String status,
            @JsonProperty("notes") final Map<String, String> notes) {
        this.tripId = tripId;
        this.userId = userId;
        this.created = (created == null) ? LocalDateTime.now() : created;
        this.status = (status == null) ? "Pending" : status;
        this.notes = (notes == null) ? new HashMap<>() : notes;
    }

    public Registration(final String tripId, final Person.Id userId) {
        this(tripId, userId, null, null, null);
    }
}