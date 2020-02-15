package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.UUID;

import lombok.Data;

@Data
public final class Person implements Serializable {
    String id;
    String first;
    String middle;
    String last;
    LocalDate birthdate;
    String cell;
    String email;
    String tsa;
    Address address;
    Passport passport;
    String notes;

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
        this.address = address;
        this.passport = passport;
        this.notes = notes;
    }

    public Person() {
        this.id = getNewId();
        this.first = null;
        this.middle = null;
        this.last = null;
        this.birthdate = null;
        this.cell = null;
        this.email = null;
        this.tsa = null;
        this.address = new Address();
        this.passport = new Passport();
        this.notes = null;
    }

    private String getNewId() {
        return UUID.randomUUID().toString();
    }
}
