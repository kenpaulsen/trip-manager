package org.paulsens.trip.action;

import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.testng.Assert;
import org.testng.annotations.Test;

import static org.paulsens.trip.cache.Cached.NO;

/**
 * The edit-seeding contract behind the near-cache: a working copy that will be SAVED wholesale must never be
 * seeded from the near-cache, or a stale seed silently overwrites fields somebody else changed. The
 * {@code *ForEdit} variants exist for exactly the pages/flows that do this (trip edit drafts, the person
 * editors, media mutation helpers); this pins their read-what-is-stored behavior and their miss contracts,
 * which mirror the display getters' so pages can swap between them without new null checks.
 *
 * <p>The BYPASS itself (Cached.NO never served from heap) is proven in {@code NearCacheClientTest} /
 * {@code NearCachePathTest}; local mode has no near-cache to observe at this level.
 */
public class ForEditReadsTest {

    @Test
    public void getTripForEditReadsTheStoredTripAndBlanksOnAMiss() throws Exception {
        final Trip stored = Trip.builder().id("fe-trip-" + System.nanoTime()).title("For Edit").build();
        Assert.assertTrue(DAO.getInstance().saveTrip(stored));

        final Trip seeded = new TripCommands().getTripForEdit(stored.getId());
        Assert.assertEquals(seeded.getId(), stored.getId());
        Assert.assertEquals(seeded.getTitle(), "For Edit");

        final Trip miss = new TripCommands().getTripForEdit("fe-absent-" + System.nanoTime());
        Assert.assertNotNull(miss, "same miss contract as getTrip: a blank Trip, never null");
        Assert.assertNull(miss.getTitle());
    }

    @Test
    public void getPersonForEditReadsTheStoredPersonAndMintsAPlaceholderOnAMiss() throws Exception {
        final Person stored = new Person();
        stored.setFirst("Fresh");
        stored.setLast("Seed");
        Assert.assertTrue(DAO.getInstance().savePerson(stored));

        final Person seeded = new PersonCommands().getPersonForEdit(stored.getId());
        Assert.assertEquals(seeded.getId(), stored.getId());
        Assert.assertEquals(seeded.getFirst(), "Fresh");

        final Person.Id absent = Person.Id.from("fe-absent-" + System.nanoTime());
        final Person miss = new PersonCommands().getPersonForEdit(absent);
        Assert.assertNotNull(miss, "same miss contract as getPerson: a blank Person, never null");
        Assert.assertNotEquals(miss.getId(), absent,
                "the placeholder keeps its own minted id (the PersonLookupMissContractTest property)");
    }

    @Test
    public void editSeedsMatchWhatTheDeclaredFreshReadReturns() throws Exception {
        // The ForEdit variants must answer exactly what a Cached.NO facade read answers -- they are that
        // read, packaged for EL. Guards against one of them quietly re-routing through a cached path.
        final Trip stored = Trip.builder().id("fe-match-" + System.nanoTime()).title("Match").build();
        Assert.assertTrue(DAO.getInstance().saveTrip(stored));
        Assert.assertEquals(new TripCommands().getTripForEdit(stored.getId()).getTitle(),
                DAO.getInstance().getTrip(stored.getId(), NO).orElseThrow().getTitle());
    }
}
