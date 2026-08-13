package org.paulsens.trip.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A person's privacy choices on the wire: each knob is a {@code Visibility} name ({@code PRIVATE} or
 * {@code LOGGED_IN}). Only the subject, their manager, and site admins ever receive this object.
 *
 * <p>The {@code *Visible()} helpers exist for {@link PersonDto#redactedFor} -- they answer the EFFECTIVE
 * visibility, so {@link #streetVisible()} is false whenever city is private, whatever the street knob says
 * (mirroring {@code PrivacySettings.isStreetVisible()}). Unknown strings read as not-visible, which fails closed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PrivacyDto(String email, String cell, String city, String street) {

    static final String LOGGED_IN = "LOGGED_IN";

    /** What a record with no stored choices means -- must match the {@code PrivacySettings} defaults. */
    static final PrivacyDto DEFAULTS = new PrivacyDto(LOGGED_IN, LOGGED_IN, LOGGED_IN, "PRIVATE");

    public boolean emailVisible() {
        return LOGGED_IN.equals(email);
    }

    public boolean cellVisible() {
        return LOGGED_IN.equals(cell);
    }

    public boolean cityVisible() {
        return LOGGED_IN.equals(city);
    }

    public boolean streetVisible() {
        return LOGGED_IN.equals(street) && cityVisible();
    }
}
