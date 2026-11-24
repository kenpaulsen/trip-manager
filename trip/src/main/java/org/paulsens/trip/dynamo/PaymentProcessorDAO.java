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
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.PaymentProcessorConfig;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * Payment-processor configs, partitioned by owning org (PK orgId, SK id) -- the partition IS the tenancy
 * boundary. Optimistic-version conditional saves like {@link FamilyDAO} (these rows steer money; a lost admin
 * race must surface, never merge silently). Deletes exist but the command layer only offers them to site
 * admins -- disabling is the normal retirement path, because a config referenced by historical payments
 * should keep resolving.
 */
public class PaymentProcessorDAO {
    /** Package-visible so {@link InMemoryPersistence} can register the table for local mode. */
    static final String PROCESSORS_TABLE = "payment_processors";
    static final String ORG_ID = "orgId";
    static final String CONFIG_ID = "id";
    static final String VERSION_ATTR = VersionedWrites.VERSION;
    private static final String CONTENT = "content";

    private final ObjectMapper mapper;
    private final JsonRows rows;
    private final Persistence persistence;
    private final PartitionCache<String, PaymentProcessorConfig> cache;

    protected PaymentProcessorDAO(final ObjectMapper mapper, final Persistence persistence,
            final CacheClient cacheClient) {
        this.mapper = mapper;
        this.rows = new JsonRows(mapper);
        this.persistence = persistence;
        this.cache = PartitionCache.<String, PaymentProcessorConfig>builder()
                .cache(cacheClient)
                .keyPrefix(CacheKeys.PROCESSOR_PREFIX)
                .softRevalidate(CacheSupport.softRevalidateEnabled(cacheClient))
                .idGetter(config -> config.getId().getValue())
                .idFormatter(id -> id)
                .serializer(value -> rows.toJson(value))
                .deserializer(json -> rows.parse(PaymentProcessorConfig.class, json))
                .order(Comparator.comparing(config -> config.getId().getValue()))
                .build();
    }

    /**
     * Conditionally persist (create at version 0, else replace that exact version); bumps the object's
     * version on success; on a lost race restores it, puts the STORED row into the cache
     * ({@link VersionedWrites#recacheStored}) and rethrows -- the {@link FamilyDAO} contract.
     */
    protected Boolean saveConfig(final PaymentProcessorConfig config) throws IOException {
        final long expected = config.getVersion();
        config.setVersion(expected + 1);
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(ORG_ID, AttributeValue.builder().s(config.getOrgId().getValue()).build());
        map.put(CONFIG_ID, AttributeValue.builder().s(config.getId().getValue()).build());
        map.put(CONTENT, AttributeValue.builder().s(mapper.writeValueAsString(config)).build());
        map.put(VERSION_ATTR, AttributeValue.builder().n(Long.toString(config.getVersion())).build());
        try {
            final boolean saved = persistence.putItem(
                    b -> VersionedWrites.conditionalPut(b, PROCESSORS_TABLE, map, CONFIG_ID, expected))
                    .sdkHttpResponse().isSuccessful();
            if (saved) {
                cache.put(config.getOrgId().getValue(), config);
            } else {
                config.setVersion(expected);
            }
            return saved;
        } catch (final ConditionalCheckFailedException ex) {
            config.setVersion(expected);
            VersionedWrites.recacheStored(persistence, PROCESSORS_TABLE,
                    Map.of(ORG_ID, map.get(ORG_ID), CONFIG_ID, map.get(CONFIG_ID)),
                    json -> rows.parse(PaymentProcessorConfig.class, json),
                    stored -> cache.put(config.getOrgId().getValue(), stored));
            throw ex;
        }
    }

    protected List<PaymentProcessorConfig> getConfigs(final Organization.Id orgId) {
        return cache.getAll(orgId.getValue(), () -> loadConfigs(orgId));
    }

    protected Optional<PaymentProcessorConfig> getConfig(
            final Organization.Id orgId, final PaymentProcessorConfig.Id configId) {
        if (orgId == null || configId == null) {
            return Optional.empty();
        }
        return cache.getOne(orgId.getValue(), configId.getValue(), () -> loadConfigs(orgId));
    }

    protected Boolean deleteConfig(final Organization.Id orgId, final PaymentProcessorConfig.Id configId) {
        final Map<String, AttributeValue> key = Map.of(
                ORG_ID, AttributeValue.builder().s(orgId.getValue()).build(),
                CONFIG_ID, AttributeValue.builder().s(configId.getValue()).build());
        final boolean deleted = persistence.deleteItem(b -> b.tableName(PROCESSORS_TABLE).key(key))
                .sdkHttpResponse().isSuccessful();
        return deleted && cache.remove(orgId.getValue(), configId.getValue());
    }

    private List<PaymentProcessorConfig> loadConfigs(final Organization.Id orgId) {
        return rows.parseAll(PaymentProcessorConfig.class,
                persistence.queryPartition(PROCESSORS_TABLE, ORG_ID, orgId.getValue()));
    }
}
