package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.testng.Assert;

/** A chat test's own trip, so it depends neither on FakeData's seed nor on what another class did to it. */
public final class ChatTrips {

    private ChatTrips() {
    }

    /** Saves a private trip running for the next two weeks with this roster, under the id the test chose. */
    public static void save(final String tripId, final String title, final Person.Id... roster)
            throws IOException {
        final Trip trip = Trip.builder()
                .id(tripId)
                .title(title)
                .openToPublic(false)
                .startDate(LocalDateTime.now())
                .endDate(LocalDateTime.now().plusDays(14))
                .people(new ArrayList<>(List.of(roster)))
                .build();
        Assert.assertTrue(DAO.getInstance().saveTrip(trip), "test setup: save dedicated trip");
    }
}
