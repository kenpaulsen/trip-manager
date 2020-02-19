package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import lombok.Data;

@Data
public final class Address implements Serializable {
    private String street;
    private String city;
    private String state;
    private String zip;

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

    public Address() {
        this.street = null;
        this.city = null;
        this.state = null;
        this.zip = null;
    }
}
