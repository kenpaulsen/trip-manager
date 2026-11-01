package org.paulsens.trip.util;

import java.time.LocalDate;
import java.time.Period;
import java.util.OptionalInt;

/**
 * The one way to compute a person's age. Pricing reads it at the trip's FIRST day (a child who turns eleven
 * the week before the conference pays the adult price), never "today" as the mir2026 branch did.
 */
public final class Ages {
    private Ages() {
    }

    /** Whole years from {@code birthdate} to {@code on}; empty when either is missing or the birthdate is later. */
    public static OptionalInt ageAt(final LocalDate birthdate, final LocalDate on) {
        if (birthdate == null || on == null || birthdate.isAfter(on)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(Period.between(birthdate, on).getYears());
    }
}
