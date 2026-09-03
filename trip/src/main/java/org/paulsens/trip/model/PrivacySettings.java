package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import lombok.Data;

/**
 * A person's choices about which of their profile fields other signed-in users may see.
 *
 * <p>Stored directly on {@link Person} (no separate table). Only four fields are negotiable; everything else is
 * fixed by policy: birthdate, passport, TSA, emergency contacts and notes are always private, while name and sex
 * are always visible to signed-in users. "Visible" here always means signed-in users -- nothing on a profile is
 * ever public to anonymous visitors through these settings.
 *
 * <p>{@code city} governs state as well; {@code street} governs zip. A street address with the city hidden would
 * still locate the person, so {@link #isStreetVisible()} answers false whenever city is private regardless of the
 * stored street choice -- the rule is enforced here, not just in the UI.
 */
@Data
public final class PrivacySettings implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    /** Who may see a field: nobody but self/family/admins, or any signed-in user. */
    public enum Visibility {
        PRIVATE, LOGGED_IN
    }

    private Visibility email;
    private Visibility cell;
    private Visibility city;
    private Visibility street;

    @JsonCreator
    public PrivacySettings(
            @JsonProperty("email") final Visibility email,
            @JsonProperty("cell") final Visibility cell,
            @JsonProperty("city") final Visibility city,
            @JsonProperty("street") final Visibility street) {
        this.email = (email == null) ? Visibility.LOGGED_IN : email;
        this.cell = (cell == null) ? Visibility.LOGGED_IN : cell;
        this.city = (city == null) ? Visibility.LOGGED_IN : city;
        this.street = (street == null) ? Visibility.PRIVATE : street;
    }

    public PrivacySettings() {
        this(null, null, null, null);
    }

    @JsonIgnore
    public boolean isEmailVisible() {
        return email == Visibility.LOGGED_IN;
    }

    @JsonIgnore
    public boolean isCellVisible() {
        return cell == Visibility.LOGGED_IN;
    }

    @JsonIgnore
    public boolean isCityVisible() {
        return city == Visibility.LOGGED_IN;
    }

    /** Street (and zip) visibility -- false whenever city is private, whatever the stored street choice says. */
    @JsonIgnore
    public boolean isStreetVisible() {
        return street == Visibility.LOGGED_IN && isCityVisible();
    }
}
