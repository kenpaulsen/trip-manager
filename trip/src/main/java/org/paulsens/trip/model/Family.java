package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;
import lombok.Value;

/**
 * A family: one or more people who share a login. The family row is the <b>source of truth</b> for household
 * membership; each manager's {@link Person#getManagedUsers()} list is derived from it (synced surgically by
 * {@code FamilyCommands}) so that every existing consumer of {@code managedUsers} keeps working unchanged.
 *
 * <p>{@code managerIds} is always a subset of {@code memberIds}. There is deliberately no "owner" field: every
 * rule in the feature is expressed in terms of managers and email-bearing members, and persisting an owner would
 * only add an ownership-transfer edge case.
 *
 * <p>{@code version} implements optimistic concurrency: {@code FamilyDAO.saveFamily} conditions the write on the
 * stored version matching the version this object was loaded with. Two managers editing at once (or an admin
 * unlink racing a member-create) serialize at this row; the loser re-reads and retries.
 */
@Data
public final class Family implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    private Id id;
    private List<Person.Id> memberIds;
    private List<Person.Id> managerIds;
    private Person.Id createdBy;
    private LocalDateTime created;
    private long version;

    @Builder
    @JsonCreator
    public Family(
            @JsonProperty("id") final Id id,
            @JsonProperty("memberIds") final List<Person.Id> memberIds,
            @JsonProperty("managerIds") final List<Person.Id> managerIds,
            @JsonProperty("createdBy") final Person.Id createdBy,
            @JsonProperty("created") final LocalDateTime created,
            @JsonProperty("version") final long version) {
        this.id = (id == null) ? Id.newInstance() : id;
        this.memberIds = (memberIds == null) ? new ArrayList<>() : new ArrayList<>(memberIds);
        this.managerIds = (managerIds == null) ? new ArrayList<>() : new ArrayList<>(managerIds);
        this.createdBy = createdBy;
        this.created = created;
        this.version = version;
    }

    public Family() {
        this(null, null, null, null, null, 0L);
    }

    @JsonIgnore
    public boolean isMember(final Person.Id personId) {
        return personId != null && memberIds.contains(personId);
    }

    @JsonIgnore
    public boolean isManager(final Person.Id personId) {
        return personId != null && managerIds.contains(personId);
    }

    /** Member count, exposed as a property so EL pages can gate on family size without a method call. */
    @JsonIgnore
    public int getSize() {
        return memberIds.size();
    }

    @Value
    public static class Id implements Serializable, Comparable<Id> {
        @Serial
        private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

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
