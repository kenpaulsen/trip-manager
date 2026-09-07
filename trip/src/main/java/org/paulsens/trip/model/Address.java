package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import lombok.Data;

@Data
public final class Address implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    private String street;
    private String city;
    private String state;
    private String zip;
    /**
     * Second address line and country -- added 2026-09 for lodging (a hotel needs a country; international
     * pilgrimages). Deliberately OUTSIDE the four-arg creator: Jackson fills them through the setters, every
     * existing {@code new Address(street, city, state, zip)} call site stays untouched, and a stream written
     * before they existed reads back with nulls (the pinned UID holds).
     */
    private String street2;
    private String country;

    public Address(
            @JsonProperty("street") String street,
            @JsonProperty("city") String city,
            @JsonProperty("state") String state,
            @JsonProperty("zip") String zip) {
        this.street = street;
        this.city = city;
        this.state = state;
        this.zip = zip;
    }

    public Address() {
        this.street = null;
        this.city = null;
        this.state = null;
        this.zip = null;
    }
}
