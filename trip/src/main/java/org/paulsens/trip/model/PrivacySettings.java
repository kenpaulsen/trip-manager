package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

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
 *
 * <p>{@code shareItinerary} is the one choice that is not a {@link Visibility}: it is about the people on the
 * person's TRIPS, not every signed-in user, so "LOGGED_IN" would mislabel it. It applies to every trip the person
 * is on, and a trip must also turn sharing on (docs/shared-itineraries.md).
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
    /**
     * Whether the person has turned OFF sharing their itinerary with the people on their trips. Stored inverted, as
     * a primitive, so that its zero value is the default (shared): Kryo runs no constructor, so session state
     * written before this field existed comes back {@code false}, which must never read as "off". Callers read
     * {@link #isItineraryShared()}; the JSON row carries the positive {@code shareItinerary}.
     */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean itineraryHidden;

    public PrivacySettings(final Visibility email, final Visibility cell, final Visibility city,
            final Visibility street) {
        this(email, cell, city, street, null);
    }

    @JsonCreator
    public PrivacySettings(
            @JsonProperty("email") final Visibility email,
            @JsonProperty("cell") final Visibility cell,
            @JsonProperty("city") final Visibility city,
            @JsonProperty("street") final Visibility street,
            @JsonProperty("shareItinerary") final Boolean shareItinerary) {
        this.email = (email == null) ? Visibility.LOGGED_IN : email;
        this.cell = (cell == null) ? Visibility.LOGGED_IN : cell;
        this.city = (city == null) ? Visibility.LOGGED_IN : city;
        this.street = (street == null) ? Visibility.PRIVATE : street;
        this.itineraryHidden = shareItinerary != null && !shareItinerary;
    }

    public PrivacySettings() {
        this(null, null, null, null, null);
    }

    /** The stored choice for the JSON row; {@link #isItineraryShared()} is what callers read. */
    @JsonProperty("shareItinerary")
    public boolean getShareItinerary() {
        return isItineraryShared();
    }

    @JsonIgnore
    public boolean isItineraryShared() {
        return !itineraryHidden;
    }

    /** Plain boolean so the account page's switch binds directly. */
    @JsonIgnore
    public void setItineraryShared(final boolean shared) {
        this.itineraryHidden = !shared;
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
