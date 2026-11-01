package org.paulsens.trip.util;

import java.time.LocalDate;
import java.util.OptionalInt;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class AgesTest {
    private static final LocalDate START = LocalDate.of(2027, 3, 5);

    @Test
    public void ageIsWholeYearsOnTheGivenDay() {
        assertEquals(Ages.ageAt(LocalDate.of(2016, 3, 5), START), OptionalInt.of(11), "the birthday itself counts");
        assertEquals(Ages.ageAt(LocalDate.of(2016, 3, 6), START), OptionalInt.of(10), "one day short");
        assertEquals(Ages.ageAt(START, START), OptionalInt.of(0));
    }

    @Test
    public void missingOrFutureBirthdatesHaveNoAge() {
        assertEquals(Ages.ageAt(null, START), OptionalInt.empty());
        assertEquals(Ages.ageAt(START, null), OptionalInt.empty());
        assertEquals(Ages.ageAt(START.plusDays(1), START), OptionalInt.empty());
    }
}
