package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.paulsens.trip.dynamo.DAO;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class CostExtraTest {

    @Test
    public void builderWithNoValuesEqualsNewInstance() {
        final CostExtra fresh = new CostExtra();
        assertEquals(CostExtra.builder().build(), fresh);
        assertEquals(fresh.getBasis(), CostExtra.Basis.FIXED, "A null basis reads as FIXED");
        assertEquals(fresh.getAmountCents(), 0L);
        assertNull(fresh.getLabel());
        assertEquals(fresh.unit(), "");
        assertEquals(fresh.unit(1), "");
    }

    @Test
    public void unitsFollowTheBasis() {
        assertEquals(extra(CostExtra.Basis.PER_PERSON_NIGHT).unit(), "guest-nights");
        assertEquals(extra(CostExtra.Basis.PER_PERSON_NIGHT).unit(1), "guest-night");
        assertEquals(extra(CostExtra.Basis.PER_PERSON_NIGHT).unit(3), "guest-nights");
        assertEquals(extra(CostExtra.Basis.PER_ROOM_NIGHT).unit(), "room-nights");
        assertEquals(extra(CostExtra.Basis.PER_ROOM_NIGHT).unit(1), "room-night");
        assertEquals(extra(CostExtra.Basis.PER_STAY).unit(), "stays");
        assertEquals(extra(CostExtra.Basis.PER_STAY).unit(1), "stay");
        assertEquals(extra(CostExtra.Basis.PER_STAY).unit(0), "stays");
        assertEquals(extra(CostExtra.Basis.FIXED).unit(), "");
    }

    @Test
    public void amountIsSignedLabelIsTrimmedAndEqualityIsByValue() {
        final CostExtra coupon = CostExtra.builder().label("  Group coupon ").basis(CostExtra.Basis.FIXED)
                .amountCents(-2000).build();
        assertEquals(coupon.getLabel(), "Group coupon");
        assertEquals(coupon.getAmountCents(), -2000L, "A coupon is a negative extra, never clamped");
        assertEquals(coupon, CostExtra.builder().label("Group coupon").amountCents(-2000).build());
        assertNotEquals(coupon, CostExtra.builder().label("Group coupon").amountCents(-2001).build());
        coupon.setBasis(null);
        assertEquals(coupon.getBasis(), CostExtra.Basis.FIXED, "The setter keeps the fold");
        coupon.setBasis(CostExtra.Basis.PER_STAY);
        assertEquals(coupon.getBasis(), CostExtra.Basis.PER_STAY);
    }

    @Test
    public void jacksonRoundTripPreservesEverything() throws Exception {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final CostExtra tax = CostExtra.builder().label("City tax").basis(CostExtra.Basis.PER_PERSON_NIGHT)
                .amountCents(250).build();
        final String json = mapper.writeValueAsString(tax);
        assertTrue(json.contains("\"basis\":\"PER_PERSON_NIGHT\""), json);
        assertTrue(json.contains("\"amountCents\":250"), json);
        assertEquals(mapper.readValue(json, CostExtra.class), tax);
        assertEquals(mapper.readValue("{\"label\":\"Fee\"}", CostExtra.class).getBasis(), CostExtra.Basis.FIXED);
    }

    private static CostExtra extra(final CostExtra.Basis basis) {
        return CostExtra.builder().label("x").basis(basis).amountCents(100).build();
    }
}
