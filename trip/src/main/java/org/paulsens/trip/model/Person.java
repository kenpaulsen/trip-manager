package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Data;
import lombok.Value;
import org.paulsens.trip.dynamo.DynamoUtils;

@Data
@Builder
public final class Person implements Serializable {
    private Person.Id id;
    private String nickname;
    private String first;
    private String middle;
    private String last;
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
        this.nickname = nickname;
        this.first = first;
        this.middle = middle;
        this.last = last;
        this.birthdate = birthdate;
        this.cell = cell;
        this.email = email == null ? null : email.trim().toLowerCase(Locale.getDefault());
        this.tsa = tsa;
        this.address = (address == null) ? new Address() : address;
        this.passport = (passport == null) ? new Passport() : passport;
        this.notes = notes;
        this.managedUsers = (managedUsers == null) ? new ArrayList<>() : managedUsers;
        this.emergencyContactName = emergencyName;
        this.emergencyContactPhone = emergencyPhone;
    }

    public Person() {
        this(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
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
        return DynamoUtils.getInstance().getTrips()
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
        public int compareTo(Id o) {
            return value.compareTo(o.getValue());
        }
    }
}
