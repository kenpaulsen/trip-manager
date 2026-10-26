package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;
import lombok.Builder;
import lombok.Data;

/**
 * One foreign currency an expense report uses and its rate to the dollar, typed by the trip administrator the
 * first time the currency is used and editable later. The rate runs the way the spreadsheet wrote it,
 * foreign units per ONE US dollar ("1 USD = 0.83 EUR"), stored in millionths so no floating point reaches a
 * stored figure. USD itself is implicit and never has a row.
 *
 * <p>The constructor normalizes: the code is trimmed and upper-cased and anything but three letters is null;
 * the rate never goes negative; a blank note is null.
 */
@Data
public final class CurrencyRate implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    /** Millionths per unit: a rate of 0.83 is stored as 830_000. */
    public static final long MICROS = 1_000_000L;

    private String code;
    private long perUsdMicros;
    private String note;

    @Builder
    @JsonCreator
    public CurrencyRate(
            @JsonProperty("code") final String code,
            @JsonProperty("perUsdMicros") final long perUsdMicros,
            @JsonProperty("note") final String note) {
        this.code = normalizeCode(code);
        this.perUsdMicros = Math.max(0L, perUsdMicros);
        this.note = BudgetLine.trimToNull(note);
    }

    public CurrencyRate() {
        this(null, 0L, null);
    }

    /** The ISO-style code a person typed, upper-cased; null unless it is exactly three letters. */
    public static String normalizeCode(final String code) {
        if (code == null) {
            return null;
        }
        final String trimmed = code.trim().toUpperCase(Locale.ROOT);
        return trimmed.matches("[A-Z]{3}") ? trimmed : null;
    }
}
