package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.CacheSupport;
import org.paulsens.trip.cache.PartitionCache;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.WaiverAcceptance;
import org.paulsens.trip.model.WaiverDocument;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * The two waiver tables (see {@code docs/waivers.md}).
 *
 * <ul>
 *   <li>{@code org_waivers} (PK orgId, SK id): an organization's documents, every published version inline,
 *       behind a per-org {@link PartitionCache} -- the {@link PaymentProcessorDAO} shape, the partition being
 *       the tenancy boundary. Saves are the {@link FamilyDAO} conditional put (version 0 only creates, any other
 *       version only replaces that exact stored version; a lost race throws with the version restored) and
 *       refuse a row over {@link #MAX_DOCUMENT_BYTES} before the store does.</li>
 *   <li>{@code waiver_acceptances} (PK tripId, SK personId#waiverId#version): the signatures. WRITE-ONCE
 *       ({@code attribute_not_exists}) and deliberately UNCACHED, like the audit table: every read decides
 *       whether somebody may register, a trip's partition is small, and a record that must never be lost
 *       has no business living in a cache whose local-mode twin IS the store. There is no delete.</li>
 * </ul>
 */
public class WaiverDAO {
    /** Package-visible so {@link InMemoryPersistence} can register the tables for local mode. */
    static final String WAIVERS_TABLE = "org_waivers";
    static final String ACCEPTANCES_TABLE = "waiver_acceptances";
    static final String ORG_ID = "orgId";
    static final String ID = "id";
    static final String TRIP_ID = "tripId";
    static final String SK = "sk";
    static final String VERSION_ATTR = VersionedWrites.VERSION;
    private static final String CONTENT = "content";
    /** Below DynamoDB's 400 KB item cap with room for the key attributes; every version counts toward it. */
    static final int MAX_DOCUMENT_BYTES = 350_000;

    private final ObjectMapper mapper;
    private final JsonRows rows;
    private final Persistence persistence;
    private final PartitionCache<String, WaiverDocument> cache;

    protected WaiverDAO(final ObjectMapper mapper, final Persistence persistence, final CacheClient cacheClient) {
        this.mapper = mapper;
        this.rows = new JsonRows(mapper);
        this.persistence = persistence;
        this.cache = PartitionCache.<String, WaiverDocument>builder()
                .cache(cacheClient)
                .keyPrefix(CacheKeys.WAIVER_PREFIX)
                .softRevalidate(CacheSupport.softRevalidateEnabled(cacheClient))
                .idGetter(WaiverDocument::getId)
                .idFormatter(id -> id)
                .serializer(value -> rows.toJson(value))
                .deserializer(json -> rows.parse(WaiverDocument.class, json))
                .order(Comparator.comparing(WaiverDAO::sortTitle))
                .build();
    }

    // ================================================================== documents

    /**
     * Conditionally persists the document and, on success, leaves its version bumped and writes through the
     * cache. On a version conflict the version is restored, the STORED row is put into the cache
     * ({@link VersionedWrites#recacheStored}) and the exception propagates.
     *
     * @throws IllegalArgumentException when the serialized row would not fit the item cap.
     */
    protected Boolean saveDocument(final WaiverDocument doc) throws IOException {
        // Sized before the bump so a refused row keeps the version it was loaded with.
        VersionedWrites.requireFits(mapper.writeValueAsString(doc), MAX_DOCUMENT_BYTES,
                size -> "The document '" + doc.getTitle() + "' is too large to store (" + size
                        + " bytes with every version; max " + MAX_DOCUMENT_BYTES + ").");
        final long expected = doc.getVersion();
        doc.setVersion(expected + 1);
        final Map<String, AttributeValue> item = new HashMap<>();
        item.put(ORG_ID, str(doc.getOrgId()));
        item.put(ID, str(doc.getId()));
        item.put(CONTENT, str(mapper.writeValueAsString(doc)));
        item.put(VERSION_ATTR, AttributeValue.builder().n(Long.toString(doc.getVersion())).build());
        try {
            final boolean saved = persistence.putItem(
                    b -> VersionedWrites.conditionalPut(b, WAIVERS_TABLE, item, ID, expected))
                    .sdkHttpResponse().isSuccessful();
            if (saved) {
                cache.put(doc.getOrgId(), doc);
            } else {
                doc.setVersion(expected);
            }
            return saved;
        } catch (final ConditionalCheckFailedException ex) {
            doc.setVersion(expected);
            VersionedWrites.recacheStored(persistence, WAIVERS_TABLE,
                    Map.of(ORG_ID, item.get(ORG_ID), ID, item.get(ID)), json -> rows.parse(WaiverDocument.class, json),
                    stored -> cache.put(doc.getOrgId(), stored));
            throw ex;
        }
    }

    protected List<WaiverDocument> getDocuments(final String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return List.of();
        }
        return cache.getAll(orgId, () -> loadDocuments(orgId));
    }

    /** The {@code (orgId, id)} composite read IS the tenancy check: a foreign id simply misses. */
    protected Optional<WaiverDocument> getDocument(final String orgId, final String id) {
        if (orgId == null || orgId.isBlank() || id == null || id.isBlank()) {
            return Optional.empty();
        }
        return cache.getOne(orgId, id, () -> loadDocuments(orgId));
    }

    private List<WaiverDocument> loadDocuments(final String orgId) {
        return rows.parseAll(WaiverDocument.class,
                persistence.queryPartition(WAIVERS_TABLE, ORG_ID, orgId));
    }

    private static String sortTitle(final WaiverDocument doc) {
        return (doc.getTitle() == null) ? "" : doc.getTitle().toLowerCase(java.util.Locale.ROOT);
    }

    // ================================================================== acceptances

    /**
     * Writes one acceptance, once. A second write of the same traveler, document and version is refused by the
     * store; the caller treats that as "already signed" (answers the stored row), never as an overwrite.
     *
     * @return true when this call created the row, false when one already existed.
     */
    protected boolean saveAcceptance(final WaiverAcceptance acceptance) throws IOException {
        final Map<String, AttributeValue> item = new HashMap<>();
        item.put(TRIP_ID, str(acceptance.getTripId()));
        item.put(SK, str(acceptance.getSortKey()));
        item.put(CONTENT, str(mapper.writeValueAsString(acceptance)));
        try {
            return persistence.putItem(b -> b.tableName(ACCEPTANCES_TABLE).item(item)
                    .conditionExpression("attribute_not_exists(#s)")
                    .expressionAttributeNames(Map.of("#s", SK)))
                    .sdkHttpResponse().isSuccessful();
        } catch (final ConditionalCheckFailedException ex) {
            return false;
        }
    }

    /** Every acceptance on a trip, in sort-key order (person, document, version). */
    protected List<WaiverAcceptance> getAcceptances(final String tripId) {
        if (tripId == null || tripId.isBlank()) {
            return List.of();
        }
        return rows.parseAll(WaiverAcceptance.class,
                persistence.queryPartition(ACCEPTANCES_TABLE, TRIP_ID, tripId))
                .stream()
                .sorted(Comparator.comparing(WaiverAcceptance::getSortKey))
                .toList();
    }

    /** One traveler's acceptances on a trip. */
    protected List<WaiverAcceptance> getAcceptances(final String tripId, final Person.Id personId) {
        if (personId == null) {
            return List.of();
        }
        return getAcceptances(tripId).stream().filter(a -> personId.equals(a.getPersonId())).toList();
    }

    // ================================================================== plumbing

    private static AttributeValue str(final String value) {
        return AttributeValue.builder().s(value).build();
    }
}
