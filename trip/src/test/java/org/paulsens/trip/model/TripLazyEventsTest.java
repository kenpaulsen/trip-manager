package org.paulsens.trip.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.paulsens.trip.dynamo.DAO;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Lazy trip-event resolution — the successor of the Jackson-converter fan-out that ran on EVERY trip
 * materialization (the 2026-08-18 incident's traffic amplifier). What is pinned here: resolution runs at
 * most once per instance (memoized, same mutable list back every time), order is preserved, an unknown id
 * is dropped from the resolved list but NEVER from the serialized ids (the old converter's silent-drop bug
 * made the loss permanent on the next save), an untouched trip round-trips its ids with zero resolution,
 * and an interrupt fails loudly rather than returning a truncated trip.
 *
 * <p>Events are saved HERE, not borrowed from {@code FakeData}: the shared in-memory store is mutated by
 * other test classes and class execution order differs between machines.</p>
 */
public class TripLazyEventsTest {

    private List<TripEvent> events;

    @BeforeClass
    public void saveOwnEvents() {
        events = List.of(
                event("IST -> SJJ", 10),
                event("Hotel Ruza", 11),
                event("SJJ -> IST", 18));
        for (final TripEvent te : events) {
            Assert.assertTrue(DAO.getInstance().saveTripEvent(te), "fixture save must succeed");
        }
    }

    private static TripEvent event(final String title, final int daysOut) {
        return new TripEvent(UUID.randomUUID().toString(), TripEvent.Type.FLIGHT, title, "lazy fixture",
                LocalDateTime.now().plusDays(daysOut), LocalDateTime.now().plusDays(daysOut).plusHours(3),
                null, null);
    }

    private List<String> ids() {
        return events.stream().map(TripEvent::getId).toList();
    }

    @Test
    public void eventsResolveInIdOrderOnFirstAccess() {
        final Trip trip = Trip.builder().tripEventIds(ids()).build();

        Assert.assertNull(trip.getResolvedTripEvents(), "a fresh trip must be unresolved");
        Assert.assertEquals(trip.getTripEvents().stream().map(TripEvent::getId).toList(), ids(),
                "concurrent resolution must not reorder the trip's events");
    }

    @Test
    public void resolutionIsMemoizedToTheSameMutableList() {
        final Trip trip = Trip.builder().tripEventIds(ids()).build();

        final List<TripEvent> first = trip.getTripEvents();
        Assert.assertSame(trip.getTripEvents(), first,
                "every caller must get the SAME list -- the mutate-then-save contract depends on it");
    }

    @Test
    public void anUnknownIdIsDroppedFromTheListButNeverFromTheSerializedIds() throws Exception {
        final String realId = events.get(0).getId();
        final Trip trip = Trip.builder().tripEventIds(List.of("no-such-event", realId)).build();

        final List<TripEvent> resolved = trip.getTripEvents();
        Assert.assertEquals(resolved.size(), 1, "an unknown id is dropped; a null here breaks EL iteration");
        Assert.assertEquals(resolved.get(0).getId(), realId);

        final String json = DAO.getInstance().getMapper().writeValueAsString(trip);
        Assert.assertTrue(json.contains("no-such-event"),
                "the failed id must survive serialization -- the old converter dropped it permanently");
        Assert.assertTrue(json.contains(realId));
    }

    /** The requestScope render path: a trip whose events are never touched must never fan out. */
    @Test
    public void anUntouchedTripRoundTripsItsIdsWithoutResolving() throws Exception {
        final String unknownId = "never-loaded-" + UUID.randomUUID();
        final Trip trip = Trip.builder().tripEventIds(List.of(unknownId)).build();

        final String json = DAO.getInstance().getMapper().writeValueAsString(trip);

        Assert.assertNull(trip.getResolvedTripEvents(), "serializing must not trigger resolution");
        Assert.assertTrue(json.contains(unknownId), "stored ids round-trip untouched");
        final Trip back = DAO.getInstance().getMapper().readValue(json, Trip.class);
        Assert.assertNull(back.getResolvedTripEvents(), "deserializing must not trigger resolution either");
    }

    @Test
    public void addingAnEventOnAnUnresolvedTripResolvesThenAppends() {
        final Trip trip = Trip.builder().tripEventIds(List.of(events.get(0).getId())).build();

        final String newId = trip.addTripEvent(TripEvent.Type.LODGING, "Added later", "n",
                LocalDateTime.now().plusDays(12), LocalDateTime.now().plusDays(13));

        Assert.assertEquals(trip.getTripEvents().size(), 2);
        Assert.assertNotNull(trip.getTripEvent(newId));
    }

    /** A trip with half its events would be worse than a failed page: fail loudly, keep the flag set. */
    @Test
    public void anInterruptFailsLoudlyRatherThanReturningATruncatedTrip() {
        final Trip trip = Trip.builder().tripEventIds(List.of(events.get(0).getId())).build();
        Thread.currentThread().interrupt();
        try {
            Assert.assertThrows(IllegalStateException.class, trip::getTripEvents);
            Assert.assertTrue(Thread.currentThread().isInterrupted(),
                    "the interrupt must be re-asserted for whoever owns this thread");
        } finally {
            Assert.assertTrue(Thread.interrupted(), "clear the flag so it cannot poison the next test");
        }
    }

    /** Builder-supplied event objects mean a RESOLVED trip -- FakeData's save path must persist them. */
    @Test
    public void builderEventObjectsMakeAResolvedTrip() {
        final Trip trip = Trip.builder().tripEvents(events).build();

        Assert.assertNotNull(trip.getResolvedTripEvents());
        Assert.assertEquals(trip.getTripEvents().stream().map(TripEvent::getId).toList(), ids());
    }
}
