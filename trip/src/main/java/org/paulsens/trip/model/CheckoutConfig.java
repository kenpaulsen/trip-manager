package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * A trip's priced-checkout POLICY (docs/checkout.md), on {@code Trip.checkout}. The prices themselves live on the
 * trip's typed registration questions ({@link OptionChoice#getPriceCents()}); this holds what applies across them:
 * whether registration requires payment, what is due at registration (the full price or a per-person deposit),
 * whether a paid registration is confirmed outright, the registration window, the age bands and the discount codes.
 * Nothing here is a counter: code uses and capacity are counted from registrations, so checkout traffic never
 * writes the trip row.
 */
@Data
public final class CheckoutConfig implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    /** The dialog's "payment due" radio values. */
    public static final String DUE_FULL = "FULL";
    public static final String DUE_DEPOSIT = "DEPOSIT";

    /** Null reads as off: every trip stored before checkout existed keeps the classic registration. */
    private Boolean enabled;
    /**
     * Null reads as OFF (since 2026-11-02; before, null read as on): a paid registration waits as PENDING for a
     * registration admin's approval unless this is TRUE.
     */
    private Boolean autoConfirmPaid;
    /** Registration opens at; null is "open now". */
    private LocalDateTime opensAt;
    /** Registration closes at; null is "until the trip starts". */
    private LocalDateTime closesAt;
    private List<AgeBand> ageBands;
    private List<DiscountCode> codes;
    /**
     * What each person pays AT registration when less than their price; null means the full price. The rest is
     * billed to the person's trip balance (their Bill is always the full price) and paid later from the trip's
     * Pay page. A person whose price is below the deposit pays their price.
     */
    private Long depositCents;
    /** When the balance after a deposit is due; shown to registrants, never enforced. Null is "no date". */
    private LocalDate balanceDueOn;

    @JsonCreator
    public CheckoutConfig(
            @JsonProperty("enabled") final Boolean enabled,
            @JsonProperty("autoConfirmPaid") final Boolean autoConfirmPaid,
            @JsonProperty("opensAt") final LocalDateTime opensAt,
            @JsonProperty("closesAt") final LocalDateTime closesAt,
            @JsonProperty("ageBands") final List<AgeBand> ageBands,
            @JsonProperty("codes") final List<DiscountCode> codes,
            @JsonProperty("depositCents") final Long depositCents,
            @JsonProperty("balanceDueOn") final LocalDate balanceDueOn) {
        this.enabled = enabled;
        this.autoConfirmPaid = autoConfirmPaid;
        this.opensAt = opensAt;
        this.closesAt = closesAt;
        this.ageBands = (ageBands == null) ? new ArrayList<>() : new ArrayList<>(ageBands);
        this.codes = (codes == null) ? new ArrayList<>() : new ArrayList<>(codes);
        this.depositCents = depositCents;
        this.balanceDueOn = balanceDueOn;
    }

    /** A full-payment policy (the shape before deposits existed). */
    public CheckoutConfig(final Boolean enabled, final Boolean autoConfirmPaid, final LocalDateTime opensAt,
            final LocalDateTime closesAt, final List<AgeBand> ageBands, final List<DiscountCode> codes) {
        this(enabled, autoConfirmPaid, opensAt, closesAt, ageBands, codes, null, null);
    }

    public CheckoutConfig() {
        this(null, null, null, null, null, null, null, null);
    }

    @JsonIgnore
    public boolean isOn() {
        return Boolean.TRUE.equals(enabled);
    }

    public void setOn(final boolean on) {
        this.enabled = on ? Boolean.TRUE : null;
    }

    /** Whether a paid registration is CONFIRMED outright; the default (null) is to wait for approval. */
    @JsonIgnore
    public boolean isConfirmPaid() {
        return Boolean.TRUE.equals(autoConfirmPaid);
    }

    public void setConfirmPaid(final boolean confirm) {
        this.autoConfirmPaid = confirm ? Boolean.TRUE : null;
    }

    /** Whether registration takes a deposit rather than the full price. */
    @JsonIgnore
    public boolean isDeposit() {
        return depositCents != null;
    }

    /** What {@code priceCents} costs at registration under this policy: the deposit when it is less. */
    public long dueNowCents(final long priceCents) {
        return depositCents == null ? priceCents : Math.max(0L, Math.min(depositCents, priceCents));
    }

    /** The editor's radio: {@link #DUE_FULL} or {@link #DUE_DEPOSIT}. */
    @JsonIgnore
    public String getDueMode() {
        return isDeposit() ? DUE_DEPOSIT : DUE_FULL;
    }

    /**
     * Picking FULL clears the deposit; picking DEPOSIT keeps the amount typed so far (or starts at $0, which
     * {@link #problems()} refuses until an amount is entered). The radio sits ABOVE the amount field, so it is
     * decoded first and {@link #setDepositDollars} then knows whether its value is wanted.
     */
    public void setDueMode(final String mode) {
        if (DUE_DEPOSIT.equals(mode)) {
            if (depositCents == null) {
                depositCents = 0L;
            }
        } else {
            depositCents = null;
            balanceDueOn = null;
        }
    }

    /** The editor's deposit amount, in dollars; null while the policy takes the full price. */
    @JsonIgnore
    public Double getDepositDollars() {
        return depositCents == null ? null : depositCents / 100.0;
    }

    /** Ignored while the policy takes the full price (the radio, decoded first, decides that). */
    public void setDepositDollars(final Double dollars) {
        if (depositCents != null) {
            depositCents = (dollars == null || dollars < 0) ? 0L : Math.round(dollars * 100);
        }
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
        // The deposit matters only while registration requires payment: a policy switched off keeps whatever
        // was typed without blocking the trip's Save over it.
        if (isOn() && depositCents != null && depositCents <= 0) {
            problems.add("The deposit must be more than $0.00 (choose Full amount for no deposit).");
        }
        if (isOn() && balanceDueOn != null && opensAt != null && balanceDueOn.isBefore(opensAt.toLocalDate())) {
            problems.add("The balance due date is before registration opens.");
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
