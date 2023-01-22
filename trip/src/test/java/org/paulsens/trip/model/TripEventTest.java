package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
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
}
