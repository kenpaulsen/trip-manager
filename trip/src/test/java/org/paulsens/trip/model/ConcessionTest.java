package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import org.paulsens.trip.dynamo.DAO;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class ConcessionTest {
    private static final Person.Id ADA = Person.Id.from("person-ada");
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 25, 9, 0);

    @Test
    public void builderWithNoValuesEqualsNewInstance() {
        final Concession fresh = new Concession();
        assertEquals(Concession.builder().build(), fresh);
        assertEquals(fresh.getKind(), Concession.Kind.TOTAL, "A null kind reads as a (zero) total discount");
        assertEquals(fresh.getPercentBps(), 0);
        assertEquals(fresh.getAmountCents(), 0L);
        assertNull(fresh.getReason());
        assertNull(fresh.getBy());
        assertNull(fresh.getAt());
        assertFalse(fresh.isComplimentary());
        assertEquals(fresh.netCents(12345, 3), 12345L, "A zero total takes nothing off");
    }

    @Test
    public void percentRoundsHalfUpAndIsClampedToTheGross() {
        final Concession tenPercent = percent(1000);
        assertEquals(tenPercent.netCents(10000, 4), 9000L);
        assertEquals(tenPercent.netCents(10001, 4), 9001L, "9000.9 rounds up");
        assertEquals(percent(5000).netCents(5, 1), 3L, "2.5 rounds half-up to 3");
        assertEquals(percent(5000).netCents(3333, 1), 1667L, "1666.5 rounds half-up");
        assertEquals(percent(0).netCents(10000, 1), 10000L);
        assertEquals(percent(10000).netCents(10000, 1), 0L, "100% off is free");
        assertEquals(Concession.builder().kind(Concession.Kind.PERCENT).percentBps(25000).build().getPercentBps(),
                10000, "Basis points clamp to 100%");
        assertEquals(Concession.builder().kind(Concession.Kind.PERCENT).percentBps(-5).build().getPercentBps(), 0);
        assertEquals(tenPercent.netCents(0, 4), 0L);
        assertEquals(tenPercent.netCents(-50, 4), 0L, "A negative gross nets to zero, never below");
    }

    @Test
    public void perNightAndTotalSubtractAndFloorAtZero() {
        final Concession perNight = Concession.builder().kind(Concession.Kind.PER_NIGHT).amountCents(500).build();
        assertEquals(perNight.netCents(22000, 4), 20000L, "$5.00 x 4 nights");
        assertEquals(perNight.netCents(22000, 0), 22000L, "No nights, nothing off");
        assertEquals(perNight.netCents(22000, -2), 22000L, "Negative nights count as none");
        assertEquals(perNight.netCents(1500, 4), 0L, "Floor at zero");
        final Concession total = Concession.builder().kind(Concession.Kind.TOTAL).amountCents(2000).build();
        assertEquals(total.netCents(22000, 4), 20000L);
        assertEquals(total.netCents(22000, 0), 20000L, "Nights are irrelevant to a total");
        assertEquals(total.netCents(1999, 1), 0L);
        assertEquals(Concession.builder().kind(Concession.Kind.TOTAL).amountCents(-100).build().getAmountCents(), 0L,
                "A negative amount clamps to zero");
    }

    @Test
    public void complimentaryIsFreeWhateverTheGross() {
        final Concession comp = Concession.builder().kind(Concession.Kind.COMPLIMENTARY).amountCents(1).percentBps(1)
                .build();
        assertTrue(comp.isComplimentary());
        assertEquals(comp.netCents(99999, 7), 0L);
        assertEquals(comp.netCents(0, 0), 0L);
        assertEquals(comp.label(), "complimentary");
    }

    @Test
    public void labelsNameTheDiscountAndNeverTheReason() {
        assertEquals(percent(1000).label(), "10% discount");
        assertEquals(percent(1250).label(), "12.5% discount");
        assertEquals(percent(1234).label(), "12.34% discount");
        assertEquals(percent(10000).label(), "100% discount");
        assertEquals(percent(5).label(), "0.05% discount");
        assertEquals(Concession.builder().kind(Concession.Kind.PER_NIGHT).amountCents(500).build().label(),
                "$5.00/night discount");
        assertEquals(Concession.builder().kind(Concession.Kind.TOTAL).amountCents(2000).build().label(),
                "$20.00 discount");
        assertEquals(Concession.builder().kind(Concession.Kind.TOTAL).amountCents(123456).build().label(),
                "$1,234.56 discount");
        assertEquals(Concession.builder().kind(Concession.Kind.COMPLIMENTARY).reason("hotel comped the guide")
                .build().label(), "complimentary");
        final Concession reasoned = Concession.builder().kind(Concession.Kind.PERCENT).percentBps(1000)
                .reason("Loyal pilgrim").by(ADA).at(AT).build();
        assertFalse(reasoned.label().contains("Loyal"), "The reason would churn every bill note: " + reasoned.label());
        assertEquals(reasoned.getReason(), "Loyal pilgrim");
        assertEquals(Concession.builder().reason("  trimmed  ").build().getReason(), "trimmed");
    }

    @Test
    public void settersKeepTheInvariants() {
        final Concession concession = new Concession();
        concession.setKind(null);
        assertEquals(concession.getKind(), Concession.Kind.TOTAL);
        concession.setKind(Concession.Kind.PERCENT);
        concession.setPercentBps(20000);
        assertEquals(concession.getPercentBps(), 10000);
        concession.setPercentBps(-1);
        assertEquals(concession.getPercentBps(), 0);
        concession.setAmountCents(-5);
        assertEquals(concession.getAmountCents(), 0L);
        concession.setAmountCents(5);
        assertEquals(concession.getAmountCents(), 5L);
    }

    @Test
    public void jacksonRoundTripPreservesEverything() throws Exception {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final Concession concession = Concession.builder().kind(Concession.Kind.PER_NIGHT).amountCents(500)
                .reason("Late room change").by(ADA).at(AT).build();
        final String json = mapper.writeValueAsString(concession);
        assertTrue(json.contains("\"kind\":\"PER_NIGHT\""), json);
        assertTrue(json.contains("\"by\":\"person-ada\""), json);
        assertFalse(json.contains("complimentary"), "Derived flags are not persisted: " + json);
        assertEquals(mapper.readValue(json, Concession.class), concession);
        assertEquals(mapper.readValue("{}", Concession.class), new Concession());
    }

    private static Concession percent(final int bps) {
        return Concession.builder().kind(Concession.Kind.PERCENT).percentBps(bps).build();
    }
}
