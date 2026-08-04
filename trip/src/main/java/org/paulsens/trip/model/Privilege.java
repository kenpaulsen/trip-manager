package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Value;

/**
 * A named privilege granting its {@link #people} some capability.
 *
 * <p><b>Why the identity field is called {@code id} and maps to DynamoDB's {@code name}:</b> a privilege is either
 * <em>global</em> (e.g. {@code "peopleAdmin"}) or <em>trip-scoped</em>, and trip-scoped ones are stored with the
 * trip id appended to the base name (e.g. {@code "tripMgr" + tripId}). That concatenated string is the DynamoDB
 * partition key (the {@code name} attribute) and this class's {@link #id}. We deliberately expose the two logical
 * parts as first-class attributes instead of leaving callers to slice the string: {@link #getName()} returns the
 * <em>base</em> name (without the trip id) and {@link #getTripId()} returns the trip id (or {@code null} when
 * global). Both are derived from {@link #id} so they can never drift from the stored key, and nothing new is
 * persisted -- the JSON still serializes the identity under the {@code "name"} property, so no data migration is
 * needed. (A future storage change that gives the trip id its own column becomes a model-internal change, since
 * callers already treat the trip id as an attribute.)</p>
 */
@Value
public class Privilege implements Serializable {
    public static final Privilege NONE = new Privilege("", "", null);

    /** A trip id suffix: a canonical UUID anchored at the end of the identity. */
    private static final Pattern TRIP_ID_SUFFIX = Pattern.compile(
            "(.*)([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$");

    /** The full identity == DynamoDB {@code name} == base name + (tripId, if trip-scoped). Serialized as "name". */
    @JsonProperty("name")
    String id;
    String description;
    List<Person.Id> people;

    @JsonCreator
    public Privilege(
            @JsonProperty("name") String id,
            @JsonProperty("description") String description,
            @JsonProperty("people") List<Person.Id> people) {
        this.id = id;
        this.description = description;
        this.people = (people == null) ? List.of() : people;
    }

    /** A complete trip id: the same canonical-UUID shape {@link #TRIP_ID_SUFFIX} anchors on. */
    private static final Pattern TRIP_ID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * Builds the identity for a base name plus an optional trip id (null/blank == global).
     *
     * <p>Deliberately lenient: reads (authorization checks, lookups) build the same concatenation whatever the
     * trip id looks like, so they stay symmetric with whatever was stored. It is the WRITE paths that must
     * refuse a non-round-trippable scope -- see {@link #requireStorableTripScope(String)}.
     */
    public static String idFor(final String baseName, final String tripId) {
        return (tripId == null || tripId.isBlank()) ? baseName : baseName + tripId;
    }

    /**
     * Refuses a trip scope that cannot survive storage.
     *
     * <p>The identity is parsed back into (name, tripId) by anchoring on a canonical-UUID suffix, so a
     * non-UUID trip id concatenates fine and then reads back as GLOBAL -- a trip-scoped grant silently
     * becoming site-wide. Real trip ids are minted UUIDs (verified against production 2026-08-04), so the
     * write paths call this to refuse the one shape that cannot round-trip rather than storing it wrong.
     *
     * @param tripId the requested scope; null/blank (global) is always fine.
     * @throws IllegalArgumentException if {@code tripId} is non-blank and not a canonical UUID.
     */
    public static void requireStorableTripScope(final String tripId) {
        if (tripId != null && !tripId.isBlank() && !TRIP_ID.matcher(tripId).matches()) {
            throw new IllegalArgumentException("Trip id '" + tripId + "' is not a canonical UUID; a privilege "
                    + "scoped to it would silently parse back as GLOBAL. Refusing to store it.");
        }
    }

    /** The base privilege name, without any trip id suffix. */
    @JsonIgnore
    public String getName() {
        final Matcher m = matcher();
        return (m == null) ? id : m.group(1);
    }

    /** The trip id this privilege is scoped to, or {@code null} if it is global. */
    @JsonIgnore
    public String getTripId() {
        final Matcher m = matcher();
        return (m == null) ? null : m.group(2);
    }

    /** True when this privilege is not tied to a specific trip. */
    @JsonIgnore
    public boolean isGlobal() {
        return getTripId() == null;
    }

    private Matcher matcher() {
        if (id == null) {
            return null;
        }
        final Matcher m = TRIP_ID_SUFFIX.matcher(id);
        return m.matches() ? m : null;
    }

    public Privilege withNewPerson(final Person.Id pid) {
        if (people.contains(pid)) {
            return this;
        }
        final List<Person.Id> newList = new ArrayList<>(people);
        newList.add(pid);
        return new Privilege(id, description, newList);
    }

    public Privilege withoutPerson(final Person.Id pid) {
        if (!people.contains(pid)) {
            return this;
        }
        final List<Person.Id> newList = new ArrayList<>(people);
        newList.remove(pid);
        return new Privilege(id, description, newList);
    }
}
