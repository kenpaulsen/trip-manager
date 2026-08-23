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
 * <em>global</em> (e.g. {@code "privilegeAdmin"}) or <em>scoped</em> to a Trip or an Organization, and scoped ones
 * are stored with the scope's id appended to the base name (e.g. {@code "tripMgr" + tripId} or
 * {@code "peopleAdmin" + orgId}). That concatenated string is the DynamoDB partition key (the {@code name}
 * attribute) and this class's {@link #id}. We deliberately expose the two logical parts as first-class attributes
 * instead of leaving callers to slice the string: {@link #getName()} returns the <em>base</em> name (without the
 * scope id) and {@link #getScopeId()} returns the scope id (or {@code null} when global). Both are derived from
 * {@link #id} so they can never drift from the stored key, and nothing new is persisted -- the JSON still
 * serializes the identity under the {@code "name"} property, so no data migration is needed.</p>
 *
 * <p><b>The scope id is an opaque UUID</b> -- nothing structural says whether it names a Trip or an Organization.
 * The privilege's base name decides the interpretation ({@code tripMgr} scopes to trips, {@code peopleAdmin} to
 * orgs; see {@code PrivilegeCommands.TRIP_SCOPED_BASES} / {@code ORG_SCOPED_BASES}), and callers always know
 * which kind of id they passed. Both id kinds are canonical UUIDs on purpose so they round-trip the same parse.</p>
 */
@Value
public class Privilege implements Serializable {
    public static final Privilege NONE = new Privilege("", "", null);

    /** A scope id suffix: a canonical UUID (trip or org id) anchored at the end of the identity. */
    private static final Pattern SCOPE_ID_SUFFIX = Pattern.compile(
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

    /** A complete scope id: the same canonical-UUID shape {@link #SCOPE_ID_SUFFIX} anchors on. */
    private static final Pattern SCOPE_ID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * Builds the identity for a base name plus an optional scope id (null/blank == global).
     *
     * <p>Deliberately lenient: reads (authorization checks, lookups) build the same concatenation whatever the
     * scope id looks like, so they stay symmetric with whatever was stored. It is the WRITE paths that must
     * refuse a non-round-trippable scope -- see {@link #requireStorableScope(String)}.
     */
    public static String idFor(final String baseName, final String scopeId) {
        return (scopeId == null || scopeId.isBlank()) ? baseName : baseName + scopeId;
    }

    /**
     * Refuses a scope that cannot survive storage.
     *
     * <p>The identity is parsed back into (name, scopeId) by anchoring on a canonical-UUID suffix, so a
     * non-UUID scope id concatenates fine and then reads back as GLOBAL -- a scoped grant silently becoming
     * site-wide. Real trip AND org ids are minted UUIDs (trips verified against production 2026-08-04; org
     * ids are canonical by construction, see {@code Organization.Id}), so the write paths call this to refuse
     * the one shape that cannot round-trip rather than storing it wrong.
     *
     * @param scopeId the requested scope (a trip or org id); null/blank (global) is always fine.
     * @throws IllegalArgumentException if {@code scopeId} is non-blank and not a canonical UUID.
     */
    public static void requireStorableScope(final String scopeId) {
        if (scopeId != null && !scopeId.isBlank() && !SCOPE_ID.matcher(scopeId).matches()) {
            throw new IllegalArgumentException("Scope id '" + scopeId + "' is not a canonical UUID; a privilege "
                    + "scoped to it would silently parse back as GLOBAL. Refusing to store it.");
        }
    }

    /** The base privilege name, without any scope id suffix. */
    @JsonIgnore
    public String getName() {
        final Matcher m = matcher();
        return (m == null) ? id : m.group(1);
    }

    /** The trip or org id this privilege is scoped to, or {@code null} if it is global. */
    @JsonIgnore
    public String getScopeId() {
        final Matcher m = matcher();
        return (m == null) ? null : m.group(2);
    }

    /**
     * Alias of {@link #getScopeId()} from before org-scoped privileges existed. Kept because EL references
     * ({@code #{aPriv.tripId}}) break silently on rename; Java callers should prefer {@code getScopeId()}.
     */
    @JsonIgnore
    public String getTripId() {
        return getScopeId();
    }

    /** True when this privilege is not tied to a specific trip or org. */
    @JsonIgnore
    public boolean isGlobal() {
        return getScopeId() == null;
    }

    private Matcher matcher() {
        if (id == null) {
            return null;
        }
        final Matcher m = SCOPE_ID_SUFFIX.matcher(id);
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
