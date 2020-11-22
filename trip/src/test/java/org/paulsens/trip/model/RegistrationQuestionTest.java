package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import nl.jqno.equalsverifier.EqualsVerifier;
import org.paulsens.trip.dynamo.DynamoUtils;
import org.paulsens.trip.testutil.TestData;
import org.testng.Assert;
import org.testng.annotations.Test;

public class RegistrationQuestionTest {
    @Test
    public void equalsTest() {
        EqualsVerifier.forClass(RegistrationQuestion.class).verify();
    }

    @Test
    void canSerializeTripEvent() throws Exception {
        final ObjectMapper mapper = DynamoUtils.getInstance().getMapper();

        final int id = 7;
        final String shortDesc = TestData.genAlpha(28);
        final String longDesc = TestData.genAlpha(78);
        final RegistrationQuestion question  = new RegistrationQuestion(id, shortDesc, longDesc);

        final String json = mapper.writeValueAsString(question);
        final RegistrationQuestion restoredQuestion = mapper.readValue(json, RegistrationQuestion.class);
        Assert.assertEquals(question, restoredQuestion);
        Assert.assertEquals(id, restoredQuestion.getId());
        Assert.assertEquals(shortDesc, restoredQuestion.getShortDesc());
        Assert.assertEquals(longDesc, restoredQuestion.getLongDesc());
    }
}