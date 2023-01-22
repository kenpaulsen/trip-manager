package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import nl.jqno.equalsverifier.EqualsVerifier;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.Test;

public class RegistrationTest {
    @Test
    public void equalsTest() {
        EqualsVerifier.forClass(Registration.class).verify();
    }

    @Test
    void canSerializeTripEvent() throws Exception {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final Person.Id userId = Person.Id.newInstance();
        final String tripId = RandomData.genAlpha(18);

        final Registration reg  = new Registration(tripId, userId);
        Thread.sleep(1L);
        final Registration reg2  = new Registration(tripId, userId);
        Assert.assertNotEquals(reg, reg2, "Timestamps should be different.");

        final String customVal = RandomData.genAlpha(13);
        reg.getOptions().put("Custom", customVal);
        final String json = mapper.writeValueAsString(reg);
        final Registration restoredReg = mapper.readValue(json, Registration.class);
        Assert.assertEquals(reg, restoredReg);
        Assert.assertEquals(userId, restoredReg.getUserId());
        Assert.assertEquals(tripId, restoredReg.getTripId());
        Assert.assertEquals(customVal, restoredReg.getOptions().get("Custom"));
    }

    @Test
    void createDateIsPreserved() throws Exception {
        final LocalDateTime createTime = LocalDateTime.now();
        final String status = RandomData.genAlpha(12);
        Thread.sleep(1L);
        final Registration reg = new Registration(
                RandomData.genAlpha(3), Person.Id.newInstance(), createTime, status, null);
        Assert.assertEquals(createTime, reg.getCreated());
        Assert.assertEquals(status, reg.getStatus());
    }
}