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
import org.paulsens.trip.model.TripBudget;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * {@code trip_budgets} (PK tripId): ONE {@link TripBudget} row per trip, lines inline, behind a
 * {@link PointCache}. Point reads only -- nothing lists budgets across trips.
 *
 * <p>Every save is the {@link FamilyDAO} conditional put: version 0 may only CREATE, any other version may only
 * replace that exact stored version, and a lost race throws {@link ConditionalCheckFailedException} with the
 * object's version restored, for the command layer to refuse as "saved by someone else". One put per save is
 * the point of the one-row shape: the refusal covers the whole sheet, never half of it. The save also refuses
 * a row that would not fit DynamoDB's 400 KB item cap, before the store does.
 */
@Slf4j
public class BudgetDAO {
    static final String BUDGETS_TABLE = "trip_budgets";
    static final String TRIP_ID = "tripId";
    static final String VERSION_ATTR = "version";
    private static final String CONTENT = "content";
    /** Below DynamoDB's 400 KB item cap with room for the attribute names and the version. */
    static final int MAX_BUDGET_BYTES = 350_000;

    private final ObjectMapper mapper;
    private final Persistence persistence;
    private final PointCache<TripBudget> cache;

    protected BudgetDAO(final ObjectMapper mapper, final Persistence persistence, final CacheClient cacheClient) {
        this.mapper = mapper;
        this.persistence = persistence;
        this.cache = PointCache.<TripBudget>builder()
                .cache(cacheClient)
                .keyPrefix(CacheKeys.BUDGET_PREFIX)
                .softRevalidate(CacheSupport.softRevalidateEnabled(cacheClient))
                .serializer(this::toJson)
                .deserializer(this::parseBudget)
                .build();
    }

    /**
     * Conditionally persists the budget and, on success, leaves its version bumped and writes through the
     * cache. On a version conflict the version is restored and the exception propagates.
     *
     * @throws IllegalArgumentException when the serialized row would not fit the DynamoDB item cap.
     */
    protected Boolean saveBudget(final TripBudget budget) throws IOException {
        // Sized before the bump so a refused row keeps the version it was loaded with.
        final int size = mapper.writeValueAsString(budget).length();
        if (size > MAX_BUDGET_BYTES) {
            throw new IllegalArgumentException("The budget for trip " + budget.getTripId() + " is too large to "
                    + "store (" + size + " bytes; max " + MAX_BUDGET_BYTES + ").");
        }
        final long expected = budget.getVersion();
        budget.setVersion(expected + 1);
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(TRIP_ID, AttributeValue.builder().s(budget.getTripId()).build());
        map.put(CONTENT, AttributeValue.builder().s(mapper.writeValueAsString(budget)).build());
        map.put(VERSION_ATTR, AttributeValue.builder().n(Long.toString(budget.getVersion())).build());
        try {
            final boolean saved = persistence.putItem(b -> {
                b.tableName(BUDGETS_TABLE).item(map);
                if (expected == 0L) {
                    b.conditionExpression("attribute_not_exists(#i)")
                            .expressionAttributeNames(Map.of("#i", TRIP_ID));
                } else {
                    b.conditionExpression("#v = :expected")
                            .expressionAttributeNames(Map.of("#v", VERSION_ATTR))
                            .expressionAttributeValues(Map.of(
                                    ":expected", AttributeValue.builder().n(Long.toString(expected)).build()));
                }
            }).sdkHttpResponse().isSuccessful();
            if (saved) {
                cache.put(budget.getTripId(), budget);
            } else {
                budget.setVersion(expected);
            }
            return saved;
        } catch (final ConditionalCheckFailedException ex) {
            budget.setVersion(expected);
            throw ex;
        }
    }

    protected Optional<TripBudget> getBudget(final String tripId) {
        if (tripId == null || tripId.isBlank()) {
            return Optional.empty();
        }
        return cache.get(tripId, this::loadBudget);
    }

    /** The trip-delete cascade's one call. No version condition: deleting twice is idempotent. */
    protected Boolean deleteBudget(final String tripId) {
        final Map<String, AttributeValue> key = Map.of(TRIP_ID, AttributeValue.builder().s(tripId).build());
        final boolean deleted = persistence.deleteItem(b -> b.tableName(BUDGETS_TABLE).key(key))
                .sdkHttpResponse().isSuccessful();
        return cache.remove(tripId) && deleted;
    }

    private TripBudget loadBudget(final String tripId) {
        final Map<String, AttributeValue> key = Map.of(TRIP_ID, AttributeValue.builder().s(tripId).build());
        final AttributeValue content =
                persistence.getItem(b -> b.key(key).tableName(BUDGETS_TABLE).build()).item().get(CONTENT);
        return (content == null) ? null : parseBudget(content.s());
    }

    private TripBudget parseBudget(final String json) {
        try {
            return mapper.readValue(json, TripBudget.class);
        } catch (final IOException ex) {
            log.error("Unable to parse budget record: " + json, ex);
            return null;
        }
    }

    private String toJson(final TripBudget budget) {
        try {
            return mapper.writeValueAsString(budget);
        } catch (final IOException ex) {
            log.error("Unable to serialize budget: " + budget.getTripId(), ex);
            return null;
        }
    }
}
