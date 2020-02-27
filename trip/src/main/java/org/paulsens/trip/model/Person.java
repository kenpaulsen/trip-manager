package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.UUID;

import lombok.Data;

@Data
public final class Person implements Serializable {
    private String id;
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

    public Person(
            @JsonProperty("id") String id,
            @JsonProperty("first") String first,
            @JsonProperty("middle") String middle,
            @JsonProperty("last") String last,
            @JsonProperty("birthdate") LocalDate birthdate,
            @JsonProperty("cell") String cell,
            @JsonProperty("email") String email,
            @JsonProperty("tsa") String tsa,
            @JsonProperty("address") Address address,
            @JsonProperty("passport") Passport passport,
            @JsonProperty("notes") String notes) {
        this.id = ((id == null) || id.isEmpty()) ? getNewId() : id;
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
    }

    public Person() {
        this(getNewId(), null, null, null, null, null, null, null, null, null, null);
    }

    private static String getNewId() {
        return UUID.randomUUID().toString();
    }
}
