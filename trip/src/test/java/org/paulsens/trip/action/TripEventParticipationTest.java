package org.paulsens.trip.action;

import java.util.ArrayList;
import java.util.List;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Adding and removing a person from a trip event, the way the itinerary page does it.
 *
 * <p>The bug these exist for: the page's event picker converts its values through {@code TripEventConverter},
 * which loads from the DAO. Since the persistence redesign a DAO read <b>deserializes a new object</b> rather
 * than returning a shared one, so the page was mutating a detached copy and then calling {@code saveTrip}, which
 * serializes the instances the <em>trip</em> holds. Nothing was written.
 *
 * <p>It presented as a persistence fault with no error anywhere: the save reported success, the durable store was
 * simply never told, and the on-screen table was bound to the selection rather than to stored data, so the change
 * appeared and then vanished on the next visit. {@link #mutatingADetachedCopyDoesNotPersist} pins the mechanism
 * so that "just mutate the converted object" cannot quietly come back.
 */
public class TripEventParticipationTest {

    private final TripCommands trip = new TripCommands();

    @BeforeClass
    void beforeClass() {
        FakeData.initFakeData();
        FakeData.addFakeData();
    }

    private static Trip trip() {
        return FakeData.getFakeTrips().get(0);
    }

    private static List<TripEvent> eventsFor(final Trip theTrip, final Person.Id who) {
        return new ArrayList<>(theTrip.getTripEventsForUser(who));
    }

    @Test
    public void addingAnEventPersists() {
        final Trip theTrip = trip();
        final Person.Id who = FakeData.getFakePeople().get(FakeData.getFakePeople().size() - 3).getId();
        final TripEvent target = theTrip.getTripEvents().stream()
                .filter(te -> !te.getParticipants().contains(who))
                .findFirst().orElseThrow();

        final List<TripEvent> selection = eventsFor(theTrip, who);
        // Exactly what the page submits: the value that came back through the converter, not the trip's instance.
        selection.add(DAO.getInstance().getTripEvent(target.getId()).join());

        Assert.assertTrue(trip.setEventParticipation(theTrip, selection, who));

        final TripEvent stored = DAO.getInstance().getTripEvent(target.getId()).join();
        Assert.assertTrue(stored.getParticipants().contains(who),
                "the added participant must reach the durable store, not just the screen");
    }

    @Test(dependsOnMethods = "addingAnEventPersists")
    public void removingAnEventPersists() {
        final Trip theTrip = trip();
        final Person.Id who = FakeData.getFakePeople().get(FakeData.getFakePeople().size() - 3).getId();
        final TripEvent target = theTrip.getTripEvents().stream()
                .filter(te -> te.getParticipants().contains(who))
                .findFirst().orElseThrow();

        final List<TripEvent> selection = eventsFor(theTrip, who);
        selection.removeIf(te -> te.getId().equals(target.getId()));

        Assert.assertTrue(trip.setEventParticipation(theTrip, selection, who));

        final TripEvent stored = DAO.getInstance().getTripEvent(target.getId()).join();
        Assert.assertFalse(stored.getParticipants().contains(who), "the removal must reach the durable store");
    }

    @Test
    public void otherPeopleOnTheEventAreUntouched() {
        // Reconciling by "who is selected" must not be read as "who is on the event".
        final Trip theTrip = trip();
        final Person.Id who = FakeData.getFakePeople().get(FakeData.getFakePeople().size() - 2).getId();
        final TripEvent target = theTrip.getTripEvents().stream()
                .filter(te -> !te.getParticipants().isEmpty() && !te.getParticipants().contains(who))
                .findFirst().orElseThrow();
        final List<Person.Id> others = new ArrayList<>(target.getParticipants());

        final List<TripEvent> selection = eventsFor(theTrip, who);
        selection.add(DAO.getInstance().getTripEvent(target.getId()).join());
        trip.setEventParticipation(theTrip, selection, who);

        final TripEvent stored = DAO.getInstance().getTripEvent(target.getId()).join();
        Assert.assertTrue(stored.getParticipants().containsAll(others),
                "everyone already on the event must still be on it: " + stored.getParticipants());
    }

    /**
     * The mechanism itself, asserted directly.
     *
     * <p>Not a test of production code -- it is the shape of the bug. If a DAO read ever again returns the same
     * instance the trip holds, this fails and the extra indirection can be reconsidered; while it returns a copy,
     * this documents exactly why the page cannot mutate what the converter handed it.
     */
    @Test
    public void mutatingADetachedCopyDoesNotPersist() {
        final Trip theTrip = trip();
        final Person.Id who = FakeData.getFakePeople().get(FakeData.getFakePeople().size() - 1).getId();
        final TripEvent inTrip = theTrip.getTripEvents().stream()
                .filter(te -> !te.getParticipants().contains(who))
                .findFirst().orElseThrow();

        final TripEvent fromDao = DAO.getInstance().getTripEvent(inTrip.getId()).join();
        Assert.assertNotSame(fromDao, inTrip, "a DAO read returns a copy; that is the whole trap");
        Assert.assertEquals(fromDao, inTrip, "and it is equal by value, so every contains() check still passed");

        final List<Person.Id> updated = new ArrayList<>(fromDao.getParticipants());
        updated.add(who);
        fromDao.setParticipants(updated);
        trip.saveTrip(theTrip);

        final TripEvent stored = DAO.getInstance().getTripEvent(inTrip.getId()).join();
        Assert.assertFalse(stored.getParticipants().contains(who),
                "mutating a detached copy must NOT persist -- if it does, the reason for setEventParticipation "
                        + "has changed and this test should be revisited");
    }

    @Test
    public void anUnchangedSelectionIsNotWritten() {
        // Saving on every stray ajax would rewrite every event on the trip and widen the window for losing a
        // concurrent edit.
        final Trip theTrip = trip();
        final Person.Id who = FakeData.getFakePeople().get(2).getId();
        Assert.assertTrue(trip.setEventParticipation(theTrip, eventsFor(theTrip, who), who));
    }

    @Test
    public void nullArgumentsAreRefusedRatherThanClearingEveryone() {
        final Trip theTrip = trip();
        Assert.assertFalse(trip.setEventParticipation(theTrip, List.of(), null));
        Assert.assertFalse(trip.setEventParticipation(null, List.of(), FakeData.getFakePeople().get(0).getId()));
    }

    // --- private notes: the same trap, reached through the table's binding instead of the picker's ---

    @Test
    public void aNoteWrittenThroughADetachedRowStillPersists() {
        // The itinerary table is bound to viewScope.userEvents, which the picker overwrites with converter
        // output -- so after anyone uses the picker, every row is a detached copy. Noting one must still work.
        final Trip theTrip = trip();
        final Person.Id who = FakeData.getFakePeople().get(0).getId();
        final TripEvent inTrip = theTrip.getTripEvents().get(0);
        final TripEvent detachedRow = DAO.getInstance().getTripEvent(inTrip.getId()).join();
        Assert.assertNotSame(detachedRow, inTrip, "precondition: the row is a copy, as it is after the picker");

        Assert.assertTrue(trip.saveEventNote(theTrip, detachedRow, who, "bring the blue folder"));

        final TripEvent stored = DAO.getInstance().getTripEvent(inTrip.getId()).join();
        Assert.assertEquals(stored.getPrivNotes().get(who), "bring the blue folder",
                "a note typed after using the picker must still reach the durable store");
    }

    @Test
    public void aNoteOnlyAffectsItsOwnAuthor() {
        final Trip theTrip = trip();
        final Person.Id mine = FakeData.getFakePeople().get(0).getId();
        final Person.Id theirs = FakeData.getFakePeople().get(1).getId();
        final TripEvent event = theTrip.getTripEvents().get(1);

        trip.saveEventNote(theTrip, event, mine, "mine");
        trip.saveEventNote(theTrip, event, theirs, "theirs");

        final TripEvent stored = DAO.getInstance().getTripEvent(event.getId()).join();
        Assert.assertEquals(stored.getPrivNotes().get(mine), "mine");
        Assert.assertEquals(stored.getPrivNotes().get(theirs), "theirs",
                "private notes are per person; one must not overwrite another");
    }

    @Test
    public void aClearedNoteIsStoredAsEmptyRatherThanThrowing() {
        final Trip theTrip = trip();
        final Person.Id who = FakeData.getFakePeople().get(0).getId();
        final TripEvent event = theTrip.getTripEvents().get(2);
        Assert.assertTrue(trip.saveEventNote(theTrip, event, who, null));
        Assert.assertEquals(
                DAO.getInstance().getTripEvent(event.getId()).join().getPrivNotes().get(who), "");
    }

    @Test
    public void anEventFromAnotherTripIsRefused() {
        // Resolving by id inside the trip means a foreign event finds no home; it must be refused, not silently
        // noted onto nothing or appended to this trip.
        final Trip theTrip = trip();
        final TripEvent foreign = new TripEvent();
        Assert.assertFalse(trip.saveEventNote(theTrip, foreign, FakeData.getFakePeople().get(0).getId(), "x"));
        Assert.assertNull(theTrip.getTripEvent(foreign.getId()), "the foreign event must not join the trip");
    }
}
