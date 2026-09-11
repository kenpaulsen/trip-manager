package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.paulsens.trip.dynamo.DAO;
import org.testng.Assert;
import org.testng.annotations.Test;

public class TripEventTest {
    @Test
    public void equalsTest() {
        EqualsVerifier.forClass(TripEvent.class).suppress(Warning.NONFINAL_FIELDS).verify();
    }

    @Test
    void canSerializeTripEvent() throws IOException {
        final ObjectMapper mapper = DAO.getInstance().getMapper();

        final TripEvent te1  = new TripEvent();
        final String json1 = mapper.writeValueAsString(te1);
        final TripEvent restoredTE1 = mapper.readValue(json1, TripEvent.class);
        Assert.assertEquals(te1, restoredTE1);
    }

    /** The typed components ride the row as a map; a row written before they existed reads back with none. */
    @Test
    void detailsRoundTripAndAreOmittedWhenEmpty() throws IOException {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final TripEvent bare = new TripEvent();
        Assert.assertFalse(bare.hasDetails());
        Assert.assertNull(bare.detail(TripEvent.Detail.FROM));
        Assert.assertFalse(mapper.writeValueAsString(bare).contains("details"), "no components, no key");

        final TripEvent flight = new TripEvent("f1", TripEvent.Type.FLIGHT, "PDX -> FCO", "AS 123: ...",
                LocalDateTime.of(2028, 5, 1, 8, 15), LocalDateTime.of(2028, 5, 1, 17, 25), null, null);
        flight.setDetails(new LinkedHashMap<>(Map.of(TripEvent.Detail.FROM.key(), "PDX",
                TripEvent.Detail.TO.key(), "FCO", TripEvent.Detail.FLIGHT_NUMBER.key(), "AS 123")));
        Assert.assertTrue(flight.hasDetails());
        Assert.assertEquals(flight.detail(TripEvent.Detail.TO), "FCO");
        Assert.assertNull(flight.detail(TripEvent.Detail.CARRIER));
        final TripEvent restored = mapper.readValue(mapper.writeValueAsString(flight), TripEvent.class);
        Assert.assertEquals(restored, flight);
        Assert.assertEquals(restored.detail(TripEvent.Detail.FLIGHT_NUMBER), "AS 123");

        final TripEvent legacy = mapper.readValue(
                "{\"id\":\"old\",\"title\":\"SEA -> AMS\",\"start\":\"2021-06-16T14:05:00\"}", TripEvent.class);
        Assert.assertFalse(legacy.hasDetails(), "a legacy row has no components");
        Assert.assertEquals(TripEvent.Detail.FLIGHT_NUMBER.key(), "flightNumber", "the stored key is stable");
    }
}
