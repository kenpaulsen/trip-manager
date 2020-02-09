package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import lombok.Value;
import lombok.experimental.Wither;

@Value
public class Passport {
    @Wither String number;
    @Wither String country;
    @Wither LocalDate expires;
    @Wither LocalDate issued;

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
}
