package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
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
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.ContentRecord;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Template-driven page content (see {@link ContentInstance}). One row per instance id; the row carries the
 * current version plus retained history for undo ({@link ContentRecord}).
 *
 * <p>Cached like {@link TemplateDAO} (five-minute soft TTL, stale-while-revalidate), but partitioned by
 * <em>section</em>: rendering a page section is one hash read of exactly that section's current instances.
 * History is an admin-path point read, never cached.
 */
@Slf4j
public class ContentDAO {
    private static final String ID = "id";
    private static final String CONTENT = "content";
    /** Package-visible so {@link InMemoryPersistence} can register the table for local mode. */
    static final String CONTENT_TABLE = "content";

    private final ObjectMapper mapper;
    private final Persistence persistence;
    private final PartitionScanCache<ContentInstance> cache;

    protected ContentDAO(final ObjectMapper mapper, final Persistence persistence) {
        this(mapper, persistence, new InMemoryCacheClient());
    }

    protected ContentDAO(final ObjectMapper mapper, final Persistence persistence,
            final CacheClient cacheClient) {
        this.mapper = mapper;
        this.persistence = persistence;
        this.cache = PartitionScanCache.<ContentInstance>builder()
                .cache(cacheClient)
                .keyPrefix(CacheKeys.CONTENT_PREFIX)
                .loadedKey(CacheKeys.CONTENT_LOADED)
                .softTtl(CacheKeys.CONTENT_SOFT_TTL)
                .softRevalidate(CacheSupport.softRevalidateEnabled(cacheClient))
                .loader(this::loadAllCurrent)
                .partitioner(ContentInstance::getSection)
                .fielder(ContentInstance::getId)
                .serializer(this::toJson)
                .deserializer(this::parseInstance)
                .build();
    }

    /**
     * Saves the given instance as the row's next version, retaining up to {@code retain} previous versions.
     * Same lost-update guard as {@link TemplateDAO#saveTemplate}: the editor's copy must carry the version it
     * was loaded from.
     */
    protected Boolean saveContent(final ContentInstance edited, final int retain) {
        if (edited == null || edited.getId() == null || edited.getId().isBlank()) {
            log.warn("Refusing to save a content instance with no id");
            return false;
        }
        if (edited.getSection() == null || edited.getSection().isBlank()) {
            log.warn("Refusing to save content instance '{}' with no section", edited.getId());
            return false;
        }
        final ContentRecord existing = pointReadRecord(edited.getId()).orElse(null);
        final ContentInstance current = existing == null ? null : existing.getCurrent();
        if (current != null && edited.getVersion() != current.getVersion()) {
            log.warn("Refusing stale save of content '{}': edited from v{} but stored is v{}",
                    edited.getId(), edited.getVersion(), current.getVersion());
            return false;
        }
        edited.setVersion(current == null ? 1 : current.getVersion() + 1);
        edited.setModified(LocalDateTime.now());
        final List<ContentInstance> previous = new ArrayList<>();
        if (current != null) {
            previous.add(current);
            previous.addAll(existing.getPrevious());
        }
        final List<ContentInstance> trimmed =
                previous.size() > retain ? previous.subList(0, Math.max(0, retain)) : previous;
        final ContentRecord row = new ContentRecord(edited.getId(), edited, trimmed);
        if (!putRecord(row)) {
            return false;
        }
        // A section move leaves the old partition's field behind -- remove it surgically or the instance
        // renders in both sections until GC TTL.
        if (current != null && !current.getSection().equals(edited.getSection())) {
            cache.removeOne(current.getSection(), current.getId());
        }
        return cache.put(edited);
    }

    /** Current instances in a section, ordered by position (ties by event date, then id for stability). */
    protected List<ContentInstance> getContentForSection(final String section) {
        if (section == null || section.isBlank()) {
            return List.of();
        }
        return cache.getPartition(section.trim()).stream()
                .sorted(Comparator.comparingInt(ContentInstance::getPosition)
                        .thenComparing(ContentInstance::getEventDate,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(ContentInstance::getId))
                .toList();
    }

    /** The current version of one instance; uncached point read (editor path -- knows no section). */
    protected Optional<ContentInstance> getContent(final String id) {
        return pointReadRecord(id).map(ContentRecord::getCurrent);
    }

    /**
     * Rewrites the section's display order: each listed id gets position = its list index. Deliberately
     * VERSION-SILENT -- the current version's position changes in place with no version bump and no history
     * push, so undo history stays about content edits and an open editor's lost-update guard still matches.
     * (Caveat: that same open editor re-saves its stale position on Apply; admin-path, accepted.)
     * Ids that are missing or belong to another section are skipped, not failed -- the caller's list can be
     * a moment stale.
     */
    protected Boolean reorderContent(final String section, final List<String> orderedIds) {
        if (section == null || section.isBlank() || orderedIds == null) {
            return false;
        }
        boolean allOk = true;
        for (int i = 0; i < orderedIds.size(); i++) {
            final ContentRecord existing = pointReadRecord(orderedIds.get(i)).orElse(null);
            final ContentInstance current = existing == null ? null : existing.getCurrent();
            if (current == null || !section.trim().equals(current.getSection())) {
                continue;
            }
            if (current.getPosition() == i) {
                continue;
            }
            current.setPosition(i);
            if (!putRecord(new ContentRecord(current.getId(), current, existing.getPrevious()))) {
                allOk = false;
                continue;
            }
            cache.put(current);
        }
        return allOk;
    }

    /**
     * Every row, current plus history -- the reference check behind refusing to delete a template that
     * content still points at. A raw scan on purpose: it runs on an admin click, never on a page render.
     */
    protected List<ContentRecord> getAllContentRecords() {
        return persistence.scanAll(b -> b.consistentRead(false).limit(1000).tableName(CONTENT_TABLE).build())
                .stream()
                .map(it -> it.get(CONTENT))
                .filter(content -> content != null)
                .map(content -> parseRecord(content.s()))
                .filter(rec -> rec != null)
                .toList();
    }

    /** The whole row -- current plus history -- for the undo dialog. Uncached (admin path). */
    protected Optional<ContentRecord> getContentRecord(final String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return pointReadRecord(id);
    }

    protected Boolean deleteContent(final String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        // Read first to learn the section (the cache partition). NEVER invalidate() -- in local mode the
        // cache client is the datastore.
        final ContentInstance current = getContent(id).orElse(null);
        final Map<String, AttributeValue> key = Map.of(ID, AttributeValue.builder().s(id).build());
        final boolean deleted = persistence.deleteItem(b -> b.tableName(CONTENT_TABLE).key(key))
                .sdkHttpResponse().isSuccessful();
        if (deleted && current != null) {
            cache.removeOne(current.getSection(), id);
        }
        return deleted;
    }

    public void clearCache() {
        cache.invalidate();
    }

    private boolean putRecord(final ContentRecord row) {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(ID, persistence.toStrAttr(row.getId()));
        try {
            map.put(CONTENT, persistence.toStrAttr(mapper.writeValueAsString(row)));
        } catch (final IOException ex) {
            final String error = "Unable to serialize content record: " + row.getId();
            log.warn(error);
            throw new IllegalStateException(error);
        }
        return persistence.putItem(b -> b.tableName(CONTENT_TABLE).item(map)).sdkHttpResponse().isSuccessful();
    }

    private Optional<ContentRecord> pointReadRecord(final String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        final Map<String, AttributeValue> key = Map.of(ID, AttributeValue.builder().s(id).build());
        try {
            final AttributeValue content =
                    persistence.getItem(b -> b.key(key).tableName(CONTENT_TABLE).build()).item().get(CONTENT);
            return Optional.ofNullable(content == null ? null : parseRecord(content.s()));
        } catch (final RuntimeException ex) {
            log.debug("ContentDAO: unable to read content ({})", id, ex);
            return Optional.empty();
        }
    }

    private List<ContentInstance> loadAllCurrent() {
        return persistence.scanAll(b -> b.consistentRead(false).limit(1000).tableName(CONTENT_TABLE).build())
                .stream()
                .map(it -> it.get(CONTENT))
                .filter(content -> content != null)
                .map(content -> parseRecord(content.s()))
                .filter(rec -> rec != null && rec.getCurrent() != null)
                .map(ContentRecord::getCurrent)
                .toList();
    }

    private ContentRecord parseRecord(final String json) {
        try {
            return mapper.readValue(json, ContentRecord.class);
        } catch (final IOException ex) {
            log.error("Unable to parse content record: " + json, ex);
            return null;
        }
    }

    private ContentInstance parseInstance(final String json) {
        try {
            return mapper.readValue(json, ContentInstance.class);
        } catch (final IOException ex) {
            log.error("Unable to parse content instance: " + json, ex);
            return null;
        }
    }

    private String toJson(final ContentInstance instance) {
        try {
            return mapper.writeValueAsString(instance);
        } catch (final IOException ex) {
            log.error("Unable to serialize content instance: " + instance.getId(), ex);
            return null;
        }
    }
}
