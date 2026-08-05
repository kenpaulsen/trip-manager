package org.paulsens.trip.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.paulsens.trip.dynamo.DAO;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * {@link Trip.TripEventsDeserializer} — the id-list-to-events fan-out that runs on every trip deserialization.
 *
 * <p>Since the virtual-threads migration each id resolves on its own {@code StructuredTaskScope} fork, so what
 * is pinned here is the merge semantics that must survive that concurrency: order preserved, unknown ids
 * dropped (never null-padded — EL iterates this list), and an interrupt failing loudly instead of returning a
 * silently truncated trip.
 *
 * <p>The events are saved HERE, not borrowed from {@code FakeData}: the shared in-memory store is mutated by
 * other test classes, and whether the seeded events still exist depends on class execution order — which
 * differs between machines (it passed locally and failed in CI on exactly that).
 */
public class TripEventsDeserializerTest {

    private final Trip.TripEventsDeserializer converter = new Trip.TripEventsDeserializer();
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
        return new TripEvent(UUID.randomUUID().toString(), TripEvent.Type.FLIGHT, title, "deserializer fixture",
                LocalDateTime.now().plusDays(daysOut), LocalDateTime.now().plusDays(daysOut).plusHours(3),
                null, null);
    }

    @Test
    public void eventsResolveInIdOrder() {
        final List<String> ids = events.stream().map(TripEvent::getId).toList();

        final List<TripEvent> out = converter.convert(ids);

        Assert.assertEquals(out.stream().map(TripEvent::getId).toList(), ids,
                "concurrent resolution must not reorder the trip's events");
    }

    @Test
    public void anUnknownIdIsDroppedNotNullPadded() {
        final String realId = events.get(0).getId();

        final List<TripEvent> out = converter.convert(List.of("no-such-event", realId));

        Assert.assertEquals(out.size(), 1, "an unknown id is dropped; a null in this list breaks EL iteration");
        Assert.assertEquals(out.get(0).getId(), realId);
    }

    @Test
    public void aNullListMeansNoEvents() {
        Assert.assertEquals(converter.convert(null), List.of());
    }

    /** A trip with half its events would be worse than a failed page: fail loudly, and keep the flag set. */
    @Test
    public void anInterruptFailsLoudlyRatherThanReturningATruncatedTrip() {
        final String realId = events.get(0).getId();
        Thread.currentThread().interrupt();
        try {
            Assert.assertThrows(IllegalStateException.class, () -> converter.convert(List.of(realId)));
            Assert.assertTrue(Thread.currentThread().isInterrupted(),
                    "the interrupt must be re-asserted for whoever owns this thread");
        } finally {
            Assert.assertTrue(Thread.interrupted(), "clear the flag so it cannot poison the next test");
        }
    }
}
