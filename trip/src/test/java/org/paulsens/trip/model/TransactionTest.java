package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.Test;

public class TransactionTest {
    @Test
    public void equalsTest() {
        EqualsVerifier.forClass(Transaction.class).suppress(Warning.NONFINAL_FIELDS).verify();
    }

    @Test
    public void canDeserializeOldValue() throws IOException {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final String old = "{\"txId\":\"4eef69b6-d3a7-4ccc-8a59-91d56ff04675\",\"userId\":\"4fa683c9-4b33-d42f-b1f5"
                + "-0fc348711fbc\",\"txDate\":\"2019-12-16T00:00:00\",\"amount\":221.07,\"category\":\"Car Rental\""
                + ",\"note\":\"Lisbon Sixt Rental, conf # 989223558\"}";
        final Transaction restored = mapper.readValue(old, Transaction.class);
        Assert.assertEquals((float) restored.getAmount(), 221.07f);
        Assert.assertEquals(restored.getCategory(), "Car Rental");
        Assert.assertEquals(restored.getTxType(), Transaction.TransactionType.Payment);
    }

    @Test
    public void canSerializeDeserialize() throws IOException {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final Transaction.TransactionType tt = RandomData.randomEnum(Transaction.TransactionType.class);
        final Transaction orig = new Transaction(null, Person.Id.from("userId"), RandomData.genAlpha(5),
                Transaction.Type.Tx, tt, LocalDateTime.now(ZoneOffset.UTC), 12.0f, "category", null);
        final String json = mapper.writeValueAsString(orig);
        final Transaction restored = mapper.readValue(json, Transaction.class);
        Assert.assertEquals(restored, orig, "[de]serialization failed!");
    }

    @Test
    public void deletePersists() throws IOException {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final LocalDateTime date = LocalDateTime.now(ZoneOffset.UTC);
        final Transaction.TransactionType tt = RandomData.randomEnum(Transaction.TransactionType.class);
        final Transaction orig = new Transaction(
                null, Person.Id.from("userId"), null, null, tt, date, null, null, null);
        orig.setDeleted(orig.getTxDate().plusHours(2));
        final String json = mapper.writeValueAsString(orig);
        final Transaction restored = mapper.readValue(json, Transaction.class);
        Assert.assertEquals(restored, orig, "[de]serialization failed!");
    }

    @Test
    public void deleteNowWorks() {
        final Transaction tx = new Transaction(
                Person.Id.from("userId"), RandomData.genAlpha(32), Transaction.Type.Batch);
        Assert.assertNull(tx.getDeleted(), "Deleted should start out as null!");
        final LocalDateTime delTime = tx.delete();
        Assert.assertNotNull(tx.getDeleted(), "Should have set the deleted date!");
        Assert.assertEquals(delTime, tx.getDeleted(), "Deleted time returned doesn't match!");
    }
}