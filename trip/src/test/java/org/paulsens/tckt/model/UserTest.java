package org.paulsens.tckt.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import nl.jqno.equalsverifier.EqualsVerifier;
import org.testng.Assert;
import org.testng.annotations.Test;

public class UserTest {

    @Test
    public void userIdCanBeSerializedAndRestored() throws Exception {
        final User.Id id = User.Id.newId();
        final ObjectMapper mapper = new ObjectMapper();
        final String strVal = mapper.writeValueAsString(id);
        Assert.assertEquals(strVal, "\"" + id.getValue() + "\"");
        final User.Id restored = mapper.readValue(strVal, User.Id.class);
        Assert.assertEquals(restored, id);
    }

    @Test
    public void equalsWorks() {
        EqualsVerifier.forClass(Ticket.class).verify();
    }
}