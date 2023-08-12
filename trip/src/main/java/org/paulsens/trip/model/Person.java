package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Data;
import lombok.Value;
import org.paulsens.trip.dynamo.DAO;

@Data
@Builder
public final class Person implements Serializable, Comparable<Person> {
    public static final Comparator<Person> peopleSorter = (a, b) ->
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

    @JsonCreator
    public Person(
            @JsonProperty("id") Id id,
            @JsonProperty("nickname") String nickname,
            @JsonProperty("first") String first,
            @JsonProperty("middle") String middle,
            @JsonProperty("last") String last,
            @JsonProperty("sex") Sex sex,
            @JsonProperty("birthdate") LocalDate birthdate,
            @JsonProperty("cell") String cell,
            @JsonProperty("email") String email,
            @JsonProperty("tsa") String tsa,
            @JsonProperty("address") Address address,
            @JsonProperty("passport") Passport passport,
            @JsonProperty("notes") String notes,
            @JsonProperty("managedUsers") List<Person.Id> managedUsers,
            @JsonProperty("emergencyName") String emergencyName,
            @JsonProperty("emergencyPhone") String emergencyPhone) {
        this.id = (id == null) ? Id.newInstance() : id;
        this.nickname = trim(nickname);
        this.first = trim(first);
        this.middle = trim(middle);
        this.last = trim(last);
        this.sex = sex;
        this.birthdate = birthdate;
        this.cell = trim(cell);
        this.email = email == null ? null : trim(email).toLowerCase(Locale.getDefault());
        this.tsa = trim(tsa);
        this.address = (address == null) ? new Address() : address;
        this.passport = (passport == null) ? new Passport() : passport;
        this.notes = trim(notes);
        this.managedUsers = (managedUsers == null) ? new ArrayList<>() : managedUsers;
        this.emergencyContactName = trim(emergencyName);
        this.emergencyContactPhone = trim(emergencyPhone);
    }

    public Person() {
        this(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
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
        return DAO.getInstance().getTrips()
                .thenApply(trips -> trips.stream().filter(
                        trip -> trip.getPeople().contains(getId())).collect(Collectors.toList()))
                .exceptionally(ex -> new ArrayList<>())
                .join();
    }

    @JsonIgnore
    public List<String> getTripIds() {
        return getTrips().stream()
                .map(Trip::getId)
                .collect(Collectors.toList());
    }

    public void setEmail(final String email) {
        this.email = (email == null) ? null : email.trim().toLowerCase(Locale.getDefault());
    }

    public int compareTo(final Person other) {
        return peopleSorter.compare(this, other);
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
