package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.CacheSupport;
import org.paulsens.trip.cache.PointCache;
import org.paulsens.trip.model.TripExpenseReport;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * {@code trip_expense_reports} (PK tripId): ONE {@link TripExpenseReport} per trip, currencies and lines inline,
 * behind a {@link PointCache}. The {@link BudgetDAO} shape exactly, for the same reasons: point reads only, and
 * a versioned conditional put per save (version 0 may only CREATE, any other version only replaces that exact
 * stored version; a lost race throws {@link ConditionalCheckFailedException} with the version restored), so
 * two people editing one report can never half-merge it. A row that would not fit DynamoDB's 400 KB item cap
 * is refused before the store sees it.
 */
public class ExpenseReportDAO {
    static final String EXPENSES_TABLE = "trip_expense_reports";
    static final String TRIP_ID = "tripId";
    static final String VERSION_ATTR = VersionedWrites.VERSION;
    private static final String CONTENT = "content";
    /** Below DynamoDB's 400 KB item cap with room for the attribute names and the version. */
    static final int MAX_REPORT_BYTES = 350_000;

    private final ObjectMapper mapper;
    private final JsonRows rows;
    private final Persistence persistence;
    private final PointCache<TripExpenseReport> cache;

    protected ExpenseReportDAO(final ObjectMapper mapper, final Persistence persistence,
            final CacheClient cacheClient) {
        this.mapper = mapper;
        this.rows = new JsonRows(mapper);
        this.persistence = persistence;
        this.cache = PointCache.<TripExpenseReport>builder()
                .cache(cacheClient)
                .keyPrefix(CacheKeys.EXPENSE_PREFIX)
                .softRevalidate(CacheSupport.softRevalidateEnabled(cacheClient))
                .serializer(value -> rows.toJson(value))
                .deserializer(json -> rows.parse(TripExpenseReport.class, json))
                .build();
    }

    /**
     * Conditionally persists the report and, on success, leaves its version bumped and writes through the
     * cache. On a version conflict the version is restored and the exception propagates.
     *
     * @throws IllegalArgumentException when the serialized row would not fit the DynamoDB item cap.
     */
    protected Boolean saveReport(final TripExpenseReport report) throws IOException {
        // Sized before the bump so a refused row keeps the version it was loaded with.
        final int size = mapper.writeValueAsString(report).length();
        if (size > MAX_REPORT_BYTES) {
            throw new IllegalArgumentException("The expense report for trip " + report.getTripId() + " is too "
                    + "large to store (" + size + " bytes; max " + MAX_REPORT_BYTES + ").");
        }
        final long expected = report.getVersion();
        report.setVersion(expected + 1);
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(TRIP_ID, AttributeValue.builder().s(report.getTripId()).build());
        map.put(CONTENT, AttributeValue.builder().s(mapper.writeValueAsString(report)).build());
        map.put(VERSION_ATTR, AttributeValue.builder().n(Long.toString(report.getVersion())).build());
        try {
            final boolean saved = persistence.putItem(
                    b -> VersionedWrites.conditionalPut(b, EXPENSES_TABLE, map, TRIP_ID, expected))
                    .sdkHttpResponse().isSuccessful();
            if (saved) {
                cache.put(report.getTripId(), report);
            } else {
                report.setVersion(expected);
            }
            return saved;
        } catch (final ConditionalCheckFailedException ex) {
            report.setVersion(expected);
            VersionedWrites.recacheStored(persistence, EXPENSES_TABLE, Map.of(TRIP_ID, map.get(TRIP_ID)),
                    json -> rows.parse(TripExpenseReport.class, json), stored -> cache.put(report.getTripId(), stored));
            throw ex;
        }
    }

    protected Optional<TripExpenseReport> getReport(final String tripId) {
        if (tripId == null || tripId.isBlank()) {
            return Optional.empty();
        }
        return cache.get(tripId, this::loadReport);
    }

    /** The trip-delete cascade's one call. No version condition: deleting twice is idempotent. */
    protected Boolean deleteReport(final String tripId) {
        final Map<String, AttributeValue> key = Map.of(TRIP_ID, AttributeValue.builder().s(tripId).build());
        final boolean deleted = persistence.deleteItem(b -> b.tableName(EXPENSES_TABLE).key(key))
                .sdkHttpResponse().isSuccessful();
        return cache.remove(tripId) && deleted;
    }

    private TripExpenseReport loadReport(final String tripId) {
        final Map<String, AttributeValue> key = Map.of(TRIP_ID, AttributeValue.builder().s(tripId).build());
        return rows.get(persistence, EXPENSES_TABLE, key, TripExpenseReport.class);
    }
}
