package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.CacheSupport;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.cache.PartitionScanCache;
import org.paulsens.trip.model.Config;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Runtime settings, stored one row per setting in the {@code config} table.
 *
 * <p>Unlike the other DAOs there is no partitioning to do: the table is tiny (tens of rows) and read during
 * ordinary page renders, so the whole thing lives in ONE cache hash under a fixed partition. A read is a single
 * {@code HGETALL}; a save invalidates that hash, so every running instance sees the change immediately rather
 * than after a redeploy -- which is the entire reason this table exists.
 *
 * <p>Reads never throw. A missing row, an unreachable table, or an unparseable value all resolve to
 * {@link Optional#empty()} so the caller falls back to its compiled-in default. A settings table that is
 * unavailable must degrade to "the defaults the code shipped with", never to a broken page.
 */
@Slf4j
public class ConfigDAO {
    static final String NAME = "name";
    private static final String CONTENT = "content";
    static final String CONFIG_TABLE = "config";

    private final ObjectMapper mapper;
    private final JsonRows rows;
    private final Persistence persistence;
    private final PartitionScanCache<Config> cache;

    protected ConfigDAO(final ObjectMapper mapper, final Persistence persistence) {
        this(mapper, persistence, new InMemoryCacheClient());
    }

    protected ConfigDAO(final ObjectMapper mapper, final Persistence persistence, final CacheClient cacheClient) {
        this.mapper = mapper;
        this.rows = new JsonRows(mapper);
        this.persistence = persistence;
        this.cache = PartitionScanCache.<Config>builder()
                .cache(cacheClient)
                .keyPrefix(CacheKeys.CONFIG_PREFIX)
                .loadedKey(CacheKeys.CONFIG_LOADED)
                .softRevalidate(CacheSupport.softRevalidateEnabled(cacheClient))
                .loader(this::loadAllConfig)
                // One partition on purpose: the table is small and always wanted whole.
                .partitioner(config -> CacheKeys.CONFIG_PARTITION)
                .fielder(Config::getName)
                .serializer(value -> rows.toJson(value))
                .deserializer(json -> rows.parse(Config.class, json))
                .build();
    }

    protected Boolean saveConfig(final Config config) {
        if (config == null || config.getName() == null || config.getName().isBlank()) {
            log.warn("Refusing to save a config row with no name");
            return false;
        }
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(NAME, persistence.toStrAttr(config.getName()));
        try {
            map.put(CONTENT, persistence.toStrAttr(mapper.writeValueAsString(config)));
        } catch (final IOException ex) {
            final String error = "Unable to serialize config named: " + config.getName();
            log.warn(error);
            throw new IllegalStateException(error);
        }
        final boolean saved = persistence.putItem(b -> b.tableName(CONFIG_TABLE).item(map))
                .sdkHttpResponse().isSuccessful();
        return saved && cache.put(config);
    }

    /** Every setting, for the admin page. */
    protected List<Config> getAllConfig() {
        return cache.getPartition(CacheKeys.CONFIG_PARTITION);
    }

    /** One setting by name; served from the cached hash, or a point read on a miss. Never a table scan. */
    protected Optional<Config> getConfig(final String name) {
        if (name == null) {
            return Optional.empty();
        }
        return cache.getOne(CacheKeys.CONFIG_PARTITION, name, () -> pointReadConfig(name));
    }

    public void clearCache() {
        cache.invalidate();
    }

    private Optional<Config> pointReadConfig(final String name) {
        final Map<String, AttributeValue> key = Map.of(NAME, AttributeValue.builder().s(name).build());
        // A swallowing read on purpose: the caller has a default and the site must keep serving.
        return rows.tryGet(persistence, CONFIG_TABLE, key, Config.class);
    }

    private List<Config> loadAllConfig() {
        return rows.scanAll(persistence, CONFIG_TABLE, Config.class);
    }
}
