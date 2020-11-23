package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import lombok.Value;
import lombok.With;

@Value
public class Registration implements Serializable {
    String tripId;                  // The trip id (partition key)
    Person.Id userId;               // The user id (sort key)
    LocalDateTime created;          // When they first registered
    @With
    String status;                  // Registration Status
    Map<String, String> options;    // Extra information

    @JsonCreator
    public Registration(
            @JsonProperty("tripId") final String tripId,
            @JsonProperty("userId") final Person.Id userId,
            @JsonProperty("created") final LocalDateTime created,
            @JsonProperty("status") final String status,
            @JsonProperty("options") final Map<String, String> options) {
        this.tripId = tripId;
        this.userId = userId;
        this.created = (created == null) ? LocalDateTime.now() : created;
        this.status = (status == null) ? "Not Registered" : status;
        this.options = (options == null) ? new HashMap<>() : options;
    }

    public Registration(final String tripId, final Person.Id userId) {
        this(tripId, userId, null, null, null);
    }
}