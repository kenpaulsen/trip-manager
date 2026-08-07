package org.paulsens.trip.dynamo;

import java.util.List;
import java.util.Map;
import org.testng.Assert;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * The media table in the fake store. Chat photos made this table WRITTEN in local mode (album rows), and the
 * media cache reloads through {@code scanAll} — an unregistered table here answered that scan with nothing,
 * which read as every album emptying itself. These pin the put/get/delete/scan round-trip.
 */
public class InMemoryPersistenceMediaTest {

    private static Map<String, AttributeValue> row(final String id) {
        return Map.of(
                "id", AttributeValue.builder().s(id).build(),
                "content", AttributeValue.builder().s("{\"id\":\"" + id + "\"}").build());
    }

    @Test
    public void mediaPutsAreVisibleToScanAllAndSurviveOtherRowsDeletes() {
        final InMemoryPersistence fake = new InMemoryPersistence();
        fake.putItem(b -> b.tableName("media").item(row("m1")));
        fake.putItem(b -> b.tableName("media").item(row("m2")));

        List<Map<String, AttributeValue>> scanned =
                fake.scanAll(b -> b.consistentRead(false).limit(1000).tableName("media").build());
        Assert.assertEquals(scanned.size(), 2, "scanAll must see what putItem stored");

        fake.deleteItem(b -> b.tableName("media")
                .key(Map.of("id", AttributeValue.builder().s("m1").build())));
        scanned = fake.scanAll(b -> b.consistentRead(false).limit(1000).tableName("media").build());
        Assert.assertEquals(scanned.size(), 1, "a delete removes exactly its row from the scan");
        Assert.assertEquals(scanned.get(0).get("id").s(), "m2");
    }

    @Test
    public void mediaGetItemAnswersByRowId() {
        final InMemoryPersistence fake = new InMemoryPersistence();
        fake.putItem(b -> b.tableName("media").item(row("m9")));

        final Map<String, AttributeValue> found = fake.getItem(b -> b.tableName("media")
                .key(Map.of("id", AttributeValue.builder().s("m9").build())).build()).item();
        Assert.assertEquals(found.get("content").s(), "{\"id\":\"m9\"}");

        final Map<String, AttributeValue> missing = fake.getItem(b -> b.tableName("media")
                .key(Map.of("id", AttributeValue.builder().s("nope").build())).build()).item();
        Assert.assertTrue(missing.isEmpty());
    }
}
