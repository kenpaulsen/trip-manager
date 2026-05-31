package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Data;

/**
 * A single discount code offered for a {@link Trip} (e.g. "EARLYBIRD2026" → $20 off). The full
 * set of codes for a trip lives in {@link Trip#getDiscountCodes()} so codes can be added, changed,
 * or removed as <em>data</em> — without a code change or redeploy. Mirrors the {@link AdmissionOption}
 * pattern.
 *
 * <p>A registrant types the {@link #code} on the registration page; the matched code's {@link #id}
 * is stored on the {@link Registration} (under {@link Registration#OPT_DISCOUNT_CODE}). Pricing and
 * validation semantics are computed in {@code org.paulsens.trip.action.RegistrationCommands}, not
 * here — this is a plain data holder.
 *
 * <p>All fields are optional from a JSON standpoint: trips persisted before this feature existed
 * simply have no discount codes, and (because the shared mapper ignores unknown properties) older
 * code reading newer JSON is unaffected.
 */
@Data
public final class DiscountCode implements Serializable {
    /** How a code's {@link DiscountCode#amount} is applied to the admission-option price. */
    public enum DiscountType {
        /** {@code amount} is the exact final price; requires the chosen option to be in
         *  {@link DiscountCode#validOptionIds} or registration/payment is blocked. */
        EXACT,
        /** {@code amount} is subtracted from the option price (final price floored at $0). */
        DISCOUNT_BY
    }

    /** Stable id stored on the {@link Registration} ({@link Registration#OPT_DISCOUNT_CODE}). */
    private String id;
    /** The human-entered code a registrant types to apply this discount (e.g. "EARLYBIRD2026"). */
    private String code;
    /** Admin-facing description, shown on the confirm page / email when the code is used. */
    private String description;
    /** Last day (inclusive) the code may be applied; {@code null} means no expiration. */
    private LocalDate expiration;
    /** How {@link #amount} is interpreted (defaults to {@link DiscountType#DISCOUNT_BY}). */
    private DiscountType discountType;
    /** The exact price ({@link DiscountType#EXACT}) or amount off ({@link DiscountType#DISCOUNT_BY}). */
    private BigDecimal amount;
    /** Admission-option ids this EXACT code is valid for; ignored for DISCOUNT_BY. */
    private List<String> validOptionIds;
    /** Whether this code is currently offered (defaults true). */
    private Boolean enabled;

    @JsonCreator
    public DiscountCode(
            @JsonProperty("id") final String id,
            @JsonProperty("code") final String code,
            @JsonProperty("description") final String description,
            @JsonProperty("expiration") final LocalDate expiration,
            @JsonProperty("discountType") final DiscountType discountType,
            @JsonProperty("amount") final BigDecimal amount,
            @JsonProperty("validOptionIds") final List<String> validOptionIds,
            @JsonProperty("enabled") final Boolean enabled) {
        this.id = id;
        this.code = code;
        this.description = description;
        this.expiration = expiration;
        this.discountType = (discountType == null) ? DiscountType.DISCOUNT_BY : discountType;
        this.amount = amount;
        this.validOptionIds = (validOptionIds == null) ? new ArrayList<>() : new ArrayList<>(validOptionIds);
        this.enabled = (enabled == null) || enabled;
    }

    /**
     * Comma-separated view of {@link #validOptionIds}, for editing in the admin UI (JSF cannot bind
     * a plain text field directly to a {@code List}). Not serialized to JSON ({@code validOptionIds} is).
     *
     * @return e.g. {@code "full,full-rosen"}, or {@code ""} when none are set.
     */
    @JsonIgnore
    public String getValidOptionIdsString() {
        return (validOptionIds == null) ? "" : String.join(",", validOptionIds);
    }

    /** Sets {@link #validOptionIds} from a comma-separated string; blank entries are dropped. */
    @JsonIgnore
    public void setValidOptionIdsString(final String csv) {
        if ((csv == null) || csv.isBlank()) {
            this.validOptionIds = new ArrayList<>();
        } else {
            this.validOptionIds = Arrays.stream(csv.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toCollection(ArrayList::new));
        }
    }
}
