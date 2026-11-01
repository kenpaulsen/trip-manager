package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.Data;

/**
 * A discount code of a trip's priced checkout (docs/checkout.md), entered per person. Its {@link #concession}
 * applies to the after-age price of the choices it is valid for ({@link #validChoiceIds}, empty = every priced
 * choice): SPEAKER as FIXED_PRICE $0 for "Full weekend" only, EARLY10 as 10% off. Uses are COUNTED from the
 * registrations that carry the code, never stored here, so checkout traffic never writes the trip row.
 */
@Data
public final class DiscountCode implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    private String code;
    private String note;
    private Concession concession;
    /** The last day the code may be used, inclusive; null never expires. */
    private LocalDate expiresOn;
    private List<String> validChoiceIds;
    /** Null reads as enabled. */
    private Boolean enabled;
    /** How many registrations may use it; null is unlimited. */
    private Integer maxUses;

    @JsonCreator
    public DiscountCode(
            @JsonProperty("code") final String code,
            @JsonProperty("note") final String note,
            @JsonProperty("concession") final Concession concession,
            @JsonProperty("expiresOn") final LocalDate expiresOn,
            @JsonProperty("validChoiceIds") final List<String> validChoiceIds,
            @JsonProperty("enabled") final Boolean enabled,
            @JsonProperty("maxUses") final Integer maxUses) {
        this.code = normalize(code);
        this.note = note;
        this.concession = (concession == null) ? new Concession() : concession;
        this.expiresOn = expiresOn;
        this.validChoiceIds = (validChoiceIds == null) ? new ArrayList<>() : new ArrayList<>(validChoiceIds);
        this.enabled = enabled;
        this.maxUses = (maxUses == null || maxUses < 0) ? null : maxUses;
    }

    /** An empty code: what the editor's "Add code" creates. */
    public DiscountCode() {
        this(null, null, null, null, null, null, null);
    }

    /** Codes are matched trimmed and case-insensitively: stored upper-case, compared the same way. */
    public static String normalize(final String code) {
        return (code == null || code.isBlank()) ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    public void setCode(final String code) {
        this.code = normalize(code);
    }

    @JsonIgnore
    public boolean isActive() {
        return enabled == null || enabled;
    }

    public void setActive(final boolean active) {
        this.enabled = active ? null : Boolean.FALSE;
    }

    /** Whether the code is past its last day on {@code today}. */
    public boolean isExpiredOn(final LocalDate today) {
        return expiresOn != null && today != null && today.isAfter(expiresOn);
    }

    /** Whether the code may discount this choice (no list means every choice). */
    public boolean appliesTo(final String choiceId) {
        return validChoiceIds.isEmpty() || validChoiceIds.contains(choiceId);
    }
}
