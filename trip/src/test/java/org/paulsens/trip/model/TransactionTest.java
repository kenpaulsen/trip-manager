package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.paulsens.trip.dynamo.DynamoUtils;
import org.testng.Assert;
import org.testng.annotations.Test;

public class TransactionTest {
    @Test
    public void equalsTest() {
        EqualsVerifier.forClass(Transaction.class).suppress(Warning.NONFINAL_FIELDS).verify();
    }

    @Test
    public void canSerializeDeserialize() throws IOException {
        final ObjectMapper mapper = DynamoUtils.getInstance().getMapper();
        final Transaction orig = new Transaction(null, "userId", LocalDateTime.now(ZoneOffset.UTC), 12.0f, "category", null);
        final String json = mapper.writeValueAsString(orig);
        final Transaction restored = mapper.readValue(json, Transaction.class);
        Assert.assertEquals(restored, orig, "[de]serialization failed!");
    }

    @Test
    public void deletePersists() throws IOException {
        final ObjectMapper mapper = DynamoUtils.getInstance().getMapper();
        final LocalDateTime date = LocalDateTime.now(ZoneOffset.UTC);
        final Transaction orig = new Transaction(null, "userId", date, null, null, null);
        orig.setDeleted(orig.getTxDate().plus(2, ChronoUnit.HOURS));
        final String json = mapper.writeValueAsString(orig);
        System.out.println("JSON: " + json);
        final Transaction restored = mapper.readValue(json, Transaction.class);
        Assert.assertEquals(restored, orig, "[de]serialization failed!");
    }

    @Test
    public void deleteNowWorks() {
        final Transaction tx = new Transaction("userId");
        Assert.assertNull(tx.getDeleted(), "Deleted should start out as null!");
        final LocalDateTime delTime = tx.delete();
        Assert.assertNotNull(tx.getDeleted(), "Should have set the deleted date!");
        Assert.assertEquals(delTime, tx.getDeleted(), "Deleted time returned doesn't match!");
    }
}
