package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import lombok.Data;

@Data
public final class RegistrationOption implements Serializable {
    private int id;
    private String shortDesc;
    private String longDesc;
    private Boolean show;

    @JsonCreator
    public RegistrationOption(
            @JsonProperty("id") int id,
            @JsonProperty("shortDesc") String shortDesc,
            @JsonProperty("longDesc") String longDesc,
            @JsonProperty("show") Boolean show) {
        this.id = id;
        this.shortDesc = shortDesc;
        this.longDesc = longDesc;
        this.show = (show == null) ? Boolean.TRUE : show;
    }
}