package org.paulsens.trip.dynamo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class PersistenceTest {
    private final Persistence persistence = new Persistence() {};

    @Test
    public void putItemReturns200() {
        final PutItemResponse resp = persistence.putItem(b -> b.tableName("test"));
        assertEquals(resp.sdkHttpResponse().statusCode(), 200);
        assertTrue(resp.sdkHttpResponse().isSuccessful());
    }

    @Test
    public void scanReturnsEmptyItems() {
        final ScanResponse resp = persistence.scan(b -> b.tableName("test"));
        assertNotNull(resp.items());
        assertTrue(resp.items().isEmpty());
    }

    @Test
    public void queryReturnsEmptyItems() {
        final QueryResponse resp = persistence.query(b -> b.tableName("test"));
        assertNotNull(resp.items());
        assertTrue(resp.items().isEmpty());
    }

    @Test
    public void deleteItemReturns200() {
        final DeleteItemResponse resp = persistence.deleteItem(b -> b.tableName("test"));
        assertEquals(resp.sdkHttpResponse().statusCode(), 200);
        assertTrue(resp.sdkHttpResponse().isSuccessful());
    }

    @Test
    public void toStrAttrCreatesCorrectAttributeValue() {
        final AttributeValue av = persistence.toStrAttr("hello");
        assertEquals(av.s(), "hello");
    }

    @Test
    public void scanAllDefaultsToSinglePageScan() {
        final List<Map<String, AttributeValue>> items = persistence.scanAll(b -> b.tableName("test"));
        assertNotNull(items);
        assertTrue(items.isEmpty());
    }

    @Test
    public void queryAllDefaultsToSinglePageQuery() {
        final List<Map<String, AttributeValue>> items = persistence.queryAll(b -> b.tableName("test"));
        assertNotNull(items);
        assertTrue(items.isEmpty());
    }

    @Test
    public void scanAllUsesOverriddenScan() {
        final Map<String, AttributeValue> row = Map.of("id", AttributeValue.builder().s("x").build());
        final Persistence custom = new Persistence() {
            @Override
            public ScanResponse scan(final Consumer<ScanRequest.Builder> scanRequest) {
                return ScanResponse.builder().items(List.of(row)).build();
            }
        };
        final List<Map<String, AttributeValue>> items = custom.scanAll(b -> b.tableName("test"));
        assertEquals(items, List.of(row));
    }

    /**
     * The exact request: aliased key name, one page-following query, never a consistent read, no index. Several
     * of the tables it reads (registrations, todo items, person data, transactions, bindings) are not backed by
     * the in-memory fake, which answers empty for them whatever the key is named; their own DAO tests run on
     * the real engine ({@link DynamoLocal}), and this pins the shape for everything else.
     */
    @Test
    public void queryPartitionAsksForOnePartitionByItsAliasedKey() {
        final Map<String, AttributeValue> row = Map.of("tripId", AttributeValue.builder().s("t1").build());
        final List<QueryRequest> asked = new ArrayList<>();
        final Persistence recording = new Persistence() {
            @Override
            public List<Map<String, AttributeValue>> queryAll(final Consumer<QueryRequest.Builder> queryRequest) {
                final QueryRequest.Builder builder = QueryRequest.builder();
                queryRequest.accept(builder);
                asked.add(builder.build());
                return List.of(row);
            }
        };

        assertEquals(recording.queryPartition("registrations", "tripId", "t1"), List.of(row));

        assertEquals(asked.size(), 1, "one paginating query");
        final QueryRequest request = asked.get(0);
        assertEquals(request.tableName(), "registrations");
        assertEquals(request.keyConditionExpression(), "#p = :p");
        assertEquals(request.expressionAttributeNames(), Map.of("#p", "tripId"));
        assertEquals(request.expressionAttributeValues(), Map.of(":p", AttributeValue.builder().s("t1").build()));
        assertNull(request.indexName());
        assertNull(request.consistentRead());
        assertNull(request.filterExpression());
    }

    /** Against the fake's own key-schema check: another partition's rows never come back. */
    @Test
    public void queryPartitionReadsOnlyTheNamedPartition() {
        final InMemoryPersistence store = new InMemoryPersistence();
        store.putItem(b -> b.tableName(OrgMemberDAO.ORG_MEMBERS_TABLE).item(Map.of(
                OrgMemberDAO.ORG_ID, store.toStrAttr("org-a"), OrgMemberDAO.PERSON_ID, store.toStrAttr("p1"))));
        store.putItem(b -> b.tableName(OrgMemberDAO.ORG_MEMBERS_TABLE).item(Map.of(
                OrgMemberDAO.ORG_ID, store.toStrAttr("org-b"), OrgMemberDAO.PERSON_ID, store.toStrAttr("p2"))));

        final List<Map<String, AttributeValue>> rows =
                store.queryPartition(OrgMemberDAO.ORG_MEMBERS_TABLE, OrgMemberDAO.ORG_ID, "org-a");

        assertEquals(rows.size(), 1);
        assertEquals(rows.get(0).get(OrgMemberDAO.PERSON_ID).s(), "p1");
        assertTrue(store.queryPartition(OrgMemberDAO.ORG_MEMBERS_TABLE, OrgMemberDAO.ORG_ID, "org-c").isEmpty());
    }

    @Test
    public void sortListReturnsSortedCopy() {
        final List<Integer> original = Arrays.asList(3, 1, 4, 1, 5, 9);
        final List<Integer> sorted = persistence.sortList(original, Comparator.naturalOrder());
        assertEquals(sorted, List.of(1, 1, 3, 4, 5, 9));
        // Original should be unchanged
        assertEquals(original, Arrays.asList(3, 1, 4, 1, 5, 9));
    }

    @Test
    public void sortListWithEmptyCollection() {
        final List<String> result = persistence.sortList(List.<String>of(), Comparator.naturalOrder());
        assertTrue(result.isEmpty());
    }

    @Test
    public void sortListWithCustomComparator() {
        final List<String> items = List.of("banana", "apple", "cherry");
        final List<String> sorted = persistence.sortList(items, Comparator.<String>reverseOrder());
        assertEquals(sorted, List.of("cherry", "banana", "apple"));
    }
    }

