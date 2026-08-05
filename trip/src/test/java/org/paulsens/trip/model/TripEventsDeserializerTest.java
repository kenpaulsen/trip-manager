package org.paulsens.trip.model;

import java.util.List;
import org.paulsens.trip.dynamo.FakeData;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * {@link Trip.TripEventsDeserializer} — the id-list-to-events fan-out that runs on every trip deserialization.
 *
 * <p>Since the virtual-threads migration each id resolves on its own {@code StructuredTaskScope} fork, so what
 * is pinned here is the merge semantics that must survive that concurrency: order preserved, unknown ids
 * dropped (never null-padded — EL iterates this list), and an interrupt failing loudly instead of returning a
 * silently truncated trip.
 */
public class TripEventsDeserializerTest {

    private final Trip.TripEventsDeserializer converter = new Trip.TripEventsDeserializer();

    private static List<TripEvent> seededEvents() {
        return FakeData.getFakeTrips().get(1).getTripEvents();
    }

    @Test
    public void eventsResolveInIdOrder() {
        final List<String> ids = seededEvents().stream().map(TripEvent::getId).toList();

        final List<TripEvent> out = converter.convert(ids);

        Assert.assertEquals(out.stream().map(TripEvent::getId).toList(), ids,
                "concurrent resolution must not reorder the trip's events");
    }

    @Test
    public void anUnknownIdIsDroppedNotNullPadded() {
        final String realId = seededEvents().get(0).getId();

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
        final String realId = seededEvents().get(0).getId();
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
