package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.CacheSupport;
import org.paulsens.trip.cache.PartitionCache;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.WaiverAcceptance;
import org.paulsens.trip.model.WaiverDocument;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

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
@Slf4j
public class WaiverDAO {
    /** Package-visible so {@link InMemoryPersistence} can register the tables for local mode. */
    static final String WAIVERS_TABLE = "org_waivers";
    static final String ACCEPTANCES_TABLE = "waiver_acceptances";
    static final String ORG_ID = "orgId";
    static final String ID = "id";
    static final String TRIP_ID = "tripId";
    static final String SK = "sk";
    static final String VERSION_ATTR = "version";
    private static final String CONTENT = "content";
    /** Below DynamoDB's 400 KB item cap with room for the key attributes; every version counts toward it. */
    static final int MAX_DOCUMENT_BYTES = 350_000;

    private final ObjectMapper mapper;
    private final Persistence persistence;
    private final PartitionCache<String, WaiverDocument> cache;

    protected WaiverDAO(final ObjectMapper mapper, final Persistence persistence, final CacheClient cacheClient) {
        this.mapper = mapper;
        this.persistence = persistence;
        this.cache = PartitionCache.<String, WaiverDocument>builder()
                .cache(cacheClient)
                .keyPrefix(CacheKeys.WAIVER_PREFIX)
                .softRevalidate(CacheSupport.softRevalidateEnabled(cacheClient))
                .idGetter(WaiverDocument::getId)
                .idFormatter(id -> id)
                .serializer(this::toJson)
                .deserializer(this::parseDocument)
                .order(Comparator.comparing(WaiverDAO::sortTitle))
                .build();
    }

    // ================================================================== documents

    /**
     * Conditionally persists the document and, on success, leaves its version bumped and writes through the
     * cache. On a version conflict the version is restored and the exception propagates.
     *
     * @throws IllegalArgumentException when the serialized row would not fit the item cap.
     */
    protected Boolean saveDocument(final WaiverDocument doc) throws IOException {
        // Sized before the bump so a refused row keeps the version it was loaded with.
        final int size = mapper.writeValueAsString(doc).length();
        if (size > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("The document '" + doc.getTitle() + "' is too large to store ("
                    + size + " bytes with every version; max " + MAX_DOCUMENT_BYTES + ").");
        }
        final long expected = doc.getVersion();
        doc.setVersion(expected + 1);
        final Map<String, AttributeValue> item = new HashMap<>();
        item.put(ORG_ID, str(doc.getOrgId()));
        item.put(ID, str(doc.getId()));
        item.put(CONTENT, str(mapper.writeValueAsString(doc)));
        item.put(VERSION_ATTR, AttributeValue.builder().n(Long.toString(doc.getVersion())).build());
        try {
            final boolean saved = persistence.putItem(b -> versionedPut(b, item, expected))
                    .sdkHttpResponse().isSuccessful();
            if (saved) {
                cache.put(doc.getOrgId(), doc);
            } else {
                doc.setVersion(expected);
            }
            return saved;
        } catch (final ConditionalCheckFailedException ex) {
            doc.setVersion(expected);
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

    private void versionedPut(final PutItemRequest.Builder b, final Map<String, AttributeValue> item,
            final long expected) {
        b.tableName(WAIVERS_TABLE).item(item);
        if (expected == 0L) {
            b.conditionExpression("attribute_not_exists(#i)").expressionAttributeNames(Map.of("#i", ID));
        } else {
            b.conditionExpression("#v = :expected")
                    .expressionAttributeNames(Map.of("#v", VERSION_ATTR))
                    .expressionAttributeValues(Map.of(
                            ":expected", AttributeValue.builder().n(Long.toString(expected)).build()));
        }
    }

    private List<WaiverDocument> loadDocuments(final String orgId) {
        return persistence.queryAll(qb -> partitionQuery(qb, WAIVERS_TABLE, ORG_ID, orgId)).stream()
                .map(item -> item.get(CONTENT))
                .filter(Objects::nonNull)
                .map(content -> parseDocument(content.s()))
                .filter(Objects::nonNull)
                .toList();
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
        return persistence.queryAll(qb -> partitionQuery(qb, ACCEPTANCES_TABLE, TRIP_ID, tripId)).stream()
                .map(item -> item.get(CONTENT))
                .filter(Objects::nonNull)
                .map(content -> parseAcceptance(content.s()))
                .filter(Objects::nonNull)
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

    private static void partitionQuery(final QueryRequest.Builder qb, final String table, final String pkName,
            final String pkValue) {
        qb.tableName(table)
                .keyConditionExpression("#p = :p")
                .expressionAttributeNames(Map.of("#p", pkName))
                .expressionAttributeValues(Map.of(":p", AttributeValue.builder().s(pkValue).build()));
    }

    private static AttributeValue str(final String value) {
        return AttributeValue.builder().s(value).build();
    }

    private WaiverDocument parseDocument(final String json) {
        try {
            return mapper.readValue(json, WaiverDocument.class);
        } catch (final IOException ex) {
            log.error("Unable to parse waiver document: " + json, ex);
            return null;
        }
    }

    private WaiverAcceptance parseAcceptance(final String json) {
        try {
            return mapper.readValue(json, WaiverAcceptance.class);
        } catch (final IOException ex) {
            log.error("Unable to parse waiver acceptance: " + json, ex);
            return null;
        }
    }

    private String toJson(final WaiverDocument doc) {
        try {
            return mapper.writeValueAsString(doc);
        } catch (final IOException ex) {
            log.error("Unable to serialize waiver document: " + doc.getId(), ex);
            return null;
        }
    }
}
