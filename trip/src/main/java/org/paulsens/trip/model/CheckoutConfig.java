package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * A trip's priced-checkout POLICY (docs/checkout.md), on {@code Trip.checkout}. The prices themselves live on the
 * trip's typed registration questions ({@link OptionChoice#getPriceCents()}); this holds what applies across them:
 * whether paid checkout is on, whether a paid registration is confirmed outright, the registration window, the age
 * bands and the discount codes. Nothing here is a counter: code uses and capacity are counted from registrations,
 * so checkout traffic never writes the trip row.
 */
@Data
public final class CheckoutConfig implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    /** Null reads as off: every trip stored before checkout existed keeps the classic registration. */
    private Boolean enabled;
    /** Null reads as ON: a paid registration is CONFIRMED (roster, ticket, mail) without an approval step. */
    private Boolean autoConfirmPaid;
    /** Registration opens at; null is "open now". */
    private LocalDateTime opensAt;
    /** Registration closes at; null is "until the trip starts". */
    private LocalDateTime closesAt;
    private List<AgeBand> ageBands;
    private List<DiscountCode> codes;

    @JsonCreator
    public CheckoutConfig(
            @JsonProperty("enabled") final Boolean enabled,
            @JsonProperty("autoConfirmPaid") final Boolean autoConfirmPaid,
            @JsonProperty("opensAt") final LocalDateTime opensAt,
            @JsonProperty("closesAt") final LocalDateTime closesAt,
            @JsonProperty("ageBands") final List<AgeBand> ageBands,
            @JsonProperty("codes") final List<DiscountCode> codes) {
        this.enabled = enabled;
        this.autoConfirmPaid = autoConfirmPaid;
        this.opensAt = opensAt;
        this.closesAt = closesAt;
        this.ageBands = (ageBands == null) ? new ArrayList<>() : new ArrayList<>(ageBands);
        this.codes = (codes == null) ? new ArrayList<>() : new ArrayList<>(codes);
    }

    public CheckoutConfig() {
        this(null, null, null, null, null, null);
    }

    @JsonIgnore
    public boolean isOn() {
        return Boolean.TRUE.equals(enabled);
    }

    public void setOn(final boolean on) {
        this.enabled = on ? Boolean.TRUE : null;
    }

    @JsonIgnore
    public boolean isConfirmPaid() {
        return autoConfirmPaid == null || autoConfirmPaid;
    }

    public void setConfirmPaid(final boolean confirm) {
        this.autoConfirmPaid = confirm ? null : Boolean.FALSE;
    }

    /** Whether registration is open at {@code now} by this window (the trip's start is checked by the caller). */
    public boolean isOpenAt(final LocalDateTime now) {
        return (opensAt == null || !now.isBefore(opensAt)) && (closesAt == null || now.isBefore(closesAt));
    }

    /** The band covering {@code age}, or null (bands never overlap: {@link #problems()} refuses it). */
    public AgeBand bandFor(final int age) {
        return ageBands.stream().filter(band -> band.covers(age)).findFirst().orElse(null);
    }

    /** The code a person typed, matched trimmed and case-insensitively, or null. */
    public DiscountCode code(final String typed) {
        final String wanted = DiscountCode.normalize(typed);
        if (wanted.isEmpty()) {
            return null;
        }
        return codes.stream().filter(code -> wanted.equals(code.getCode())).findFirst().orElse(null);
    }

    /** What is wrong with this policy as an editor left it (empty when it may be saved). */
    public List<String> problems() {
        final List<String> problems = new ArrayList<>();
        if (opensAt != null && closesAt != null && !closesAt.isAfter(opensAt)) {
            problems.add("Registration must close after it opens.");
        }
        for (int i = 0; i < ageBands.size(); i++) {
            final AgeBand band = ageBands.get(i);
            if (band.getMaxAge() != null && band.getMaxAge() < band.getMinAge()) {
                problems.add("Age band " + band.getRangeText() + " ends before it starts.");
            }
            for (int j = i + 1; j < ageBands.size(); j++) {
                if (band.overlaps(ageBands.get(j))) {
                    problems.add("Age bands " + band.getRangeText() + " and " + ageBands.get(j).getRangeText()
                            + " overlap.");
                }
            }
        }
        final List<String> seen = new ArrayList<>();
        for (final DiscountCode code : codes) {
            if (code.getCode().isEmpty()) {
                problems.add("Every discount code needs its code.");
            } else if (seen.contains(code.getCode())) {
                problems.add("The code " + code.getCode() + " is listed twice.");
            } else {
                seen.add(code.getCode());
            }
        }
        return problems;
    }
}
