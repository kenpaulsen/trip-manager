package org.paulsens.trip.action;

import static org.testng.Assert.assertEquals;

import java.util.List;
import org.paulsens.trip.model.CostExtra;
import org.testng.annotations.Test;

public class OfferSchedulesTest {

    @Test
    public void noExtrasMeansNoLines() {
        assertEquals(OfferSchedules.extraLines(List.of()), List.of());
    }

    @Test
    public void nightlyExtrasSumWithinTheirBasisAndOneTimeOnesAreListedByName() {
        final List<CostExtra> extras = List.of(
                new CostExtra("Cleaning", CostExtra.Basis.PER_STAY, 2000L),
                new CostExtra("City tax", CostExtra.Basis.PER_PERSON_NIGHT, 150L),
                new CostExtra("Resort fee", CostExtra.Basis.PER_ROOM_NIGHT, 500L),
                new CostExtra("Coupon", CostExtra.Basis.FIXED, -1000L),
                new CostExtra("Tourist tax", CostExtra.Basis.PER_PERSON_NIGHT, 250L));
        assertEquals(OfferSchedules.extraLines(extras), List.of(
                "+$4.00/night per person (City tax, Tourist tax)",
                "+$5.00/night per room (Resort fee)",
                "Cleaning: $20.00 per stay",
                "Coupon: -$10.00 once"));
    }

    @Test
    public void aNightlyDiscountReadsNegative() {
        assertEquals(OfferSchedules.extraLines(List.of(
                new CostExtra("Loyalty", CostExtra.Basis.PER_ROOM_NIGHT, -300L))),
                List.of("-$3.00/night per room (Loyalty)"));
    }
}
