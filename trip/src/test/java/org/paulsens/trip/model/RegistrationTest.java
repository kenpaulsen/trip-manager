package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import nl.jqno.equalsverifier.EqualsVerifier;
import org.paulsens.trip.dynamo.DynamoUtils;
import org.paulsens.trip.testutil.TestData;
import org.testng.Assert;
import org.testng.annotations.Test;

public class RegistrationTest {
    @Test
    public void equalsTest() {
        EqualsVerifier.forClass(Registration.class).verify();
    }

    @Test
    void canSerializeTripEvent() throws Exception {
        final ObjectMapper mapper = DynamoUtils.getInstance().getMapper();
        final String userId = TestData.genAlpha(15);
        final String tripId = TestData.genAlpha(18);

        final Registration reg  = new Registration(tripId, userId);
        Thread.sleep(1L);
        final Registration reg2  = new Registration(tripId, userId);
        Assert.assertNotEquals(reg, reg2, "Timestamps should be different.");

        final String customVal = TestData.genAlpha(13);
        reg.getNotes().put("Custom", customVal);
        final String json = mapper.writeValueAsString(reg);
        final Registration restoredReg = mapper.readValue(json, Registration.class);
        Assert.assertEquals(reg, restoredReg);
        Assert.assertEquals(userId, restoredReg.getUserId());
        Assert.assertEquals(tripId, restoredReg.getTripId());
        Assert.assertEquals(customVal, restoredReg.getNotes().get("Custom"));
    }

    @Test
    void createDateIsPreserved() throws Exception {
        final LocalDateTime createTime = LocalDateTime.now();
        Thread.sleep(1L);
        final Registration reg = new Registration(TestData.genAlpha(3), TestData.genAlpha(5), createTime, null);
        Assert.assertEquals(createTime, reg.getCreated());
    }
}