package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import lombok.Data;

/**
 * One age range of a trip's priced checkout (docs/checkout.md): a person whose age on the trip's FIRST day falls in
 * [{@link #minAge}, {@link #maxAge}] has the band's {@link #concession} applied to each priced choice whose question
 * lets age bands apply. "0-3 free" is COMPLIMENTARY; "4-10 at most $50" is FIXED_PRICE; a percentage or an amount
 * off work too. The adjustment is lodging's {@link Concession}, reused rather than a second discount type.
 */
@Data
public final class AgeBand implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    private String label;
    private int minAge;
    /** The oldest age in the band, inclusive; null is "and up". */
    private Integer maxAge;
    private Concession concession;

    @JsonCreator
    public AgeBand(
            @JsonProperty("label") final String label,
            @JsonProperty("minAge") final int minAge,
            @JsonProperty("maxAge") final Integer maxAge,
            @JsonProperty("concession") final Concession concession) {
        this.label = (label == null) ? "" : label.trim();
        this.minAge = Math.max(0, minAge);
        this.maxAge = maxAge;
        this.concession = (concession == null) ? new Concession() : concession;
    }

    /** An empty band: what the editor's "Add age band" creates. */
    public AgeBand() {
        this(null, 0, null, null);
    }

    /** Whether {@code age} falls in this band. */
    public boolean covers(final int age) {
        return age >= minAge && (maxAge == null || age <= maxAge);
    }

    /** Whether this band overlaps {@code other} (the editor refuses overlapping bands). */
    public boolean overlaps(final AgeBand other) {
        final int thisMax = maxAge == null ? Integer.MAX_VALUE : maxAge;
        final int otherMax = other.maxAge == null ? Integer.MAX_VALUE : other.maxAge;
        return minAge <= otherMax && other.minAge <= thisMax;
    }

    /** "4-10" or "65+", for labels and the breakdown when the band has no label of its own. */
    @JsonIgnore
    public String getRangeText() {
        return maxAge == null ? minAge + "+" : minAge + "-" + maxAge;
    }
}
