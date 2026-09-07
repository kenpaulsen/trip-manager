package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.CacheSupport;
import org.paulsens.trip.cache.PartitionCache;
import org.paulsens.trip.cache.PartitionScanCache;
import org.paulsens.trip.model.Accommodation;
import org.paulsens.trip.model.Reservation;
import org.paulsens.trip.model.ReservationOffer;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * The three lodging tables (one class, the {@link ChatDAO} precedent):
 * <ul>
 *   <li>{@code lodging_accommodations} (PK id) -- GLOBAL hotels, the one entity deliberately not org-owned
 *       (see {@link Accommodation}). Small (tens to hundreds of rows) and every picker wants the whole list,
 *       so it lives in one {@link PartitionScanCache} hash like organizations. No delete: a hotel with
 *       historical offers is retired by flag.</li>
 *   <li>{@code lodging_offers} and {@code lodging_reservations} (PK tripId, SK id) -- trip-partitioned, so
 *       every read that matters (the rooms page, the itinerary, recompute, the delete cascade) is one
 *       partition query. {@link PartitionCache} per trip.</li>
 * </ul>
 *
 * <p>All three carry {@link FamilyDAO}-style optimistic versions: offers and reservations steer money, and an
 * accommodation's whole inventory is one row, so a lost admin race must surface, never merge. The
 * accommodation save also refuses a row that would not fit DynamoDB's 400 KB item cap, before the store does.
 */
@Slf4j
public class LodgingDAO {
    /** Package-visible so {@link InMemoryPersistence} can register the tables for local mode. */
    static final String ACCOMMODATIONS_TABLE = "lodging_accommodations";
    static final String OFFERS_TABLE = "lodging_offers";
    static final String RESERVATIONS_TABLE = "lodging_reservations";
    static final String ID = "id";
    static final String TRIP_ID = "tripId";
    static final String VERSION_ATTR = "version";
    private static final String CONTENT = "content";
    /** Below DynamoDB's 400 KB item cap with room for the attribute names and the version. */
    static final int MAX_ACCOMMODATION_BYTES = 350_000;

    private final ObjectMapper mapper;
    private final Persistence persistence;
    private final PartitionScanCache<Accommodation> accommodations;
    private final PartitionCache<String, ReservationOffer> offers;
    private final PartitionCache<String, Reservation> reservations;

    protected LodgingDAO(final ObjectMapper mapper, final Persistence persistence, final CacheClient cacheClient) {
        this.mapper = mapper;
        this.persistence = persistence;
        final boolean soft = CacheSupport.softRevalidateEnabled(cacheClient);
        this.accommodations = PartitionScanCache.<Accommodation>builder()
                .cache(cacheClient)
                .keyPrefix(CacheKeys.LODGING_ACC_PREFIX)
                .loadedKey(CacheKeys.LODGING_ACC_LOADED)
                .softRevalidate(soft)
                .loader(this::loadAllAccommodations)
                .partitioner(acc -> CacheKeys.LODGING_ACC_PARTITION)
                .fielder(acc -> acc.getId().getValue())
                .serializer(this::toJson)
                .deserializer(json -> parse(json, Accommodation.class))
                .build();
        this.offers = PartitionCache.<String, ReservationOffer>builder()
                .cache(cacheClient)
                .keyPrefix(CacheKeys.LODGING_OFFER_PREFIX)
                .softRevalidate(soft)
                .idGetter(offer -> offer.getId().getValue())
                .idFormatter(id -> id)
                .serializer(this::toJson)
                .deserializer(json -> parse(json, ReservationOffer.class))
                .order(Comparator.comparing(offer -> offer.getId().getValue()))
                .build();
        this.reservations = PartitionCache.<String, Reservation>builder()
                .cache(cacheClient)
                .keyPrefix(CacheKeys.LODGING_RES_PREFIX)
                .softRevalidate(soft)
                .idGetter(res -> res.getId().getValue())
                .idFormatter(id -> id)
                .serializer(this::toJson)
                .deserializer(json -> parse(json, Reservation.class))
                .order(Comparator.comparing(res -> res.getId().getValue()))
                .build();
    }

    // ------------------------------------------------------------------ accommodations

    /**
     * Conditionally persist (create at version 0, else replace that exact version); bumps the object's
     * version on success, restores it and rethrows on a lost race -- the {@link FamilyDAO} contract.
     *
     * @throws IllegalArgumentException when the serialized row would not fit the DynamoDB item cap.
     */
    protected Boolean saveAccommodation(final Accommodation acc) throws IOException {
        final String json = mapper.writeValueAsString(acc);
        if (json.length() > MAX_ACCOMMODATION_BYTES) {
            throw new IllegalArgumentException("Accommodation " + acc.getId().getValue() + " is too large to "
                    + "store (" + json.length() + " bytes; max " + MAX_ACCOMMODATION_BYTES + "). Split the "
                    + "property or trim room notes.");
        }
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(ID, AttributeValue.builder().s(acc.getId().getValue()).build());
        return versionedPut(acc, acc.getVersion(), acc::setVersion, map, ACCOMMODATIONS_TABLE,
                () -> accommodations.put(acc));
    }

    protected Optional<Accommodation> getAccommodation(final Accommodation.Id id) {
        if (id == null) {
            return Optional.empty();
        }
        return accommodations.getOne(CacheKeys.LODGING_ACC_PARTITION, id.getValue(), () -> pointRead(id));
    }

    /** Every accommodation (retired ones included), unsorted -- the command layer filters and orders. */
    protected List<Accommodation> getAccommodations() {
        return accommodations.getPartition(CacheKeys.LODGING_ACC_PARTITION);
    }

    // ------------------------------------------------------------------ offers

    protected Boolean saveOffer(final ReservationOffer offer) throws IOException {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(TRIP_ID, AttributeValue.builder().s(offer.getTripId()).build());
        map.put(ID, AttributeValue.builder().s(offer.getId().getValue()).build());
        return versionedPut(offer, offer.getVersion(), offer::setVersion, map, OFFERS_TABLE,
                () -> offers.put(offer.getTripId(), offer));
    }

    protected List<ReservationOffer> getOffers(final String tripId) {
        if (tripId == null) {
            return List.of();
        }
        return offers.getAll(tripId, () -> loadRows(OFFERS_TABLE, tripId, ReservationOffer.class));
    }

    protected Optional<ReservationOffer> getOffer(final String tripId, final ReservationOffer.Id id) {
        if (tripId == null || id == null) {
            return Optional.empty();
        }
        return offers.getOne(tripId, id.getValue(), () -> loadRows(OFFERS_TABLE, tripId, ReservationOffer.class));
    }

    protected Boolean deleteOffer(final String tripId, final ReservationOffer.Id id) {
        final boolean deleted = deleteRow(OFFERS_TABLE, tripId, id.getValue());
        return deleted && offers.remove(tripId, id.getValue());
    }

    // ------------------------------------------------------------------ reservations

    protected Boolean saveReservation(final Reservation res) throws IOException {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(TRIP_ID, AttributeValue.builder().s(res.getTripId()).build());
        map.put(ID, AttributeValue.builder().s(res.getId().getValue()).build());
        return versionedPut(res, res.getVersion(), res::setVersion, map, RESERVATIONS_TABLE,
                () -> reservations.put(res.getTripId(), res));
    }

    protected List<Reservation> getReservations(final String tripId) {
        if (tripId == null) {
            return List.of();
        }
        return reservations.getAll(tripId, () -> loadRows(RESERVATIONS_TABLE, tripId, Reservation.class));
    }

    protected Optional<Reservation> getReservation(final String tripId, final Reservation.Id id) {
        if (tripId == null || id == null) {
            return Optional.empty();
        }
        return reservations.getOne(tripId, id.getValue(),
                () -> loadRows(RESERVATIONS_TABLE, tripId, Reservation.class));
    }

    /**
     * The trip-delete cascade: every offer and reservation row of the trip, read RAW from both partitions
     * (not through the caches) so rows the cache never saw go too. Returns the number of rows deleted.
     */
    protected int deleteAllForTrip(final String tripId) {
        int deleted = 0;
        for (final String id : rawIds(OFFERS_TABLE, tripId)) {
            deleteRow(OFFERS_TABLE, tripId, id);
            offers.remove(tripId, id);
            deleted++;
        }
        for (final String id : rawIds(RESERVATIONS_TABLE, tripId)) {
            deleteRow(RESERVATIONS_TABLE, tripId, id);
            reservations.remove(tripId, id);
            deleted++;
        }
        offers.invalidate(tripId);
        reservations.invalidate(tripId);
        return deleted;
    }

    public void clearCache() {
        accommodations.invalidate();
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * The shared conditional put: bump the object's version, serialize it AT that version, create only at
     * version 0 else replace that exact stored version; on success write through the cache, on a lost race
     * restore the version and rethrow (the {@link FamilyDAO} contract).
     */
    private Boolean versionedPut(final Object row, final long expected, final Consumer<Long> versionSetter,
            final Map<String, AttributeValue> keyAttrs, final String table, final Runnable cachePut)
            throws IOException {
        versionSetter.accept(expected + 1);
        final Map<String, AttributeValue> map = new HashMap<>(keyAttrs);
        map.put(VERSION_ATTR, AttributeValue.builder().n(Long.toString(expected + 1)).build());
        map.put(CONTENT, AttributeValue.builder().s(mapper.writeValueAsString(row)).build());
        try {
            final boolean saved = persistence.putItem(b -> conditionalPut(b, table, map, expected))
                    .sdkHttpResponse().isSuccessful();
            if (saved) {
                cachePut.run();
            } else {
                versionSetter.accept(expected);
            }
            return saved;
        } catch (final ConditionalCheckFailedException ex) {
            versionSetter.accept(expected);
            throw ex;
        }
    }

    private void conditionalPut(final PutItemRequest.Builder b, final String table,
            final Map<String, AttributeValue> map, final long expected) {
        b.tableName(table).item(map);
        if (expected == 0L) {
            b.conditionExpression("attribute_not_exists(#i)").expressionAttributeNames(Map.of("#i", ID));
        } else {
            b.conditionExpression("#v = :expected")
                    .expressionAttributeNames(Map.of("#v", VERSION_ATTR))
                    .expressionAttributeValues(Map.of(
                            ":expected", AttributeValue.builder().n(Long.toString(expected)).build()));
        }
    }

    private boolean deleteRow(final String table, final String tripId, final String id) {
        final Map<String, AttributeValue> key = Map.of(
                TRIP_ID, AttributeValue.builder().s(tripId).build(),
                ID, AttributeValue.builder().s(id).build());
        return persistence.deleteItem(b -> b.tableName(table).key(key)).sdkHttpResponse().isSuccessful();
    }

    private Set<String> rawIds(final String table, final String tripId) {
        final Set<String> ids = new LinkedHashSet<>();
        for (final Map<String, AttributeValue> row : persistence.queryAll(qb -> byTripId(qb, table, tripId))) {
            final AttributeValue id = row.get(ID);
            if (id != null) {
                ids.add(id.s());
            }
        }
        return ids;
    }

    private <T> List<T> loadRows(final String table, final String tripId, final Class<T> type) {
        return persistence.queryAll(qb -> byTripId(qb, table, tripId)).stream()
                .map(item -> item.get(CONTENT))
                .filter(content -> content != null)
                .map(content -> parse(content.s(), type))
                .filter(row -> row != null)
                .toList();
    }

    private void byTripId(final QueryRequest.Builder qb, final String table, final String tripId) {
        qb.tableName(table)
                .keyConditionExpression("tripId = :c")
                .expressionAttributeValues(Map.of(":c", AttributeValue.builder().s(tripId).build()));
    }

    private Optional<Accommodation> pointRead(final Accommodation.Id id) {
        final Map<String, AttributeValue> key = Map.of(ID, AttributeValue.builder().s(id.getValue()).build());
        try {
            final AttributeValue content = persistence.getItem(
                    b -> b.key(key).tableName(ACCOMMODATIONS_TABLE).build()).item().get(CONTENT);
            return Optional.ofNullable(content == null ? null : parse(content.s(), Accommodation.class));
        } catch (final RuntimeException ex) {
            log.debug("LodgingDAO: unable to read accommodation ({})", id.getValue(), ex);
            return Optional.empty();
        }
    }

    private List<Accommodation> loadAllAccommodations() {
        return persistence.scanAll(b -> b.consistentRead(false).limit(1000).tableName(ACCOMMODATIONS_TABLE).build())
                .stream()
                .map(item -> item.get(CONTENT))
                .filter(content -> content != null)
                .map(content -> parse(content.s(), Accommodation.class))
                .filter(acc -> acc != null)
                .toList();
    }

    private <T> T parse(final String json, final Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (final IOException ex) {
            log.error("Unable to parse " + type.getSimpleName() + " record: " + json, ex);
            return null;
        }
    }

    private String toJson(final Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (final IOException ex) {
            log.error("Unable to serialize lodging row: " + value, ex);
            return null;
        }
    }
}
