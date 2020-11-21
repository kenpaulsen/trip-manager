package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import lombok.Data;
import lombok.Value;

@Data
public final class Person implements Serializable {
    private Id id;
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
            @JsonProperty("managedUsers") List<Person.Id> managedUsers) {
        this.id = (id == null) ? Id.newInstance() : id;
        this.nickname = nickname;
        this.first = first;
        this.middle = middle;
        this.last = last;
        this.birthdate = birthdate;
        this.cell = cell;
        this.email = email;
        this.tsa = tsa;
        this.address = (address == null) ? new Address() : address;
        this.passport = (passport == null) ? new Passport() : passport;
        this.notes = notes;
        this.managedUsers = (managedUsers == null) ? new ArrayList<>() : managedUsers;
    }

    public Person() {
        this(null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @JsonIgnore
    public String getPreferredName() {
        return nickname == null ? first : nickname;
    }

    @Value
    public static class Id {
        @JsonValue
        String value;

        public static Id from(final String id) {
            return new Id(id);
        }

        public static Id newInstance() {
            return new Id(UUID.randomUUID().toString());
        }
    }
}