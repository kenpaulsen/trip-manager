package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.time.LocalDate;
import lombok.Data;

@Data
public final class Passport implements Serializable {
    private String number;
    private String country;
    private LocalDate expires;
    private LocalDate issued;

    public Passport(
            @JsonProperty("number") String number,
            @JsonProperty("country") String country,
            @JsonProperty("expires") LocalDate expires,
            @JsonProperty("issued") LocalDate issued) {
        this.number = number;
        this.country = country;
        this.expires = expires;
        this.issued = issued;
    }

    public Passport() {
        this.number = null;
        this.country = null;
        this.expires = null;
        this.issued = null;
    }
}
