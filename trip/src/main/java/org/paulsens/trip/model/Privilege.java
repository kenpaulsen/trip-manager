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

    /** Builds the identity for a base name plus an optional trip id (null/blank == global). */
    public static String idFor(final String baseName, final String tripId) {
        return (tripId == null || tripId.isBlank()) ? baseName : baseName + tripId;
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
