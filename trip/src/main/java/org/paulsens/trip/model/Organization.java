package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;
import lombok.Value;

/**
 * An organization: the tenancy boundary of the platform. Trips belong to an organization, people are members of
 * at least one, and org-owned configuration (payment processors first, theming and content later) is visible
 * only inside it.
 *
 * <p>Membership itself is NOT stored on this row: a roster can reach the whole user base, which would push a
 * single item toward the DynamoDB size cap and serialize every join on one optimistic-version row. Member rows
 * live in the {@code org_members} table (source of truth), with {@link Person#getOrgIds()} as the derived
 * reverse edge -- the same row-is-truth/back-pointer split {@link Family} uses. Only {@code adminIds} lives
 * here: admins are few, and every authorization check needs them in hand with the org.
 *
 * <p>Ids are canonical UUIDs on purpose: {@link Privilege} scope suffixes must round-trip through a UUID-anchored
 * parse, so a non-UUID org id would foreclose org-scoped privileges later.
 *
 * <p>{@code version} implements optimistic concurrency exactly like {@link Family}: saves are conditioned on the
 * stored version matching the loaded one, and a lost race surfaces to the caller rather than retrying silently.
 */
@Data
public final class Organization implements Serializable {
    private Id id;
    private String name;
    private String abbreviation;
    private String contactEmail;
    private List<Person.Id> adminIds;
    private Person.Id createdBy;
    private LocalDateTime created;
    private long version;
    /**
     * Org-level payment defaults: the middle rung of the trip &rarr; org &rarr; site settings ladder, the
     * same {@link TripPaymentConfig} type the trip-level override uses. Like {@code Person.familyId},
     * deliberately NOT a constructor parameter (setter-populated) so the 8-arg creator's callers stay
     * untouched; never null via the lazy getter.
     */
    private TripPaymentConfig paymentDefaults;
    /**
     * The privilege base names this org may grant (site-admin controlled allow-list, bounding both the
     * trip-scoped roles on the trip editor and the org-scoped grants on the org People page). {@code null}
     * means "never restricted" == everything allowed, so existing rows need no migration and restriction is
     * always an explicit act; an EMPTY list means "nothing grantable". That distinction is why -- unlike
     * {@link #paymentDefaults} -- the getter must NOT lazy-init. Setter-populated, deliberately outside the
     * 8-arg creator for the same reason {@code paymentDefaults} is.
     */
    private List<String> grantablePrivileges;

    @Builder
    @JsonCreator
    public Organization(
            @JsonProperty("id") final Id id,
            @JsonProperty("name") final String name,
            @JsonProperty("abbreviation") final String abbreviation,
            @JsonProperty("contactEmail") final String contactEmail,
            @JsonProperty("adminIds") final List<Person.Id> adminIds,
            @JsonProperty("createdBy") final Person.Id createdBy,
            @JsonProperty("created") final LocalDateTime created,
            @JsonProperty("version") final long version) {
        this.id = (id == null) ? Id.newInstance() : id;
        this.name = trim(name);
        this.abbreviation = trim(abbreviation);
        this.contactEmail = trim(contactEmail);
        this.adminIds = (adminIds == null) ? new ArrayList<>() : new ArrayList<>(adminIds);
        this.createdBy = createdBy;
        this.created = created;
        this.version = version;
    }

    public Organization() {
        this(null, null, null, null, null, null, null, 0L);
    }

    public TripPaymentConfig getPaymentDefaults() {
        if (paymentDefaults == null) {
            paymentDefaults = new TripPaymentConfig();
        }
        return paymentDefaults;
    }

    @JsonIgnore
    public boolean isAdmin(final Person.Id personId) {
        return personId != null && adminIds.contains(personId);
    }

    /** True when this org may grant the given privilege base name -- see {@link #grantablePrivileges}. */
    @JsonIgnore
    public boolean mayGrant(final String baseName) {
        return grantablePrivileges == null || grantablePrivileges.contains(baseName);
    }

    /** The short label when one was set, otherwise the full name -- what compact UI (chips, notes) shows. */
    @JsonIgnore
    public String getShortName() {
        return (abbreviation == null || abbreviation.isBlank()) ? name : abbreviation;
    }

    private static String trim(final String str) {
        return (str == null) ? null : str.trim();
    }

    @Value
    public static class Id implements Serializable, Comparable<Id> {
        @JsonValue
        String value;

        public static Id from(final String id) {
            return new Id(id);
        }

        public static Id newInstance() {
            return new Id(UUID.randomUUID().toString());
        }

        @Override
        public int compareTo(final Id o) {
            return value.compareTo(o.getValue());
        }
    }
}
