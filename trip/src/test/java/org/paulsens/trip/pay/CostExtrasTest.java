package org.paulsens.trip.pay;

import org.paulsens.trip.model.CostExtra;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

/** The cost-extra arithmetic as a worked-example table: 12 guest-nights, 5 room-nights, 3 stays. */
public class CostExtrasTest {
    private static final int GUEST_NIGHTS = 12;
    private static final int ROOM_NIGHTS = 5;
    private static final int STAYS = 3;

    @Test
    public void quantityFollowsTheBasis() {
        assertEquals(CostExtras.quantity(extra(CostExtra.Basis.PER_PERSON_NIGHT, 250), GUEST_NIGHTS, ROOM_NIGHTS,
                STAYS), 12);
        assertEquals(CostExtras.quantity(extra(CostExtra.Basis.PER_ROOM_NIGHT, 250), GUEST_NIGHTS, ROOM_NIGHTS,
                STAYS), 5);
        assertEquals(CostExtras.quantity(extra(CostExtra.Basis.PER_STAY, 250), GUEST_NIGHTS, ROOM_NIGHTS, STAYS), 3);
        assertEquals(CostExtras.quantity(extra(CostExtra.Basis.FIXED, 250), GUEST_NIGHTS, ROOM_NIGHTS, STAYS), 1,
                "FIXED is once for the option whatever the counts");
        assertEquals(CostExtras.quantity(extra(CostExtra.Basis.FIXED, 250), 0, 0, 0), 1);
        assertEquals(CostExtras.quantity(extra(CostExtra.Basis.PER_STAY, 250), 0, 0, -2), 0, "Never negative");
        assertEquals(CostExtras.quantity(null, GUEST_NIGHTS, ROOM_NIGHTS, STAYS), 0);
    }

    @Test
    public void amountIsTheUnitTimesTheQuantityAndKeepsItsSign() {
        assertEquals(CostExtras.amountCents(extra(CostExtra.Basis.PER_PERSON_NIGHT, 250), GUEST_NIGHTS, ROOM_NIGHTS,
                STAYS), 3000L, "$2.50 x 12 guest-nights");
        assertEquals(CostExtras.amountCents(extra(CostExtra.Basis.PER_ROOM_NIGHT, 1000), GUEST_NIGHTS, ROOM_NIGHTS,
                STAYS), 5000L, "$10.00 x 5 room-nights");
        assertEquals(CostExtras.amountCents(extra(CostExtra.Basis.PER_STAY, 3000), GUEST_NIGHTS, ROOM_NIGHTS, STAYS),
                9000L, "$30.00 x 3 stays");
        assertEquals(CostExtras.amountCents(extra(CostExtra.Basis.FIXED, 1500), GUEST_NIGHTS, ROOM_NIGHTS, STAYS),
                1500L, "A fixed fee once");
        assertEquals(CostExtras.amountCents(extra(CostExtra.Basis.FIXED, -2000), GUEST_NIGHTS, ROOM_NIGHTS, STAYS),
                -2000L, "A coupon stays negative");
        assertEquals(CostExtras.amountCents(extra(CostExtra.Basis.PER_STAY, -500), GUEST_NIGHTS, ROOM_NIGHTS, STAYS),
                -1500L, "A per-stay credit multiplies too");
        assertEquals(CostExtras.amountCents(extra(CostExtra.Basis.PER_PERSON_NIGHT, 250), 0, 0, 0), 0L);
        assertEquals(CostExtras.amountCents(null, GUEST_NIGHTS, ROOM_NIGHTS, STAYS), 0L);
    }

    @Test
    public void describeNamesTheUnitAmountAndTheCount() {
        assertEquals(CostExtras.describe(extra(CostExtra.Basis.PER_PERSON_NIGHT, 250), GUEST_NIGHTS, ROOM_NIGHTS,
                STAYS), "City tax: $2.50 × 12 guest-nights");
        assertEquals(CostExtras.describe(extra(CostExtra.Basis.PER_ROOM_NIGHT, 1000), GUEST_NIGHTS, 1, STAYS),
                "City tax: $10.00 × 1 room-night");
        assertEquals(CostExtras.describe(extra(CostExtra.Basis.PER_STAY, 3000), GUEST_NIGHTS, ROOM_NIGHTS, STAYS),
                "City tax: $30.00 × 3 stays");
        assertEquals(CostExtras.describe(extra(CostExtra.Basis.FIXED, 1500), GUEST_NIGHTS, ROOM_NIGHTS, STAYS),
                "City tax: $15.00");
        assertEquals(CostExtras.describe(extra(CostExtra.Basis.FIXED, -2000), GUEST_NIGHTS, ROOM_NIGHTS, STAYS),
                "City tax: -$20.00", "The sign leads the dollar sign");
        assertEquals(CostExtras.describe(CostExtra.builder().amountCents(500).build(), 0, 0, 0), "$5.00",
                "No label, no colon");
        assertEquals(CostExtras.describe(CostExtra.builder().label("  ").amountCents(500).build(), 0, 0, 0), "$5.00");
        assertEquals(CostExtras.describe(null, GUEST_NIGHTS, ROOM_NIGHTS, STAYS), "");
    }

    @Test
    public void formatSignedPutsTheMinusFirst() {
        assertEquals(CostExtras.formatSigned(123456), "$1,234.56");
        assertEquals(CostExtras.formatSigned(0), "$0.00");
        assertEquals(CostExtras.formatSigned(-5), "-$0.05");
        assertEquals(CostExtras.formatSigned(-123456), "-$1,234.56");
    }

    private static CostExtra extra(final CostExtra.Basis basis, final long cents) {
        return CostExtra.builder().label("City tax").basis(basis).amountCents(cents).build();
    }
}
