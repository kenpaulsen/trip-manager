package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.Test;

public class RegistrationOptionTest {
    @Test
    public void equalsTest() {
        EqualsVerifier.forClass(RegistrationOption.class).suppress(Warning.NONFINAL_FIELDS).verify();
    }

    @Test
    void canSerializeTripEvent() throws Exception {
        final ObjectMapper mapper = DAO.getInstance().getMapper();

        final int id = 7;
        final String shortDesc = RandomData.genAlpha(28);
        final String longDesc = RandomData.genAlpha(78);
        final RegistrationOption question  = new RegistrationOption(id, shortDesc, longDesc, false);

        final String json = mapper.writeValueAsString(question);
        final RegistrationOption restoredQuestion = mapper.readValue(json, RegistrationOption.class);
        Assert.assertEquals(question, restoredQuestion);
        Assert.assertEquals(id, restoredQuestion.getId());
        Assert.assertEquals(shortDesc, restoredQuestion.getShortDesc());
        Assert.assertEquals(longDesc, restoredQuestion.getLongDesc());
        Assert.assertFalse(restoredQuestion.getShow());
    }
}