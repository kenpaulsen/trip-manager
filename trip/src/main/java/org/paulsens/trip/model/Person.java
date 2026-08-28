package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Data;
import lombok.Setter;
import lombok.Value;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.cache.Cached;

@Data
public final class Person implements Serializable, Comparable<Person> {
    public static final Comparator<Person> PEOPLE_SORTER = (a, b) ->
            getPersonSortStr(a).compareToIgnoreCase(getPersonSortStr(b));
    private Person.Id id;
    private String nickname;
    private String first;
    private String middle;
    private String last;
    private Sex sex;
    private LocalDate birthdate;
    private String cell;
    private String email;
    private String tsa;
    private Address address;
    private Passport passport;
    private String notes;
    private List<Person.Id> managedUsers;
    private String emergencyContactName;
    private String emergencyContactPhone;
    @Setter(AccessLevel.NONE)
    private LocalDateTime deleted;
    /**
     * Back-pointer to the {@link Family} this person belongs to; null for the (common) person in no family.
     * Deliberately NOT a constructor parameter: Jackson populates it through the setter (like the emergency
     * fields' aliases), which keeps the 17-arg creator's many existing callers untouched. The family row is
     * the source of truth — this field only answers "which family?", never "who is in it?".
     */
    private Family.Id familyId;
    /**
     * Back-pointers to the {@link Organization}s this person belongs to (usually exactly one; multi-org is
     * supported). The {@code org_members} table is the source of truth -- this derived list exists so
     * authorization ("is this viewer in that org?") never needs a table query. Synced surgically by
     * {@code OrgCommands}. Like {@link #familyId}, deliberately NOT a constructor parameter, and never null:
     * rows written before the feature deserialize without the key, leaving this initializer in place.
     */
    private List<Organization.Id> orgIds = new ArrayList<>();
    /**
     * Which of this person's profile-picture slots (1-4) is THE profile picture; null when never chosen, in
     * which case the lowest occupied slot wins (the deterministic default). The slot NUMBER is stored rather
     * than the object key so replacing a photo — which mints a new versioned key — never has to write this
     * row. Like {@link #familyId}, deliberately NOT a constructor parameter: Jackson populates it through the
     * setter, keeping the 17-arg creator's callers untouched.
     */
    private Integer profilePhotoSlot;
    /**
     * The person's privacy choices for the four negotiable profile fields. Never null: rows written before the
     * feature deserialize without the key, leaving this initializer in place, and the setter refuses null -- EL on
     * the profile page binds straight to {@code person.privacy.email}, so a lazily-created default object would
     * silently swallow writes. Like {@link #familyId}, deliberately NOT a constructor parameter.
     */
    private PrivacySettings privacy = new PrivacySettings();

    // @Builder sits on this constructor, not the class: the builder covers exactly these parameters, so fields
    // added later (familyId) don't force an 18-arg constructor on every existing caller. Setter-populated
    // fields are simply absent from the builder.
    @Builder
    @JsonCreator
    public Person(
            @JsonProperty("id") final Id id,
            @JsonProperty("nickname") final String nickname,
            @JsonProperty("first") final String first,
            @JsonProperty("middle") final String middle,
            @JsonProperty("last") final String last,
            @JsonProperty("sex") final Sex sex,
            @JsonProperty("birthdate") final LocalDate birthdate,
            @JsonProperty("cell") final String cell,
            @JsonProperty("email") final String email,
            @JsonProperty("tsa") final String tsa,
            @JsonProperty("address") final Address address,
            @JsonProperty("passport") final Passport passport,
            @JsonProperty("notes") final String notes,
            @JsonProperty("managedUsers") final List<Person.Id> managedUsers,
            @JsonProperty("emergencyName") final String emergencyName,
            @JsonProperty("emergencyPhone") final String emergencyPhone,
            @JsonProperty("deleted") final LocalDateTime deleted) {
        this.id = (id == null) ? Id.newInstance() : id;
        this.nickname = trim(nickname);
        this.first = trim(first);
        this.middle = trim(middle);
        this.last = trim(last);
        this.sex = sex;
        this.birthdate = birthdate;
        this.cell = trim(cell);
        this.email = email == null ? null : trim(email).toLowerCase(Locale.ROOT);
        this.tsa = trim(tsa);
        this.address = (address == null) ? new Address() : address;
        this.passport = (passport == null) ? new Passport() : passport;
        this.notes = trim(notes);
        this.managedUsers = (managedUsers == null) ? new ArrayList<>() : managedUsers;
        this.emergencyContactName = trim(emergencyName);
        this.emergencyContactPhone = trim(emergencyPhone);
        this.deleted = deleted;
    }

    public Person() {
        this(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public LocalDateTime delete() {
        this.deleted = LocalDateTime.now();
        return deleted;
    }

    @JsonIgnore
    public String getPreferredName() {
        return nickname == null || nickname.isBlank() ? first : nickname;
    }

    /**
     * Get the trips that contain this user.
     * @return List of trips the user has joined.
     */
    @JsonIgnore
    public List<Trip> getTrips() {
        try {
            return DAO.getInstance().getTripsForUser(getId(), Cached.YES);
        } catch (final RuntimeException ex) {
            return new ArrayList<>();
        }
    }

    @JsonIgnore
    public List<String> getTripIds() {
        return getTrips().stream()
                .map(Trip::getId)
                .collect(Collectors.toList());
    }

    public void setPrivacy(final PrivacySettings privacy) {
        this.privacy = (privacy == null) ? new PrivacySettings() : privacy;
    }

    // Same never-null contract as privacy: EL and sync code iterate this list without guarding.
    public void setOrgIds(final List<Organization.Id> orgIds) {
        this.orgIds = (orgIds == null) ? new ArrayList<>() : new ArrayList<>(orgIds);
    }

    public void setEmail(final String email) {
        this.email = (email == null) ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    public int compareTo(final Person other) {
        return PEOPLE_SORTER.compare(this, other);
    }

    private static String getPersonSortStr(final Person person) {
        return "" + person.getLast() + "," + person.getFirst();
    }

    private static String trim(final String str) {
        return str == null ? null : str.trim();
    }

    public enum Sex {
        Male, Female
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
