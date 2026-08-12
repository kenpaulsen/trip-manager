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

    /**
     * The {@code Registration.options} map key for this option. The map is String-keyed while the id is an
     * int, and EL will not coerce an int subscript to a String map key -- which is why the single-person
     * registration page had to build its inputs with dynamic components. A settable EL binding like
     * {@code #&#123;regs[pid].options[opt.key]&#125;} needs this getter.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getKey() {
        return String.valueOf(id);
    }

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