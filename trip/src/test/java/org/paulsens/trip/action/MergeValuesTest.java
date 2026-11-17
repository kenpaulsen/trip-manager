package org.paulsens.trip.action;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Address;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/** Whose values a merge fills ({@link MergeValues}): the trip's facts for all, the person-on-trip ones only there. */
public class MergeValuesTest {
    private Trip trip;
    private Person onTrip;
    private ReminderRecipients facts;

    @BeforeClass
    void beforeClass() {
        FakeData.initFakeData();
        FakeData.addFakeData();
        trip = DAO.getInstance().getTrip(FakeData.FAKE_TRIP_ID, Cached.NO).orElseThrow();
        onTrip = DAO.getInstance().getPerson(trip.getPeople().get(0), Cached.NO).orElseThrow();
        onTrip.setCell("555-0100");
        facts = new ReminderRecipients((tripId, person) -> 12_345L, (t, person) -> List.of("Release"),
                person -> List.of(), t -> "https://site.example");
    }

    private MergeValues values(final Trip chosen, final boolean siteAdmin, final String... used) {
        return new MergeValues(chosen, siteAdmin, Set.of(used), LocalDate.of(2020, 1, 1), facts,
                (tripId, personId) -> "114");
    }

    private static Person stranger() {
        final Person person = new Person();
        person.setId(Person.Id.newInstance());
        person.setFirst("Sam");
        person.setCell("555-0199");
        person.setAddress(new Address());
        return person;
    }

    @Test
    public void someoneOnTheTripGetsEverything() {
        final Map<String, String> got = values(trip, false, MergeText.ROOM, ReminderText.BALANCE_DUE)
                .valuesFor(onTrip);

        Assert.assertEquals(got.get(MergeText.ROOM), "114");
        Assert.assertEquals(got.get(ReminderText.BALANCE_DUE), "$123.45");
        Assert.assertEquals(got.get(ReminderText.DOCUMENT_LIST), "Release");
        Assert.assertEquals(got.get(ReminderText.TRIP_TITLE), trip.getTitle());
        Assert.assertEquals(got.get(MergeText.CELL), "555-0100");
        Assert.assertEquals(got.get(MergeText.FIRST_NAME), onTrip.getPreferredName());
    }

    @Test
    public void someoneOffTheTripGetsTheTripButNothingAboutThemOnIt() {
        final MergeValues merge = values(trip, false, MergeText.ROOM, ReminderText.BALANCE_DUE);
        final Person stranger = stranger();
        final Map<String, String> got = merge.valuesFor(stranger);

        Assert.assertFalse(merge.onTrip(stranger));
        Assert.assertEquals(got.get(ReminderText.TRIP_TITLE), trip.getTitle());
        Assert.assertFalse(got.containsKey(MergeText.ROOM));
        Assert.assertFalse(got.containsKey(ReminderText.BALANCE_DUE));
        Assert.assertFalse(got.containsKey(MergeText.CELL), "a trip manager's merge fills addresses on the trip only");
        Assert.assertEquals(values(trip, true).valuesFor(stranger).get(MergeText.CELL), "555-0199",
                "a site admin's fills them for anyone");
    }

    @Test
    public void factsTheTextDoesNotUseAreNotLookedUp() {
        final Map<String, String> got = values(trip, false, MergeText.FIRST_NAME).valuesFor(onTrip);

        Assert.assertFalse(got.containsKey(MergeText.ROOM));
        Assert.assertFalse(got.containsKey(ReminderText.BALANCE_DUE));
    }

    @Test
    public void noTripMeansNoTripFacts() {
        final MergeValues merge = values(null, true, MergeText.ROOM);

        Assert.assertFalse(merge.onTrip(onTrip));
        Assert.assertFalse(merge.valuesFor(onTrip).containsKey(ReminderText.TRIP_TITLE));
        Assert.assertFalse(merge.valuesFor(onTrip).containsKey(MergeText.ROOM));
    }

    /**
     * The off-trip warning asks {@link MergeValues#blanksOffTrip}; that answer must be what {@link
     * MergeValues#valuesFor} actually does. For each kind, and for both senders, the kind is "blank off the trip"
     * exactly when one of its fields is filled for someone on the trip but missing for someone who is not.
     */
    @Test
    public void whatIsBlankOffTheTripIsWhatValuesForLeavesOut() {
        final Person stranger = stranger();
        for (final boolean siteAdmin : new boolean[] {false, true}) {
            for (final MergeText.Kind kind : MergeText.Kind.values()) {
                final String[] fields = MergeText.FIELDS.stream().filter(field -> field.getKind() == kind)
                        .map(MergeText.Field::getName).toArray(String[]::new);
                final MergeValues merge = values(trip, siteAdmin, fields);
                final Map<String, String> on = merge.valuesFor(onTrip);
                final Map<String, String> off = merge.valuesFor(stranger);
                final boolean leftOut = java.util.Arrays.stream(fields)
                        .anyMatch(name -> on.containsKey(name) && !off.containsKey(name));
                Assert.assertEquals(merge.blanksOffTrip(java.util.EnumSet.of(kind)), leftOut,
                        kind + (siteAdmin ? " (site admin)" : " (trip manager)"));
            }
        }
    }

    /** The merge pays for the traveler reads only for the keys those reads fill: the two lists are one. */
    @Test
    public void theTravelerFactKeysAreWhatTheReadFills() {
        Assert.assertEquals(facts.travelerFacts(trip, onTrip).keySet(), ReminderRecipients.TRAVELER_FACTS);
    }
}
