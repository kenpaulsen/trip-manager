package org.paulsens.trip.dynamo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * The stored JSON of the rows that hold hand-authored page HTML, read and rewritten AS TEXT: for one-time data
 * migrations only ({@code migrate.HtmlCleanCli}, medjugorje#158), never for a page or the API.
 *
 * <p>Why text and not the model objects: a migration must change the fields it means to and nothing else, and
 * must write a row back only if nobody saved it in between. Reading a row into its class and writing the object
 * out again can respell fields the migration never looked at, and a save through the DAO has no "only if
 * unchanged". So a row is read as the string it is stored as and replaced only while it still holds that string.
 *
 * <p>The caches are NOT touched here: a migration runs with the shared cache off, and its script drops the app's
 * caches afterwards (a caller with a cache of its own drops its entry itself). Four tables, each a key and one
 * {@code content} attribute: {@code trips}, {@code content} and {@code trip_events} keyed by {@code id}, and
 * {@code todo_items} by {@code tripId} and {@code dataId} (a row id here is {@link #todoRowId}).
 */
public final class RawRows {

    public static final String TRIPS = "trips";
    public static final String CONTENT_ROWS = "content";
    public static final String TODO_ITEMS = "todo_items";
    /** Trip events too: the note repair ({@code migrate.NoteElFlattenCli}) reads and rewrites them the same way. */
    static final String TRIP_EVENTS = "trip_events";
    static final List<String> TABLES = List.of(TRIPS, CONTENT_ROWS, TODO_ITEMS, TRIP_EVENTS);

    private static final String ID = "id";
    private static final String TRIP_ID = "tripId";
    private static final String DATA_ID = "dataId";
    private static final String CONTENT = "content";
    /** Never part of a trip id (a UUID), so the first one in a row id ends the trip's part. */
    private static final char TODO_ID_SEPARATOR = '/';

    private final Persistence persistence;

    RawRows(final Persistence persistence) {
        this.persistence = persistence;
    }

    /** Every row's stored JSON by row id, straight from the table with a consistent read; sorted by id. */
    Map<String, String> read(final String table) {
        requireKnown(table);
        final Map<String, String> rows = new TreeMap<>();
        for (final Map<String, AttributeValue> item
                : persistence.scanAll(b -> b.tableName(table).consistentRead(true).build())) {
            final String id = rowId(table, item);
            final AttributeValue content = item.get(CONTENT);
            if (id != null && content != null && content.s() != null) {
                rows.put(id, content.s());
            }
        }
        return rows;
    }

    /**
     * Writes {@code replacement} as the row's stored JSON only if the row still holds {@code expected}. False,
     * writing nothing, when it does not: someone saved the row since it was read, and a migration must never
     * overwrite an edit it did not see.
     */
    boolean replace(final String table, final String rowId, final String expected, final String replacement) {
        requireKnown(table);
        final Map<String, AttributeValue> item = new HashMap<>(key(table, rowId));
        item.put(CONTENT, persistence.toStrAttr(replacement));
        try {
            persistence.putItem(b -> b.tableName(table).item(item)
                    .conditionExpression("#c = :expected")
                    .expressionAttributeNames(Map.of("#c", CONTENT))
                    .expressionAttributeValues(Map.of(":expected", persistence.toStrAttr(expected))));
        } catch (final ConditionalCheckFailedException ex) {
            return false;
        }
        return true;
    }

    /** A to-do row's id here: its two key parts, the trip's id first. */
    public static String todoRowId(final String tripId, final String dataId) {
        return tripId + TODO_ID_SEPARATOR + dataId;
    }

    /** What every to-do row id of one trip starts with. */
    public static String todoRowIdPrefix(final String tripId) {
        return tripId + TODO_ID_SEPARATOR;
    }

    private static void requireKnown(final String table) {
        if (!TABLES.contains(table)) {
            throw new IllegalArgumentException("Not a table this migration may read or write: " + table);
        }
    }

    private static String rowId(final String table, final Map<String, AttributeValue> item) {
        if (!TODO_ITEMS.equals(table)) {
            return item.get(ID) == null ? null : item.get(ID).s();
        }
        final AttributeValue trip = item.get(TRIP_ID);
        final AttributeValue data = item.get(DATA_ID);
        return trip == null || data == null ? null : todoRowId(trip.s(), data.s());
    }

    private Map<String, AttributeValue> key(final String table, final String rowId) {
        if (!TODO_ITEMS.equals(table)) {
            return Map.of(ID, persistence.toStrAttr(rowId));
        }
        final int slash = rowId.indexOf(TODO_ID_SEPARATOR);
        if (slash <= 0) {
            throw new IllegalArgumentException("A to-do row id is tripId/dataId, not: " + rowId);
        }
        return Map.of(TRIP_ID, persistence.toStrAttr(rowId.substring(0, slash)),
                DATA_ID, persistence.toStrAttr(rowId.substring(slash + 1)));
    }
}
