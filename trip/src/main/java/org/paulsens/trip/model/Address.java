package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * {@code "Podbrdo 25, Medjugorje 88266, Bosnia and Herzegovina"}: street and street2, then city, state and
     * zip, then country, each group only when it has something in it (the shape the lodging admin page
     * prints, and the default notes of a LODGING trip event).
     */
    @JsonIgnore
    public String oneLine() {
        final List<String> groups = new ArrayList<>();
        group(groups, street, street2);
        group(groups, city, state, zip);
        group(groups, country);
        return String.join(", ", groups);
    }

    private static void group(final List<String> groups, final String... parts) {
        final List<String> present = new ArrayList<>();
        for (final String part : parts) {
            if (part != null && !part.isBlank()) {
                present.add(part.trim());
            }
        }
        if (!present.isEmpty()) {
            groups.add(String.join(" ", present));
        }
    }
}
