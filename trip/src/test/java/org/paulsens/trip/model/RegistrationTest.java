package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import nl.jqno.equalsverifier.EqualsVerifier;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Registration.Status;
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

        final Registration reg  = new Registration(tripId, userId)
                .withStatus(Status.NOT_REGISTERED);
        Thread.sleep(1L);
        final Registration reg2  = new Registration(tripId, userId)
                .withStatus(Status.NOT_REGISTERED);
        Assert.assertNotEquals(reg, reg2, "Timestamps should be different.");

        final String customVal = RandomData.genAlpha(13);
        reg.getOptions().put("Custom", customVal);
        final String json = mapper.writeValueAsString(reg);
        System.out.println(json);
        final Registration restoredReg = mapper.readValue(json, Registration.class);
        Assert.assertEquals(reg, restoredReg);
        Assert.assertEquals(userId, restoredReg.getUserId());
        Assert.assertEquals(tripId, restoredReg.getTripId());
        Assert.assertEquals(customVal, restoredReg.getOptions().get("Custom"));
    }

    @Test
    void createDateIsPreserved() throws Exception {
        final LocalDateTime createTime = LocalDateTime.now();
        final Status status = RandomData.randomEnum(Status.class);
        Thread.sleep(1L);
        final Registration reg = new Registration(
                RandomData.genAlpha(3), Person.Id.newInstance(), createTime, status, null);
        Assert.assertEquals(createTime, reg.getCreated());
        Assert.assertEquals(status, reg.getStatus());
    }

    @Test
    void metadataGettersReadFromOptionsMap() {
        final Registration reg = new Registration(RandomData.genAlpha(3), Person.Id.newInstance());
        // Empty registration: every metadata getter returns null.
        Assert.assertNull(reg.getRegistrantType());
        Assert.assertNull(reg.getDiscount());
        Assert.assertNull(reg.getRegistrationType());

        reg.getOptions().put(Registration.OPT_REGISTRANT_TYPE, Registration.RegistrantType.ADULT);
        reg.getOptions().put(Registration.OPT_DISCOUNT, Registration.Discount.EARLY_BIRD);
        reg.getOptions().put(Registration.OPT_REGISTRATION_TYPE, Registration.RegistrationType.FULL);

        Assert.assertEquals(reg.getRegistrantType(), Registration.RegistrantType.ADULT);
        Assert.assertEquals(reg.getDiscount(), Registration.Discount.EARLY_BIRD);
        Assert.assertEquals(reg.getRegistrationType(), Registration.RegistrationType.FULL);
    }

    @Test
    void canRoundTripMetadataJson() throws Exception {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final Registration reg = new Registration(RandomData.genAlpha(5), Person.Id.newInstance());
        reg.getOptions().put(Registration.OPT_REGISTRANT_TYPE, Registration.RegistrantType.CHILD);
        reg.getOptions().put(Registration.OPT_DISCOUNT, Registration.Discount.EARLY_BIRD);
        reg.getOptions().put(Registration.OPT_REGISTRATION_TYPE, Registration.RegistrationType.FULL);
        reg.getOptions().put("1", "USA");   // per-trip option lives alongside metadata

        final String json = mapper.writeValueAsString(reg);
        final Registration restored = mapper.readValue(json, Registration.class);

        Assert.assertEquals(restored.getRegistrantType(), Registration.RegistrantType.CHILD);
        Assert.assertEquals(restored.getDiscount(), Registration.Discount.EARLY_BIRD);
        Assert.assertEquals(restored.getRegistrationType(), Registration.RegistrationType.FULL);
        Assert.assertEquals(restored.getOptions().get("1"), "USA");
        Assert.assertEquals(restored, reg);
    }

    @Test
    void oldJsonWithoutMetadataDeserializesCleanly() throws Exception {
        // Simulates a Registration row written before the metadata keys existed.
        // Backward compat: missing keys are simply absent from the map.
        final String legacyJson = "{"
                + "\"tripId\":\"trip-1\","
                + "\"userId\":\"user-1\","
                + "\"created\":\"2025-01-01T12:00:00\","
                + "\"status\":\"Pending\","
                + "\"options\":{\"1\":\"USA\",\"opt9\":\"true\"}"
                + "}";
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final Registration restored = mapper.readValue(legacyJson, Registration.class);

        Assert.assertNull(restored.getRegistrantType());
        Assert.assertNull(restored.getDiscount());
        Assert.assertNull(restored.getRegistrationType());
        Assert.assertEquals(restored.getOptions().get("1"), "USA");
        Assert.assertEquals(restored.getOptions().get("opt9"), "true");
        Assert.assertEquals(restored.getStatus(), Status.PENDING);
    }
}