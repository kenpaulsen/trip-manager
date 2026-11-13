package org.paulsens.trip.dynamo;

import java.util.List;
import java.util.Map;
import org.testng.Assert;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * The privileges and settings tables in the fake store. Their scan caches rebuild against it when local mode runs
 * on a real Valkey (the pipeline's integration test), and a rebuild removes what its scan does not return: while
 * the tables were unregistered every scan came back empty, and the first full read removed every seeded role.
 */
public class InMemoryPersistencePrivilegesTest {

    private static Map<String, AttributeValue> row(final String name) {
        return Map.of(
                "name", AttributeValue.builder().s(name).build(),
                "content", AttributeValue.builder().s("{\"name\":\"" + name + "\"}").build());
    }

    private static List<Map<String, AttributeValue>> scan(final InMemoryPersistence fake, final String table) {
        return fake.scanAll(b -> b.consistentRead(true).limit(1000).tableName(table).build());
    }

    @Test
    public void privilegeAndSettingRowsRoundTripThroughScanGetAndDelete() {
        for (final String table : List.of(PrivilegesDAO.PRIVILEGE_TABLE, ConfigDAO.CONFIG_TABLE)) {
            final InMemoryPersistence fake = new InMemoryPersistence();
            fake.putItem(b -> b.tableName(table).item(row("one")));
            fake.putItem(b -> b.tableName(table).item(row("two")));
            Assert.assertEquals(scan(fake, table).size(), 2, table + ": the scan sees what was written");

            final Map<String, AttributeValue> found = fake.getItem(b -> b.tableName(table)
                    .key(Map.of("name", AttributeValue.builder().s("two").build())).build()).item();
            Assert.assertEquals(found.get("content").s(), "{\"name\":\"two\"}");

            fake.deleteItem(b -> b.tableName(table).key(Map.of("name", AttributeValue.builder().s("one").build())));
            Assert.assertEquals(scan(fake, table).size(), 1, table + ": a delete removes exactly its row");
        }
    }
}
