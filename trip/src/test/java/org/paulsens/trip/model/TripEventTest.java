package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.time.LocalDateTime;
import static java.time.temporal.ChronoUnit.MINUTES;
import java.util.HashMap;
import java.util.Map;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.paulsens.trip.dynamo.DynamoUtils;
import org.paulsens.trip.testutil.TestData;
import org.testng.Assert;
import org.testng.annotations.Test;

public class TripEventTest {
    @Test
    public void equalsTest() {
        EqualsVerifier.forClass(TripEvent2.class).suppress(Warning.NONFINAL_FIELDS).verify();
    }

    @Test
    void canSerializeTripEvent() throws IOException {
        final ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        final TripEvent2 te1  = new TripEvent2();
        final String json1 = mapper.writeValueAsString(te1);
        System.err.println(json1);
        final TripEvent2 restoredTE1 = mapper.readValue(json1, TripEvent2.class);
        Assert.assertEquals(te1, restoredTE1);
    }

    @Test
    void canConvertTripEventToTripEvent2() throws IOException {
        // Setup TripEvent
        final String title = TestData.genAlpha(5);
        final String notes = TestData.genAlpha(6);
        final LocalDateTime time = LocalDateTime.now();
        final Map<String, String> privNotes = new HashMap<>();
        final String USER1 = TestData.genAlpha(7);
        final String USER1_NOTE = TestData.genAlpha(8);
        final String USER2 = TestData.genAlpha(9);
        final String USER2_NOTE = TestData.genAlpha(10);
        privNotes.put(USER1, USER1_NOTE);
        privNotes.put(USER2, USER2_NOTE);

        // Get mocked data to modify b/c crappy old TripEvent requires db access to do anything
        final Trip trip = DynamoUtils.getInstance().getTrips().join().get(0);
        final String trId = trip.addTripEvent("bad", "bad", time.minus(5, MINUTES));

        final TripEvent te = new TripEvent(trId, title, notes, time, privNotes);
        trip.editTripEvent(te);

        // Create TE2
        final TripEvent2 te2 = new TripEvent2(te);

        // Verify
        Assert.assertEquals(te2.getId(), trId, "ID doesn't match!");
        Assert.assertEquals(te2.getTitle(), title, "Title doesn't match!");
        Assert.assertEquals(te2.getNotes(), notes, "Notes doesn't match!");
        Assert.assertEquals(te2.getStart(), time, "Start doesn't match!");
        Assert.assertEquals(te2.getPrivNotes().get(USER1), USER1_NOTE, "User 1 note doesn't match!");
        Assert.assertEquals(te2.getPrivNotes().get(USER2), USER2_NOTE, "User 2 note doesn't match!");
        Assert.assertEquals(te2.getParticipants().size(), te.getParticipants().size(), "Wrong number of participants");
        Assert.assertEquals(te2.getPrivNotes().size(), te.getPeople().size(), "Wrong number of private notes!");
    }
}