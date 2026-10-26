package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.CacheSupport;
import org.paulsens.trip.cache.PointCache;
import org.paulsens.trip.model.TripExpenseReport;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * {@code trip_expense_reports} (PK tripId): ONE {@link TripExpenseReport} per trip, currencies and lines inline,
 * behind a {@link PointCache}. The {@link BudgetDAO} shape exactly, for the same reasons: point reads only, and
 * a versioned conditional put per save (version 0 may only CREATE, any other version only replaces that exact
 * stored version; a lost race throws {@link ConditionalCheckFailedException} with the version restored), so
 * two people editing one report can never half-merge it. A row that would not fit DynamoDB's 400 KB item cap
 * is refused before the store sees it.
 */
@Slf4j
public class ExpenseReportDAO {
    static final String EXPENSES_TABLE = "trip_expense_reports";
    static final String TRIP_ID = "tripId";
    static final String VERSION_ATTR = "version";
    private static final String CONTENT = "content";
    /** Below DynamoDB's 400 KB item cap with room for the attribute names and the version. */
    static final int MAX_REPORT_BYTES = 350_000;

    private final ObjectMapper mapper;
    private final Persistence persistence;
    private final PointCache<TripExpenseReport> cache;

    protected ExpenseReportDAO(final ObjectMapper mapper, final Persistence persistence,
            final CacheClient cacheClient) {
        this.mapper = mapper;
        this.persistence = persistence;
        this.cache = PointCache.<TripExpenseReport>builder()
                .cache(cacheClient)
                .keyPrefix(CacheKeys.EXPENSE_PREFIX)
                .softRevalidate(CacheSupport.softRevalidateEnabled(cacheClient))
                .serializer(this::toJson)
                .deserializer(this::parseReport)
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
            final boolean saved = persistence.putItem(b -> conditionalPut(b, map, expected))
                    .sdkHttpResponse().isSuccessful();
            if (saved) {
                cache.put(report.getTripId(), report);
            } else {
                report.setVersion(expected);
            }
            return saved;
        } catch (final ConditionalCheckFailedException ex) {
            report.setVersion(expected);
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

    /** Version 0 may only create; any other version may only replace that exact stored version. */
    private static void conditionalPut(final PutItemRequest.Builder b, final Map<String, AttributeValue> item,
            final long expected) {
        b.tableName(EXPENSES_TABLE).item(item);
        if (expected == 0L) {
            b.conditionExpression("attribute_not_exists(#i)").expressionAttributeNames(Map.of("#i", TRIP_ID));
        } else {
            b.conditionExpression("#v = :expected")
                    .expressionAttributeNames(Map.of("#v", VERSION_ATTR))
                    .expressionAttributeValues(Map.of(
                            ":expected", AttributeValue.builder().n(Long.toString(expected)).build()));
        }
    }

    private TripExpenseReport loadReport(final String tripId) {
        final Map<String, AttributeValue> key = Map.of(TRIP_ID, AttributeValue.builder().s(tripId).build());
        final AttributeValue content =
                persistence.getItem(b -> b.key(key).tableName(EXPENSES_TABLE).build()).item().get(CONTENT);
        return (content == null) ? null : parseReport(content.s());
    }

    private TripExpenseReport parseReport(final String json) {
        try {
            return mapper.readValue(json, TripExpenseReport.class);
        } catch (final IOException ex) {
            log.error("Unable to parse expense report record: " + json, ex);
            return null;
        }
    }

    private String toJson(final TripExpenseReport report) {
        try {
            return mapper.writeValueAsString(report);
        } catch (final IOException ex) {
            log.error("Unable to serialize expense report: " + report.getTripId(), ex);
            return null;
        }
    }
}
