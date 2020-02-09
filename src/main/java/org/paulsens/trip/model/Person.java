package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import lombok.Value;
import lombok.experimental.Wither;

@Value
public class Person {
    @Wither String first;
    @Wither String middle;
    @Wither String last;
    @Wither LocalDate birthdate;
    @Wither String cell;
    @Wither String email;
    @Wither String tsa;
    @Wither Address address;
    @Wither Passport passport;
    @Wither String notes;

    public Person(
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
}
