package org.paulsens.trip.dynamo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * The one conflict recache every versioned DAO shares. What each DAO does with it is in that DAO's own test
 * ({@code aLostVersionCheckRefreshesTheCachedRow}, {@code theReReadAfterALostVersionCheckIsConsistent}).
 */
public class VersionedWritesTest {
    private static final String TABLE = FamilyDAO.FAMILY_TABLE;
    private static final Map<String, AttributeValue> KEY = Map.of("id", AttributeValue.builder().s("row-1").build());

    /**
     * The in-memory fake is always consistent, so only the request can show this: a default read may land on
     * a replica still at the losing version, and putting that into the cache replaces the winner's fresh entry.
     */
    @Test
    public void theStoreIsAskedForAStronglyConsistentReadOfThatRow() {
        final RecordingPersistence store = new RecordingPersistence();
        final List<String> cached = new ArrayList<>();

        VersionedWrites.recacheStored(store, TABLE, KEY, json -> json, cached::add);

        assertEquals(store.onlyConsistentReadOf(TABLE).key(), KEY);
        assertTrue(cached.isEmpty(), "a row the store does not hold puts nothing");
    }

    @Test
    public void theStoredRowIsParsedAndPutIntoTheCache() {
        final InMemoryPersistence store = new InMemoryPersistence();
        store.putItem(b -> b.tableName(TABLE).item(Map.of(
                "id", KEY.get("id"), "content", AttributeValue.builder().s("stored").build())));
        final List<String> cached = new ArrayList<>();

        VersionedWrites.recacheStored(store, TABLE, KEY, json -> "parsed " + json, cached::add);

        assertEquals(cached, List.of("parsed stored"));
    }

    /** A removal would be wrong (in local mode the cache is the store), so unreadable content changes nothing. */
    @Test
    public void contentTheParserCannotReadPutsNothing() {
        final InMemoryPersistence store = new InMemoryPersistence();
        store.putItem(b -> b.tableName(TABLE).item(Map.of(
                "id", KEY.get("id"), "content", AttributeValue.builder().s("{not json").build())));
        final List<String> cached = new ArrayList<>();

        VersionedWrites.<String>recacheStored(store, TABLE, KEY, json -> null, cached::add);

        assertTrue(cached.isEmpty());
    }

    /** The caller is about to rethrow the conflict; nothing here may replace it with a different failure. */
    @Test
    public void aFailingReadOrCachePutIsSwallowed() {
        final InMemoryPersistence store = new InMemoryPersistence();
        store.putItem(b -> b.tableName(TABLE).item(Map.of(
                "id", KEY.get("id"), "content", AttributeValue.builder().s("stored").build())));

        VersionedWrites.recacheStored(new UnreadableStore(), TABLE, KEY, json -> json, VersionedWritesTest::refuse);
        VersionedWrites.recacheStored(store, TABLE, KEY, json -> json, VersionedWritesTest::refuse);
    }

    private static void refuse(final String row) {
        throw new IllegalStateException("cache down, with " + row);
    }

    /** A store whose point reads fail. */
    private static final class UnreadableStore implements Persistence {
        @Override
        public GetItemResponse getItem(final Consumer<GetItemRequest.Builder> request) {
            throw new IllegalStateException("store down");
        }
    }
}
