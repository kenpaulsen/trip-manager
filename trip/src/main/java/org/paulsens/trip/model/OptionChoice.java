package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Data;
import org.paulsens.trip.pay.MoneyMath;

/**
 * One answer a CHOICE or MULTI_CHOICE {@link RegistrationOption} offers. On a trip with priced checkout the
 * choice also carries what it costs, so "Full weekend / Friday only / Saturday only" is simply a required
 * single-choice question whose choices have prices (see {@code docs/checkout.md}). Every money and capacity
 * field is optional: an unpriced trip uses choices only to turn a free-text question into a dropdown.
 *
 * <p>The registrant's answer stores the choice {@link #id}, never its label, so an admin may rename a choice
 * without orphaning the answers already given.
 */
@Data
public final class OptionChoice implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    private String id;
    private String label;
    /** What this choice costs in cents; 0 is free (and the default). */
    private long priceCents;
    /** How many registrations may pick this choice; null is unlimited. */
    private Integer capacity;
    /**
     * A statement the registrant must tick to pick this choice ("I am staying at the conference hotel");
     * blank requires nothing. The text they agreed to is kept on their registration.
     */
    private String attestationText;
    /** The ISO dates this choice covers ("2027-07-16"), for per-day counts; empty means every day. */
    private List<String> days;
    /** Whether the choice is offered; null reads as offered, so a stored choice never disappears by accident. */
    private Boolean enabled;

    @JsonCreator
    public OptionChoice(
            @JsonProperty("id") final String id,
            @JsonProperty("label") final String label,
            @JsonProperty("priceCents") final long priceCents,
            @JsonProperty("capacity") final Integer capacity,
            @JsonProperty("attestationText") final String attestationText,
            @JsonProperty("days") final List<String> days,
            @JsonProperty("enabled") final Boolean enabled) {
        this.id = (id == null || id.isBlank()) ? newId() : id.trim();
        this.label = (label == null) ? "" : label.trim();
        this.priceCents = Math.max(0L, priceCents);
        this.capacity = (capacity == null || capacity < 0) ? null : capacity;
        this.attestationText = blankToNull(attestationText);
        this.days = (days == null) ? new ArrayList<>() : new ArrayList<>(days);
        this.enabled = enabled;
    }

    /** A free, enabled choice with a fresh id: what the editor's "Add choice" creates. */
    public OptionChoice(final String label) {
        this(null, label, 0L, null, null, null, null);
    }

    /** Short, because it is what every answer stores; unique within a trip is all it needs to be. */
    public static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @JsonIgnore
    public boolean isOffered() {
        return enabled == null || enabled;
    }

    /** The editor checkbox's write half: offered is stored as null (the default), withdrawn as false. */
    public void setOffered(final boolean offered) {
        this.enabled = offered ? null : Boolean.FALSE;
    }

    @JsonIgnore
    public boolean isPriced() {
        return priceCents > 0;
    }

    /** What a registration form shows: the label, with its price when it has one ("Full weekend ($150.00)"). */
    @JsonIgnore
    public String getDisplayLabel() {
        return isPriced() ? label + " (" + MoneyMath.formatCents(priceCents) + ")" : label;
    }

    /** Whether picking this choice requires ticking {@link #attestationText}. */
    @JsonIgnore
    public boolean isAttestationRequired() {
        return attestationText != null;
    }

    public void setPriceCents(final long priceCents) {
        this.priceCents = Math.max(0L, priceCents);
    }

    public void setAttestationText(final String text) {
        this.attestationText = blankToNull(text);
    }

    /** The {@link #days} as comma-separated text, for the editor's one-line cell. */
    @JsonIgnore
    public String getDaysText() {
        return String.join(", ", days);
    }

    public void setDaysText(final String text) {
        this.days = splitCsv(text);
    }

    /** The price in dollars for the editor's numeric cell; null and negatives read as free. */
    @JsonIgnore
    public Double getPriceDollars() {
        return priceCents / 100.0;
    }

    public void setPriceDollars(final Double dollars) {
        this.priceCents = (dollars == null || dollars < 0) ? 0L : Math.round(dollars * 100);
    }

    static List<String> splitCsv(final String text) {
        final List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (final String part : text.split(",")) {
            final String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static String blankToNull(final String text) {
        return (text == null || text.isBlank()) ? null : text.trim();
    }
}
