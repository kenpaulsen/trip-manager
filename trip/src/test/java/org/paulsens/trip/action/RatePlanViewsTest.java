package org.paulsens.trip.action;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;
import org.paulsens.trip.action.LodgingViews.CostExtraForm;
import org.paulsens.trip.action.RatePlanViews.PlanChoice;
import org.paulsens.trip.action.RatePlanViews.RatePlanForm;
import org.paulsens.trip.action.RatePlanViews.RatePlanRow;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * The rate plan page shapes: they park in viewScope, so they round-trip through Java serialization with their
 * defaults intact, and the menu value a {@link PlanChoice} carries is what the bean takes back.
 */
public class RatePlanViewsTest {

    @Test
    public void aNewPlanFormNeedsPricingAndStatesACostByDefault() throws Exception {
        final RatePlanForm form = new RatePlanForm();
        assertFalse(form.isPriceKnown(), "a new plan needs participant pricing until an admin sets it");
        assertTrue(form.isCostKnown(), "stating the cost is what the dialog is for");
        assertEquals(form.getCostPricingModel(), "PER_ROOM");
        assertEquals(form.getPricingModel(), "PER_ROOM");
        assertTrue(form.getRoomTypeIds().isEmpty());
        assertTrue(form.getCostExtras().isEmpty());
        assertNull(form.getPlanId(), "null = new");
        assertFalse(form.isRetired());

        form.setAccId("acc-1");
        form.setName("Autumn");
        form.setRoomTypeIds(List.of("rt-d", "rt-t"));
        form.setValidFrom(LocalDate.of(2027, 9, 1));
        form.setValidUntil(LocalDate.of(2027, 10, 31));
        form.setCostNightlyPrice(50.0);
        form.setCostExtras(List.of(new CostExtraForm("City tax", "FIXED", 12.0)));
        form.setPriceKnown(true);
        form.setNightlyPrice(70.0);
        form.setProblem("nope");
        final RatePlanForm back = roundTrip(form);
        assertEquals(back.getAccId(), "acc-1");
        assertEquals(back.getRoomTypeIds(), List.of("rt-d", "rt-t"));
        assertEquals(back.getValidUntil(), LocalDate.of(2027, 10, 31));
        assertEquals(back.getCostExtras().get(0).getLabel(), "City tax");
        assertEquals(back.getNightlyPrice(), 70.0);
        assertEquals(back.getProblem(), "nope");
        form.setRoomTypeIds(null);
        form.setCostExtras(null);
        assertTrue(form.getRoomTypeIds().isEmpty(), "null collapses to empty, never NPEs the page");
        assertTrue(form.getCostExtras().isEmpty());
    }

    @Test
    public void aRowCarriesANullPriceColumnForReadersWhoMayNotSeeIt() throws Exception {
        final RatePlanRow row = new RatePlanRow("p-1", "Autumn", "Double, Triple", "Sep 1 - Oct 31, 2027",
                "$50.00/night per room, +1 extra", null, true, "Spring trip, 1 other trip", 2, false, "notes");
        final RatePlanRow back = roundTrip(row);
        assertNull(back.getPriceSummary(), "an operator's row");
        assertTrue(back.isNeedsPricing());
        assertEquals(back.getLinkedCount(), 2);
        assertEquals(back.getLinkedTrips(), "Spring trip, 1 other trip");
        assertEquals(back.getDates(), "Sep 1 - Oct 31, 2027");
        assertFalse(back.isRetired());
        assertEquals(new RatePlanRow().getLinkedCount(), 0);
    }

    @Test
    public void aPlanChoiceCarriesHotelAndPlanInOneMenuValue() throws Exception {
        final PlanChoice choice = new PlanChoice("plan-1", "acc-1", "Pansion: Autumn (Sep 1 - Oct 31, 2027)");
        assertEquals(choice.getValue(), "acc-1|plan-1");
        assertEquals(roundTrip(choice).getValue(), "acc-1|plan-1");
        assertEquals(roundTrip(choice).getLabel(), "Pansion: Autumn (Sep 1 - Oct 31, 2027)");
        assertEquals(PlanChoice.SEPARATOR, "|", "a UUID never contains it, so the split is unambiguous");
    }

    @SuppressWarnings("unchecked")
    private static <T extends Serializable> T roundTrip(final T value) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (T) in.readObject();
        }
    }
}
