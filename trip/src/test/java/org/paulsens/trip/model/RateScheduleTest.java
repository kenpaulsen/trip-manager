package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.paulsens.trip.dynamo.DAO;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertTrue;

public class RateScheduleTest {
    private static final LocalDate SEP24 = LocalDate.of(2026, 9, 24);
    private static final LocalDate SEP25 = LocalDate.of(2026, 9, 25);

    @Test
    public void builderWithNoValuesEqualsNewInstance() {
        final RateSchedule fresh = new RateSchedule();
        assertEquals(RateSchedule.builder().build(), fresh);
        assertEquals(fresh.getPricingModel(), PricingModel.PER_ROOM, "A null model reads as PER_ROOM");
        assertEquals(fresh.getNightlyCents(), 0L);
        assertEquals(fresh.getSingleSupplementCents(), 0L);
        assertTrue(fresh.getOverrides().isEmpty(), "Never null");
        assertFalse(fresh.isPerPerson());
        assertFalse(fresh.hasOverrides());
    }

    @Test
    public void moneyIsClampedAndTheOverridesAreCopied() {
        final Map<String, Long> overrides = new HashMap<>(Map.of("2026-09-25", 7000L));
        final RateSchedule schedule = RateSchedule.builder().nightlyCents(-5).singleSupplementCents(-1)
                .overrides(overrides).pricingModel(PricingModel.PER_PERSON).build();
        assertEquals(schedule.getNightlyCents(), 0L);
        assertEquals(schedule.getSingleSupplementCents(), 0L);
        assertTrue(schedule.isPerPerson());
        overrides.put("2026-09-26", 1L);
        assertEquals(schedule.getOverrides().size(), 1, "The map is copied, not aliased");
        assertNotSame(schedule.getOverrides(), overrides);
    }

    @Test
    public void nightlyCentsUsesTheOverrideForThatDateElseTheFlatRate() {
        final RateSchedule schedule = RateSchedule.builder().nightlyCents(5500)
                .overrides(Map.of("2026-09-25", 7000L, "2026-09-26", -3L)).build();
        assertEquals(schedule.nightlyCents(SEP24), 5500L);
        assertEquals(schedule.nightlyCents(SEP25), 7000L);
        assertEquals(schedule.nightlyCents(SEP25.plusDays(1)), 0L, "A negative override clamps to zero");
        assertEquals(schedule.nightlyCents(null), 5500L);
        assertTrue(schedule.hasOverrides());
    }

    @Test
    public void settersKeepTheInvariants() {
        final RateSchedule schedule = new RateSchedule();
        schedule.setPricingModel(null);
        assertEquals(schedule.getPricingModel(), PricingModel.PER_ROOM);
        schedule.setPricingModel(PricingModel.PER_PERSON);
        assertTrue(schedule.isPerPerson());
        schedule.setNightlyCents(-100);
        assertEquals(schedule.getNightlyCents(), 0L);
        schedule.setNightlyCents(6000);
        assertEquals(schedule.getNightlyCents(), 6000L);
        schedule.setSingleSupplementCents(-7);
        assertEquals(schedule.getSingleSupplementCents(), 0L);
        schedule.setSingleSupplementCents(2000);
        assertEquals(schedule.getSingleSupplementCents(), 2000L);
        final Map<String, Long> overrides = new HashMap<>(Map.of("2026-09-25", 7000L));
        schedule.setOverrides(overrides);
        overrides.clear();
        assertEquals(schedule.getOverrides(), Map.of("2026-09-25", 7000L), "Copied on set too");
        schedule.setOverrides(null);
        assertTrue(schedule.getOverrides().isEmpty(), "Null resets to empty, never to null");
    }

    @Test
    public void jacksonRoundTripPreservesEverythingAndPersistsNoDerivedFlags() throws Exception {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final RateSchedule schedule = RateSchedule.builder().pricingModel(PricingModel.PER_PERSON).nightlyCents(5500)
                .overrides(Map.of("2026-09-25", 7000L)).singleSupplementCents(2000).build();
        final String json = mapper.writeValueAsString(schedule);
        assertTrue(json.contains("\"2026-09-25\":7000"), json);
        assertFalse(json.contains("perPerson"), "Derived flags are not persisted: " + json);
        assertFalse(json.contains("hasOverrides"), json);
        assertEquals(mapper.readValue(json, RateSchedule.class), schedule);
        assertEquals(mapper.readValue("{}", RateSchedule.class), new RateSchedule(), "An empty object is the default");
    }
}
