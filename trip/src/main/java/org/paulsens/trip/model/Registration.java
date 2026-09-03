package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Value;
import lombok.With;

@Value
public class Registration implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    /**
     * Reserved {@link #options} keys, underscore-prefixed so they can never collide with the numeric
     * {@code RegistrationOption} ids (the convention the mir2026 branch established). Additive JSON: rows
     * written before family accounts simply lack them.
     */
    public static final String OPT_REGISTERED_BY = "_registeredBy";
    /** One shared UUID per family submit, so the admin page can group a party visually. */
    public static final String OPT_PARTY = "_party";

    String tripId;                  // The trip id (partition key)
    Person.Id userId;               // The user id (sort key)
    LocalDateTime created;          // When they first registered
    @With
    Status status;                  // Registration Status
    Map<String, String> options;    // Extra information

    @JsonCreator
    public Registration(
            @JsonProperty("tripId") final String tripId,
            @JsonProperty("userId") final Person.Id userId,
            @JsonProperty("created") final LocalDateTime created,
            @JsonProperty("status") final Status status,
            @JsonProperty("options") final Map<String, String> options) {
        this.tripId = tripId;
        this.userId = userId;
        this.created = (created == null) ? LocalDateTime.now() : created;
        this.status = (status == null) ? Status.NOT_REGISTERED : status;
        this.options = (options == null) ? new HashMap<>() : options;
    }

    public Registration(final String tripId, final Person.Id userId) {
        this(tripId, userId, null, null, null);
    }

    public Registration withStatusString(final String description) {
        return withStatus(Status.fromDescription(description));
    }

    /** Who submitted this registration (a family manager registering their member), or null pre-family rows. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getRegisteredBy() {
        return options.get(OPT_REGISTERED_BY);
    }

    /** The family-submit party id, or null when registered individually. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getParty() {
        return options.get(OPT_PARTY);
    }

    public enum Status {
        NOT_REGISTERED("Not Registered"),
        CONFIRMED("Confirmed"),
        PENDING("Pending");

        @Getter @JsonValue
        final String description;

        Status(final String description) {
            this.description = description;
        }

        @JsonCreator
        public static Status fromDescription(final String description) {
            return switch (description) {
                case "Not Registered" -> NOT_REGISTERED;
                case "Confirmed" -> CONFIRMED;
                case "Pending" -> PENDING;
                default -> null;
            };
        }

        @Override
        public String toString() {
            return description;
        }
    }
}
