package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.dynamo.DAO;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

public class ReservationOfferTest {
    private static final LocalDateTime AT = LocalDateTime.of(2026, 9, 20, 15, 0);

    @Test
    public void builderWithNoValuesEqualsNewInstance() {
        final ReservationOffer built = ReservationOffer.builder().build();
        final ReservationOffer fresh = new ReservationOffer();
        built.setId(fresh.getId());
        assertEquals(built, fresh);
        assertEquals(fresh.getType(), OfferType.LODGING);
        assertEquals(fresh.getPricingModel(), PricingModel.PER_ROOM);
        assertEquals(fresh.getMinNights(), 1, "Minimum stay defaults to one night");
        assertTrue(fresh.isEnabled(), "A builder that never mentions it produces a live offer");
        assertFalse(fresh.isPerPerson());
        assertFalse(fresh.hasCancellationFee());
        assertTrue(fresh.getNightlyPriceOverrides().isEmpty());
        assertNotNull(fresh.getId());
    }

    @Test
    public void negativeMoneyAndMinNightsAreClamped() {
        final ReservationOffer offer = ReservationOffer.builder().nightlyPriceCents(-5).singleSupplementCents(-1)
                .minNights(0).name(" Double ").build();
        assertEquals(offer.getNightlyPriceCents(), 0L);
        assertEquals(offer.getSingleSupplementCents(), 0L);
        assertEquals(offer.getMinNights(), 1);
        assertEquals(offer.getName(), "Double");
    }

    @Test
    public void nightlyPriceUsesTheOverrideForThatDateElseTheFlatRate() {
        final ReservationOffer offer = ReservationOffer.builder().nightlyPriceCents(5500)
                .nightlyPriceOverrides(Map.of("2026-09-25", 7000L, "2026-09-26", -3L)).build();
        assertEquals(offer.nightlyPriceCents(LocalDate.of(2026, 9, 24)), 5500L);
        assertEquals(offer.nightlyPriceCents(LocalDate.of(2026, 9, 25)), 7000L);
        assertEquals(offer.nightlyPriceCents(LocalDate.of(2026, 9, 26)), 0L, "A negative override clamps to zero");
        assertEquals(offer.nightlyPriceCents(null), 5500L);
    }

    @Test
    public void enabledFlagAndCancellationFeeAndValidity() {
        final ReservationOffer offer = ReservationOffer.builder().disabled(true).cancelFeeBps(1000)
                .validFrom(AT).validUntil(AT.plusDays(10)).pricingModel(PricingModel.PER_PERSON).build();
        assertFalse(offer.isEnabled());
        assertTrue(offer.isPerPerson());
        assertTrue(offer.hasCancellationFee());
        assertTrue(ReservationOffer.builder().cancelFeeFixedCents(100L).build().hasCancellationFee());
        assertFalse(ReservationOffer.builder().cancelFeeFixedCents(0L).cancelFeeBps(0).build().hasCancellationFee());
        assertTrue(offer.coversStay(AT, AT.plusDays(10)));
        assertTrue(offer.coversStay(AT.plusDays(2), AT.plusDays(5)));
        assertFalse(offer.coversStay(AT.minusMinutes(1), AT.plusDays(5)));
        assertFalse(offer.coversStay(AT, AT.plusDays(10).plusMinutes(1)));
        assertFalse(offer.coversStay(null, AT));
        assertTrue(ReservationOffer.builder().build().coversStay(AT, AT.plusDays(400)), "Unset bounds are open");
        assertTrue(ReservationOffer.builder().disabled(false).build().isEnabled());
    }

    @Test
    public void jacksonRoundTripPreservesEverything() throws Exception {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final ReservationOffer offer = ReservationOffer.builder().id(ReservationOffer.Id.from("offer-1"))
                .tripId("trip-1").orgId("org-1").name("Double room").accommodationId(Accommodation.Id.from("acc-1"))
                .roomTypeId("rt-d").tripEventId("ev-1").pricingModel(PricingModel.PER_PERSON).nightlyPriceCents(5500)
                .nightlyPriceOverrides(Map.of("2026-09-25", 7000L)).singleSupplementCents(2000).minNights(2)
                .validFrom(AT).validUntil(AT.plusDays(3)).defaultStart(AT).defaultEnd(AT.plusDays(11))
                .policyHtml("<p>No refunds</p>").cancelFeeFixedCents(1000L).cancelFeeBps(500).disabled(true)
                .createdBy(Person.Id.from("person-ken")).created(AT).version(2L).build();
        final String json = mapper.writeValueAsString(offer);
        assertTrue(json.contains("\"2026-09-25\":7000"), "Overrides are keyed by ISO date, greppable");
        assertFalse(json.contains("\"enabled\""), "Only the stored inverted flag is persisted");
        assertEquals(mapper.readValue(json, ReservationOffer.class), offer);
        assertTrue(ReservationOffer.Id.from("a").compareTo(ReservationOffer.Id.from("b")) < 0);
        assertNotNull(ReservationOffer.Id.newInstance().getValue());
    }

    // ------------------------------------------------------------------ price vs cost (the split)

    @Test
    public void aLegacyRowReadsAsPricedAtItsOldNumbersZeroIncluded() throws Exception {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final ReservationOffer free = mapper.readValue("{\"id\":\"offer-1\",\"name\":\"Comped\","
                + "\"pricingModel\":\"PER_ROOM\",\"nightlyPriceCents\":0,\"nightlyPriceOverrides\":{},"
                + "\"singleSupplementCents\":0,\"minNights\":1,\"version\":3}", ReservationOffer.class);
        assertTrue(free.isPriced(), "Every pre-split row priced; a $0 one still does");
        assertEquals(free.getPrice(), new RateSchedule());
        assertEquals(free.getNightlyPriceCents(), 0L);
        assertTrue(free.isCostAssumed(), "No cost stated: the invoice assumes the price");
        assertSame(free.effectiveCost(), free.getPrice());

        final ReservationOffer paid = mapper.readValue("{\"id\":\"offer-2\",\"name\":\"Double\","
                + "\"pricingModel\":\"PER_PERSON\",\"nightlyPriceCents\":5500,"
                + "\"nightlyPriceOverrides\":{\"2026-09-25\":7000},\"singleSupplementCents\":2000}",
                ReservationOffer.class);
        assertEquals(paid.getPrice(), RateSchedule.builder().pricingModel(PricingModel.PER_PERSON).nightlyCents(5500)
                .overrides(Map.of("2026-09-25", 7000L)).singleSupplementCents(2000).build());
        assertTrue(paid.isPerPerson());
        assertEquals(paid.nightlyPriceCents(LocalDate.of(2026, 9, 25)), 7000L);
        assertEquals(paid.getSingleSupplementCents(), 2000L);
        assertNull(paid.getCost());
        assertTrue(paid.getCostExtras().isEmpty(), "Never null, even on a row that never heard of extras");
        assertFalse(paid.isLinked());

        final ReservationOffer bare = mapper.readValue("{\"id\":\"offer-3\",\"nightlyPriceCents\":6000}",
                ReservationOffer.class);
        assertTrue(bare.isPriced(), "nightlyPriceCents alone is a legacy row");
        assertEquals(bare.getPricingModel(), PricingModel.PER_ROOM);
        assertEquals(bare.getNightlyPriceCents(), 6000L);
    }

    @Test
    public void anExplicitlyUnpricedRowStaysUnpricedAndMirrorsZero() throws Exception {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final ReservationOffer offer = mapper.readValue("{\"id\":\"offer-1\",\"name\":\"Needs pricing\","
                + "\"priced\":false,\"cost\":{\"nightlyCents\":5000}}", ReservationOffer.class);
        assertFalse(offer.isPriced());
        assertNull(offer.getPrice());
        assertEquals(offer.getPricingModel(), PricingModel.PER_ROOM, "The mirror answers a safe default");
        assertEquals(offer.getNightlyPriceCents(), 0L);
        assertEquals(offer.getSingleSupplementCents(), 0L);
        assertTrue(offer.getNightlyPriceOverrides().isEmpty());
        assertEquals(offer.nightlyPriceCents(LocalDate.of(2026, 9, 25)), 0L);
        assertFalse(offer.isPerPerson());
        assertEquals(offer.getCost().getNightlyCents(), 5000L, "The cost side is independent of the price side");
        assertSame(offer.effectiveCost(), offer.getCost());
        assertFalse(offer.isCostAssumed(), "A stated cost is never assumed");
        // "priced": false next to legacy keys is still unpriced: the flag outranks the legacy mirror.
        final ReservationOffer mixed = mapper.readValue("{\"id\":\"offer-2\",\"priced\":false,"
                + "\"nightlyPriceCents\":6000,\"pricingModel\":\"PER_ROOM\"}", ReservationOffer.class);
        assertFalse(mixed.isPriced());
    }

    @Test
    public void aPriceObjectOutranksTheLegacyMirror() throws Exception {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final ReservationOffer offer = mapper.readValue("{\"id\":\"offer-1\",\"priced\":true,"
                + "\"price\":{\"pricingModel\":\"PER_PERSON\",\"nightlyCents\":8000,\"singleSupplementCents\":1500},"
                + "\"pricingModel\":\"PER_ROOM\",\"nightlyPriceCents\":5500,\"singleSupplementCents\":0,"
                + "\"nightlyPriceOverrides\":{\"2026-09-25\":7000}}", ReservationOffer.class);
        assertTrue(offer.isPriced());
        assertEquals(offer.getNightlyPriceCents(), 8000L, "The object wins over a stale mirror");
        assertTrue(offer.isPerPerson());
        assertEquals(offer.getSingleSupplementCents(), 1500L);
        assertTrue(offer.getNightlyPriceOverrides().isEmpty(), "The mirror's overrides are not merged in");
    }

    @Test
    public void aFreshOfferIsUnpricedUntilSomethingSaysAPrice() {
        assertFalse(ReservationOffer.builder().build().isPriced(), "Nothing said: needs participant pricing");
        assertFalse(new ReservationOffer().isPriced());
        assertNull(ReservationOffer.builder().build().effectiveCost(), "Neither side: nothing to invoice");
        assertFalse(ReservationOffer.builder().build().isCostAssumed(), "Nothing to assume FROM");
        assertTrue(ReservationOffer.builder().nightlyPriceCents(60_00L).build().isPriced(),
                "A builder that names a legacy price gets a priced offer (FakeData, the tests)");
        assertTrue(ReservationOffer.builder().nightlyPriceCents(0).build().isPriced(), "...even $0");
        final ReservationOffer perPersonOnly = ReservationOffer.builder().pricingModel(PricingModel.PER_PERSON).build();
        assertTrue(perPersonOnly.isPriced(), "Any legacy key counts");
        assertTrue(perPersonOnly.isPerPerson());
        assertEquals(perPersonOnly.getNightlyPriceCents(), 0L);
        assertTrue(ReservationOffer.builder().singleSupplementCents(100).build().isPriced());
        assertTrue(ReservationOffer.builder().nightlyPriceOverrides(Map.of()).build().isPriced());
        final RateSchedule price = RateSchedule.builder().nightlyCents(7000).build();
        assertSame(ReservationOffer.builder().price(price).build().getPrice(), price);
        assertFalse(ReservationOffer.builder().priced(Boolean.TRUE).build().isPriced(),
                "The flag alone carries no numbers: TRUE without a price object or legacy keys is still unpriced");
    }

    @Test
    public void legacySettersWriteThePriceSideCreatingItOnFirstWrite() {
        final ReservationOffer offer = new ReservationOffer();
        offer.setNightlyPriceCents(6000);
        assertTrue(offer.isPriced(), "A legacy write means priced");
        assertEquals(offer.getPrice().getNightlyCents(), 6000L);
        offer.setPricingModel(PricingModel.PER_PERSON);
        offer.setSingleSupplementCents(2000);
        offer.setNightlyPriceOverrides(new HashMap<>(Map.of("2026-09-25", 7000L)));
        assertEquals(offer.getPrice(), RateSchedule.builder().pricingModel(PricingModel.PER_PERSON).nightlyCents(6000)
                .overrides(Map.of("2026-09-25", 7000L)).singleSupplementCents(2000).build());
        offer.getNightlyPriceOverrides().put("2026-09-26", 1L);
        assertEquals(offer.getPrice().getOverrides().size(), 2, "The mirror getter hands out the live map");
        offer.setNightlyPriceCents(-1);
        assertEquals(offer.getNightlyPriceCents(), 0L, "Clamped through the schedule");
        final ReservationOffer fresh = new ReservationOffer();
        fresh.setPricingModel(null);
        assertTrue(fresh.isPriced());
        assertEquals(fresh.getPricingModel(), PricingModel.PER_ROOM);
    }

    @Test
    public void costSideExtrasAndRatePlanLink() {
        final List<CostExtra> extras = new ArrayList<>(List.of(CostExtra.builder().label("City tax")
                .basis(CostExtra.Basis.PER_PERSON_NIGHT).amountCents(250).build()));
        final RateSchedule cost = RateSchedule.builder().nightlyCents(5000).build();
        final ReservationOffer offer = ReservationOffer.builder().nightlyPriceCents(6000).cost(cost).costExtras(extras)
                .ratePlanId("plan-1").ratePlanVersion(4L).build();
        extras.clear();
        assertEquals(offer.getCostExtras().size(), 1, "Copied, not aliased");
        assertSame(offer.effectiveCost(), cost);
        assertFalse(offer.isCostAssumed());
        assertTrue(offer.isLinked());
        assertEquals(offer.getRatePlanVersion(), 4L);
        assertFalse(ReservationOffer.builder().ratePlanId("  ").build().isLinked(), "Blank folds to null");
        assertNull(ReservationOffer.builder().ratePlanId("").build().getRatePlanId());
        assertEquals(ReservationOffer.builder().build().getRatePlanVersion(), 0L);
        final ReservationOffer costOnly = ReservationOffer.builder().cost(cost).build();
        assertFalse(costOnly.isPriced());
        assertSame(costOnly.effectiveCost(), cost);
        assertFalse(costOnly.isCostAssumed());
    }

    @Test
    public void aNewRowCarriesThePriceObjectTheFlagAndTheLegacyMirror() throws Exception {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final ReservationOffer priced = ReservationOffer.builder().id(ReservationOffer.Id.from("offer-1"))
                .name("Double").pricingModel(PricingModel.PER_PERSON).nightlyPriceCents(5500)
                .nightlyPriceOverrides(Map.of("2026-09-25", 7000L)).singleSupplementCents(2000)
                .cost(RateSchedule.builder().nightlyCents(4000).build())
                .costExtras(List.of(CostExtra.builder().label("City tax").amountCents(250).build()))
                .ratePlanId("plan-1").ratePlanVersion(2L).build();
        final String json = mapper.writeValueAsString(priced);
        assertTrue(json.contains("\"price\":{"), "The new shape: " + json);
        assertTrue(json.contains("\"priced\":true"), json);
        assertTrue(json.contains("\"cost\":{"), json);
        assertTrue(json.contains("\"costExtras\":[{"), json);
        assertTrue(json.contains("\"ratePlanId\":\"plan-1\""), json);
        assertTrue(json.contains("\"ratePlanVersion\":2"), json);
        // The mirror: an OLD instance in a rolling deploy reads these and prices the row exactly as before.
        assertTrue(json.contains("\"pricingModel\":\"PER_PERSON\""), json);
        assertTrue(json.contains("\"nightlyPriceCents\":5500"), json);
        assertTrue(json.contains("\"nightlyPriceOverrides\":{\"2026-09-25\":7000}"), json);
        assertTrue(json.contains("\"singleSupplementCents\":2000"), json);
        assertFalse(json.contains("\"costAssumed\"") || json.contains("\"linked\"") || json.contains("\"perPerson\""),
                "Derived flags are not persisted: " + json);
        assertEquals(mapper.readValue(json, ReservationOffer.class), priced);

        final String unpriced = mapper.writeValueAsString(ReservationOffer.builder().name("Needs pricing").build());
        assertTrue(unpriced.contains("\"priced\":false"), unpriced);
        assertFalse(unpriced.contains("\"price\":"), "No object when unpriced: " + unpriced);
        assertTrue(unpriced.contains("\"nightlyPriceCents\":0"), "The mirror still answers zero: " + unpriced);
        assertTrue(unpriced.contains("\"pricingModel\":\"PER_ROOM\""), unpriced);
        assertTrue(unpriced.contains("\"costExtras\":[]"), unpriced);
        assertFalse(mapper.readValue(unpriced, ReservationOffer.class).isPriced(), "...and reads back unpriced");
    }

    @Test
    public void javaSerializationRoundTripsBothSides() throws Exception {
        final ReservationOffer offer = ReservationOffer.builder().name("Double").nightlyPriceCents(5500)
                .cost(RateSchedule.builder().nightlyCents(4000).build())
                .costExtras(List.of(CostExtra.builder().label("City tax").amountCents(250).build()))
                .ratePlanId("plan-1").build();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(offer);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            final ReservationOffer revived = (ReservationOffer) in.readObject();
            assertEquals(revived, offer);
            assertTrue(revived.isPriced());
            assertEquals(revived.getCostExtras().size(), 1);
        }
    }
}
