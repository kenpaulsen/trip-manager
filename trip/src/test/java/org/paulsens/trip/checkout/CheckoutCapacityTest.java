package org.paulsens.trip.checkout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PriceBreakdown;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Trip;
import org.testng.annotations.Test;

import static org.paulsens.trip.checkout.GoldenTrip.ADULT;
import static org.paulsens.trip.checkout.GoldenTrip.BEFORE;
import static org.paulsens.trip.checkout.GoldenTrip.answers;
import static org.paulsens.trip.checkout.GoldenTrip.trip;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * {@link CheckoutCapacity}: the trip's limit and each choice's capacity, counted from CONFIRMED and PENDING rows,
 * and the discount-code use count.
 */
public class CheckoutCapacityTest {
    private static int seq;

    private static Registration row(final Registration.Status status, final Map<String, String> answers) {
        seq++;
        return new Registration("trip-golden", Person.Id.from("person-" + seq), BEFORE, status, answers);
    }

    /** {@code count} rows of {@code status}, each answering {@code admission}. */
    private static List<Registration> rows(final int count, final Registration.Status status, final String admission) {
        final List<Registration> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(row(status, answers(admission, null)));
        }
        return rows;
    }

    private static List<Map<String, String>> party(final int size, final String admission) {
        final List<Map<String, String>> party = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            party.add(answers(admission, null));
        }
        return party;
    }

    private static Trip limited(final Integer limit) {
        final Trip trip = trip();
        trip.setRegLimit(limit);
        return trip;
    }

    @Test
    public void anUnlimitedTripAlwaysFits() {
        assertTrue(CheckoutCapacity.problems(limited(null), rows(500, Registration.Status.CONFIRMED, "full"),
                party(3, "full")).isEmpty());
    }

    @Test
    public void onlyConfirmedAndPendingRowsHoldAPlace() {
        final List<Registration> existing = new ArrayList<>(rows(5, Registration.Status.CONFIRMED, "full"));
        existing.addAll(rows(3, Registration.Status.PENDING, "full"));
        existing.addAll(rows(4, Registration.Status.NOT_REGISTERED, "full"));
        assertTrue(CheckoutCapacity.problems(limited(10), existing, party(2, "full")).isEmpty(), "8 + 2 fits 10");
        assertEquals(CheckoutCapacity.problems(limited(10), existing, party(3, "full")), List.of(
                new CheckoutProblem(CheckoutProblem.CAPACITY_FULL, null, "trip-golden",
                        "Spring Conference has only 2 places left, but your party has 3 people.")));
        assertEquals(CheckoutCapacity.problems(limited(9), existing, party(3, "full")).get(0).message(),
                "Spring Conference has only 1 place left, but your party has 3 people.");
    }

    @Test
    public void aFullTripSaysSo() {
        final Trip trip = limited(2);
        assertEquals(CheckoutCapacity.problems(trip, rows(3, Registration.Status.CONFIRMED, "full"),
                party(1, "full")).get(0).message(), "Spring Conference is full.", "an over-filled trip is full too");
        trip.setTitle(null);
        assertEquals(CheckoutCapacity.problems(trip, rows(2, Registration.Status.PENDING, "full"),
                party(1, "full")).get(0).message(), "This trip is full.");
    }

    @Test
    public void anEmptyPartyHasNoProblems() {
        final List<Registration> crowd = rows(5, Registration.Status.CONFIRMED, "fri");
        final Trip trip = limited(1);
        GoldenTrip.question(trip, GoldenTrip.ADMISSION).choice("fri").setCapacity(1);
        assertTrue(CheckoutCapacity.problems(trip, crowd, List.of()).isEmpty());
        assertTrue(CheckoutCapacity.problems(trip, crowd, null).isEmpty());
        assertTrue(CheckoutCapacity.problems(limited(1), null, party(1, "full")).isEmpty(),
                "null existing rows read as none");
    }

    @Test
    public void aChoiceIsCountedOnlyWhenThePartyPicksIt() {
        final Trip trip = trip();
        GoldenTrip.question(trip, GoldenTrip.ADMISSION).choice("fri").setCapacity(2);
        final List<Registration> existing = new ArrayList<>(rows(1, Registration.Status.CONFIRMED, "fri"));
        existing.addAll(rows(3, Registration.Status.NOT_REGISTERED, "fri"));
        existing.addAll(rows(4, Registration.Status.CONFIRMED, "full"));
        assertTrue(CheckoutCapacity.problems(trip, existing, party(1, "fri")).isEmpty(), "1 + 1 fits 2");
        assertTrue(CheckoutCapacity.problems(trip, existing, party(5, "full")).isEmpty(), "nobody picks Friday");
        assertEquals(CheckoutCapacity.problems(trip, existing, party(2, "fri")), List.of(
                new CheckoutProblem(CheckoutProblem.CAPACITY_FULL, null, "fri",
                        "\"Fri only\" (Admission) has only 1 place left, but your party picked it for 2 people.")));
        existing.addAll(rows(1, Registration.Status.PENDING, "fri"));
        assertEquals(CheckoutCapacity.problems(trip, existing, party(1, "fri")).get(0).message(),
                "\"Fri only\" (Admission) is full.");
    }

    @Test
    public void anAddOnCapacityCountsEachPersonOnce() {
        final Trip trip = trip();
        GoldenTrip.question(trip, GoldenTrip.EXTRAS).choice("shirt").setCapacity(1);
        final List<Map<String, String>> party = List.of(answers("full", "shirt,shirt"));
        assertTrue(CheckoutCapacity.problems(trip, List.of(), party).isEmpty());
        final List<Map<String, String>> withNull = Arrays.asList(answers("full", "shirt"), null);
        assertTrue(CheckoutCapacity.problems(trip, List.of(), withNull).isEmpty(), "a null answer map picks nothing");
        assertEquals(CheckoutCapacity.problems(trip, List.of(row(Registration.Status.CONFIRMED,
                answers("full", "shirt"))), party).get(0).ref(), "shirt");
    }

    @Test
    public void aWithdrawnChoiceIsNotCapacityChecked() {
        final Trip trip = trip();
        GoldenTrip.question(trip, GoldenTrip.ADMISSION).choice("fri").setCapacity(0);
        GoldenTrip.question(trip, GoldenTrip.ADMISSION).choice("fri").setOffered(false);
        assertTrue(CheckoutCapacity.problems(trip, List.of(), party(1, "fri")).isEmpty(),
                "the validator refuses a withdrawn pick; capacity has nothing to add");
    }

    @Test
    public void codeUsesCountPlaceHoldingRowsPricedWithTheCode() {
        final Trip trip = trip();
        final PriceBreakdown speaker = CheckoutPricing.price(trip, ADULT, answers("full", null), "SPEAKER", BEFORE);
        final PriceBreakdown early = CheckoutPricing.price(trip, ADULT, answers("full", null), "EARLY10", BEFORE);
        final List<Registration> existing = List.of(
                row(Registration.Status.CONFIRMED, Map.of()).withPriceBreakdown(speaker),
                row(Registration.Status.PENDING, Map.of()).withPriceBreakdown(speaker),
                row(Registration.Status.NOT_REGISTERED, Map.of()).withPriceBreakdown(speaker),
                row(Registration.Status.CONFIRMED, Map.of()).withPriceBreakdown(early),
                row(Registration.Status.CONFIRMED, Map.of()),
                row(Registration.Status.CONFIRMED, Map.of()).withPriceBreakdown(
                        new PriceBreakdown(null, null, 0L, null, null)));
        assertEquals(CheckoutCapacity.codeUses(existing, " speaker "), 2);
        assertEquals(CheckoutCapacity.codeUses(existing, "EARLY10"), 1);
        assertEquals(CheckoutCapacity.codeUses(existing, "NOPE"), 0);
        assertEquals(CheckoutCapacity.codeUses(existing, " "), 0);
        assertEquals(CheckoutCapacity.codeUses(existing, null), 0);
        assertEquals(CheckoutCapacity.codeUses(null, "SPEAKER"), 0);
    }
}
