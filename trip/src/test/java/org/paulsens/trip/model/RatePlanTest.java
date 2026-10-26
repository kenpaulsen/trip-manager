package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.paulsens.trip.dynamo.DAO;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class RatePlanTest {
    private static final LocalDate SEP1 = LocalDate.of(2026, 9, 1);
    private static final LocalDate SEP30 = LocalDate.of(2026, 9, 30);
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 25, 9, 0);
    private static final Person.Id ADA = Person.Id.from("person-ada");

    @Test
    public void builderWithNoValuesEqualsNewInstance() {
        final RatePlan built = RatePlan.builder().build();
        final RatePlan fresh = new RatePlan();
        built.setId(fresh.getId());
        assertEquals(built, fresh);
        assertNotNull(fresh.getId(), "A plan mints its own id, like every other lodging row");
        assertTrue(fresh.getRoomTypeIds().isEmpty(), "Never null");
        assertTrue(fresh.getCostExtras().isEmpty(), "Never null");
        assertTrue(fresh.getLinkedTripIds().isEmpty(), "Never null");
        assertNull(fresh.getCost());
        assertNull(fresh.getPrice());
        assertFalse(fresh.isPriced(), "No price yet: offers taken from it need participant pricing");
        assertFalse(fresh.isRetired(), "A builder that never mentions it produces a live plan");
        assertEquals(fresh.getVersion(), 0L);
    }

    @Test
    public void linkAndUnlinkReportChangeAndNeverDuplicate() {
        final RatePlan plan = RatePlan.builder().linkedTripIds(List.of("trip-a", "trip-a", "", "trip-b")).build();
        assertEquals(plan.getLinkedTripIds(), List.of("trip-a", "trip-b"), "Deduped and blanks dropped on read");
        assertFalse(plan.link("trip-a"), "Already linked");
        assertTrue(plan.link("trip-c"));
        assertFalse(plan.link("trip-c"));
        assertFalse(plan.link(null));
        assertFalse(plan.link("  "));
        assertEquals(plan.getLinkedTripIds(), List.of("trip-a", "trip-b", "trip-c"));
        assertTrue(plan.unlink("trip-a"));
        assertFalse(plan.unlink("trip-a"), "Gone already");
        assertFalse(plan.unlink(null));
        assertEquals(plan.getLinkedTripIds(), List.of("trip-b", "trip-c"));
    }

    @Test
    public void coversIsInclusiveOnBothEndsAndANullBoundIsOpen() {
        final RatePlan plan = RatePlan.builder().validFrom(SEP1).validUntil(SEP30).build();
        assertTrue(plan.covers(SEP1, SEP30));
        assertTrue(plan.covers(SEP1.plusDays(5), SEP1.plusDays(9)));
        assertFalse(plan.covers(SEP1.minusDays(1), SEP1.plusDays(9)), "Starts before the plan");
        assertFalse(plan.covers(SEP1, SEP30.plusDays(1)), "Ends after the plan");
        assertFalse(plan.covers(null, SEP30), "A stay without dates never fits");
        assertFalse(plan.covers(SEP1, null));
        assertTrue(RatePlan.builder().validFrom(SEP1).build().covers(SEP1, SEP30.plusYears(5)), "Open end");
        assertTrue(RatePlan.builder().validUntil(SEP30).build().covers(SEP1.minusYears(5), SEP30), "Open start");
        assertTrue(RatePlan.builder().build().covers(SEP1, SEP30), "Both open");
    }

    @Test
    public void aPlanNormalizesWhatItIsGiven() {
        final List<String> types = new ArrayList<>(List.of("rt-d"));
        final List<CostExtra> extras = new ArrayList<>(List.of(CostExtra.builder().label("Tax").build()));
        final RatePlan plan = RatePlan.builder().name("  Autumn rate  ").roomTypeIds(types).costExtras(extras)
                .retired(true).price(RateSchedule.builder().nightlyCents(6000).build()).build();
        types.add("rt-s");
        extras.clear();
        assertEquals(plan.getName(), "Autumn rate");
        assertEquals(plan.getRoomTypeIds(), List.of("rt-d"), "Copied, not aliased");
        assertEquals(plan.getCostExtras().size(), 1, "Copied, not aliased");
        assertTrue(plan.isRetired());
        assertTrue(plan.isPriced());
        assertEquals(RatePlan.Id.from("p-1").compareTo(RatePlan.Id.from("p-2")), -1);
        assertEquals(RatePlan.Id.from("p-1"), RatePlan.Id.from("p-1"));
        assertNotNull(RatePlan.Id.newInstance().getValue());
    }

    @Test
    public void jacksonRoundTripPreservesEverythingAndPersistsNoDerivedFlags() throws Exception {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final RatePlan plan = RatePlan.builder().id(RatePlan.Id.from("plan-1"))
                .accommodationId(Accommodation.Id.from("acc-1")).name("Autumn rate").roomTypeIds(List.of("rt-d"))
                .validFrom(SEP1).validUntil(SEP30)
                .cost(RateSchedule.builder().nightlyCents(5000).build())
                .price(RateSchedule.builder().pricingModel(PricingModel.PER_PERSON).nightlyCents(3500)
                        .singleSupplementCents(1500).build())
                .costExtras(List.of(CostExtra.builder().label("City tax").basis(CostExtra.Basis.PER_PERSON_NIGHT)
                        .amountCents(250).build()))
                .notes("Breakfast included").linkedTripIds(List.of("trip-a")).retired(false).createdBy(ADA)
                .created(AT).version(3L).build();
        final String json = mapper.writeValueAsString(plan);
        assertTrue(json.contains("\"cost\":{"), json);
        assertTrue(json.contains("\"price\":{"), json);
        assertTrue(json.contains("\"linkedTripIds\":[\"trip-a\"]"), json);
        assertTrue(json.contains("\"retired\":false"), "The stored Boolean persists, not the derived flag: " + json);
        assertFalse(json.contains("\"priced\""), "Derived flags are not persisted: " + json);
        assertEquals(mapper.readValue(json, RatePlan.class), plan);
        final RatePlan bare = mapper.readValue("{\"id\":\"plan-2\",\"name\":\"Bare\"}", RatePlan.class);
        assertFalse(bare.isPriced());
        assertFalse(bare.isRetired());
        assertTrue(bare.getLinkedTripIds().isEmpty());
    }
}
