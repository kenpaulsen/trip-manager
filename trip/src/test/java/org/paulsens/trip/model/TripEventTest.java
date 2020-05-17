package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
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
        final TripEvent2 restoredTE1 = mapper.readValue(json1, TripEvent2.class);
        Assert.assertEquals(te1, restoredTE1);
    }
}