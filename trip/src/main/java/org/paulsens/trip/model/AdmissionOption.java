package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Data;

/**
 * A single priced conference admission / ticket choice offered for a {@link Trip} (e.g.
 * "Full Weekend Admission (Standard)" at $340). The full set of options for a trip lives in
 * {@link Trip#getAdmissionOptions()} so that options and prices can be added, changed, or
 * removed as <em>data</em> — without a code change or redeploy.
 *
 * <p>All fields are optional from a JSON standpoint: trips persisted before this feature
 * existed simply have no admission options, and (because the shared mapper is configured to
 * ignore unknown properties) older code reading newer JSON is unaffected by fields it does
 * not know about. Pricing semantics are computed in
 * {@code org.paulsens.trip.action.RegistrationCommands}, not here.
 */
@Data
public final class AdmissionOption implements Serializable {
    /** Stable id stored on the {@link Registration} ({@link Registration#OPT_ADMISSION}). */
    private String id;
    /** Human-readable label shown in the dropdown, the receipt/transaction, and the email. */
    private String label;
    /** Adult / standard price in USD. The child price is derived (see RegistrationCommands). */
    private BigDecimal price;
    /** Day codes included by this option (e.g. {@code ["FRI","SAT","SUN"]}); used for headcount. */
    private List<String> days;
    /** {@code true} for the Rosen-Centre-guest discounted variants (hidden from children). */
    private Boolean rosenDiscounted;
    /** Whether this option is currently offered on the registration page (defaults true). */
    private Boolean show;

    @JsonCreator
    public AdmissionOption(
            @JsonProperty("id") final String id,
            @JsonProperty("label") final String label,
            @JsonProperty("price") final BigDecimal price,
            @JsonProperty("days") final List<String> days,
            @JsonProperty("rosenDiscounted") final Boolean rosenDiscounted,
            @JsonProperty("show") final Boolean show) {
        this.id = id;
        this.label = label;
        this.price = price;
        this.days = (days == null) ? new ArrayList<>() : new ArrayList<>(days);
        this.rosenDiscounted = (rosenDiscounted != null) && rosenDiscounted;
        this.show = (show == null) || show;
    }

    /**
     * Comma-separated view of {@link #days}, for editing in the admin UI (JSF cannot bind a
     * plain text field directly to a {@code List}). Not serialized to JSON ({@code days} is).
     *
     * @return e.g. {@code "FRI,SAT,SUN"}, or {@code ""} when no days are set.
     */
    @JsonIgnore
    public String getDaysString() {
        return (days == null) ? "" : String.join(",", days);
    }

    /** Sets {@link #days} from a comma-separated string; blank entries are dropped. */
    @JsonIgnore
    public void setDaysString(final String csv) {
        if ((csv == null) || csv.isBlank()) {
            this.days = new ArrayList<>();
        } else {
            this.days = Arrays.stream(csv.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toCollection(ArrayList::new));
        }
    }
}
