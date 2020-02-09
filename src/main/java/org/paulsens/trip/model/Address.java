package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;
import lombok.experimental.Wither;

@Value
public class Address {
    @Wither
    String street;
    @Wither
    String city;
    @Wither
    String state;
    @Wither
    String zip;

    public Address(
            @JsonProperty("street") String street,
            @JsonProperty("city") String city,
            @JsonProperty("state") String state,
            @JsonProperty("zip") String zip) {
        this.street = street;
        this.city = city;
        this.state = state;
        this.zip = zip;
    }
}
