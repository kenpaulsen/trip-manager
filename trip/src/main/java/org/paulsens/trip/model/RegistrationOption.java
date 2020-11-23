package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import lombok.Value;

@Value
public class RegistrationOption implements Serializable {
    int id;
    String shortDesc;
    String longDesc;

    @JsonCreator
    public RegistrationOption(
            @JsonProperty("id") int id,
            @JsonProperty("shortDesc") String shortDesc,
            @JsonProperty("longDesc") String longDesc) {
        this.id = id;
        this.shortDesc = shortDesc;
        this.longDesc = longDesc;
    }
}
