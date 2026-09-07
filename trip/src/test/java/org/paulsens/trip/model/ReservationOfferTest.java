package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import org.paulsens.trip.dynamo.DAO;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
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
        assertTrue(offer.isOpenAt(AT));
        assertTrue(offer.isOpenAt(AT.plusDays(10)));
        assertFalse(offer.isOpenAt(AT.minusMinutes(1)));
        assertFalse(offer.isOpenAt(AT.plusDays(10).plusMinutes(1)));
        assertFalse(offer.isOpenAt(null));
        assertTrue(ReservationOffer.builder().build().isOpenAt(AT), "Unset bounds are open");
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
}
