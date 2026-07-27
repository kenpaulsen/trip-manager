package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.CacheSupport;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.cache.PartitionScanCache;
import org.paulsens.trip.model.MediaItem;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Metadata for managed media (the bytes live in S3; see MediaStack). One row per file.
 *
 * <p>Cached the same way as {@link ConfigDAO}: the table is small (tens of rows) and read on ordinary page
 * renders -- a page asking "what is in the homepage slot" must not turn into a DynamoDB query per visit -- so
 * the whole table lives in ONE cache hash. A save invalidates it for every instance at once, which is what
 * makes an upload appear on the site immediately rather than after a redeploy.
 *
 * <p>Deleting a row is a real delete, unlike the append-only audit trail: media is content, and removing a file
 * from the site has to actually remove it. The S3 object is deleted separately by the caller, and S3 versioning
 * keeps a recoverable copy.
 */
@Slf4j
public class MediaDAO {
    private static final String ID = "id";
    private static final String CONTENT = "content";
    private static final String MEDIA_TABLE = "media";

    private final ObjectMapper mapper;
    private final Persistence persistence;
    private final PartitionScanCache<MediaItem> cache;

    protected MediaDAO(final ObjectMapper mapper, final Persistence persistence) {
        this(mapper, persistence, new InMemoryCacheClient());
    }

    protected MediaDAO(final ObjectMapper mapper, final Persistence persistence, final CacheClient cacheClient) {
        this.mapper = mapper;
        this.persistence = persistence;
        this.cache = PartitionScanCache.<MediaItem>builder()
                .cache(cacheClient)
                .keyPrefix(CacheKeys.MEDIA_PREFIX)
                .loadedKey(CacheKeys.MEDIA_LOADED)
                .softRevalidate(CacheSupport.softRevalidateEnabled(cacheClient))
                .loader(this::loadAllMedia)
                // One partition: the table is small and pages want it whole (then filter by slot).
                .partitioner(item -> CacheKeys.MEDIA_PARTITION)
                .fielder(MediaItem::getId)
                .serializer(this::toJson)
                .deserializer(this::parseMedia)
                .build();
    }

    protected CompletableFuture<Boolean> saveMedia(final MediaItem item) {
        if (item == null || item.getId() == null || item.getId().isBlank()) {
            log.warn("Refusing to save a media row with no id");
            return CompletableFuture.completedFuture(false);
        }
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(ID, persistence.toStrAttr(item.getId()));
        try {
            map.put(CONTENT, persistence.toStrAttr(mapper.writeValueAsString(item)));
        } catch (final IOException ex) {
            final String error = "Unable to serialize media item: " + item.getId();
            log.warn(error);
            throw new IllegalStateException(error);
        }
        return persistence.putItem(b -> b.tableName(MEDIA_TABLE).item(map))
                .thenCompose(resp -> resp.sdkHttpResponse().isSuccessful()
                        ? cache.put(item)
                        : CompletableFuture.completedFuture(false));
    }

    /** Every media item, for the admin page. */
    protected CompletableFuture<List<MediaItem>> getAllMedia() {
        return cache.getPartition(CacheKeys.MEDIA_PARTITION);
    }

    /**
     * Items placed in the given slot, ordered by position then upload time. Filtered in memory on purpose: the
     * whole table is already one cached hash, so a slot query costs nothing beyond the filter and needs no GSI.
     */
    protected CompletableFuture<List<MediaItem>> getMediaInSlot(final String slot) {
        if (slot == null || slot.isBlank()) {
            return CompletableFuture.completedFuture(List.of());
        }
        final String wanted = slot.trim();
        return getAllMedia().thenApply(all -> all.stream()
                .filter(item -> wanted.equalsIgnoreCase(item.getSlot()))
                .sorted(Comparator.comparingInt(MediaItem::getPosition)
                        .thenComparing(MediaItem::getUploaded,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList());
    }

    protected CompletableFuture<Optional<MediaItem>> getMedia(final String id) {
        if (id == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return cache.getOne(CacheKeys.MEDIA_PARTITION, id, () -> pointReadMedia(id));
    }

    protected CompletableFuture<Boolean> deleteMedia(final String id) {
        if (id == null || id.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }
        final Map<String, AttributeValue> key = Map.of(ID, AttributeValue.builder().s(id).build());
        return persistence.deleteItem(b -> b.tableName(MEDIA_TABLE).key(key))
                .thenCompose(resp -> resp.sdkHttpResponse().isSuccessful()
                        // Invalidate rather than surgically removing the field: deletes are rare, and a rebuild
                        // from the table is the one thing guaranteed to leave the cache consistent.
                        ? cache.invalidate().thenApply(ignored -> true)
                        : CompletableFuture.completedFuture(false));
    }

    public void clearCache() {
        cache.invalidate().join();
    }

    private CompletableFuture<Optional<MediaItem>> pointReadMedia(final String id) {
        final Map<String, AttributeValue> key = Map.of(ID, AttributeValue.builder().s(id).build());
        return persistence.getItem(b -> b.key(key).tableName(MEDIA_TABLE).build())
                .thenApply(resp -> resp.item().get(CONTENT))
                .thenApply(content -> Optional.ofNullable(content == null ? null : parseMedia(content.s())))
                .exceptionally(ex -> {
                    log.debug("MediaDAO: unable to read media item ({})", id, ex);
                    return Optional.empty();
                });
    }

    private CompletableFuture<List<MediaItem>> loadAllMedia() {
        return persistence.scanAll(b -> b.consistentRead(false).limit(1000).tableName(MEDIA_TABLE).build())
                .thenApply(items -> items.stream()
                        .map(it -> it.get(CONTENT))
                        .filter(content -> content != null)
                        .map(content -> parseMedia(content.s()))
                        .filter(item -> item != null)
                        .toList());
    }

    private MediaItem parseMedia(final String json) {
        try {
            return mapper.readValue(json, MediaItem.class);
        } catch (final IOException ex) {
            log.error("Unable to parse media record: " + json, ex);
            return null;
        }
    }

    private String toJson(final MediaItem item) {
        try {
            return mapper.writeValueAsString(item);
        } catch (final IOException ex) {
            log.error("Unable to serialize media item: " + item.getId(), ex);
            return null;
        }
    }
}
