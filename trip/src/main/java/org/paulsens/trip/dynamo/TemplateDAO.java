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
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.CacheSupport;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.cache.PartitionScanCache;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.TemplateRecord;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Reusable content templates (see {@link ContentTemplate}). One row per template id; the row carries the
 * current version plus the retained history for undo ({@link TemplateRecord}).
 *
 * <p>Cached like {@link MediaDAO} -- the table is tiny and read on public page renders -- but with one twist:
 * the cache <em>field</em> is {@code id + "#v" + version} ({@link CacheKeys#templateField}), and the loader
 * flattens every retained version of every row into its own entry. Content instances pin the template version
 * they were authored against, so a page rendering old content finds its exact template version in cache even
 * after the template has moved on. The soft TTL is five minutes ({@link CacheKeys#CONTENT_SOFT_TTL}): stale
 * reads still answer from cache while a background rebuild refreshes it.
 */
@Slf4j
public class TemplateDAO {
    private static final String ID = "id";
    private static final String CONTENT = "content";
    /** Package-visible so {@link InMemoryPersistence} can register the table for local mode. */
    static final String TEMPLATES_TABLE = "templates";

    private final ObjectMapper mapper;
    private final Persistence persistence;
    private final PartitionScanCache<ContentTemplate> cache;

    protected TemplateDAO(final ObjectMapper mapper, final Persistence persistence) {
        this(mapper, persistence, new InMemoryCacheClient());
    }

    protected TemplateDAO(final ObjectMapper mapper, final Persistence persistence,
            final CacheClient cacheClient) {
        this.mapper = mapper;
        this.persistence = persistence;
        this.cache = PartitionScanCache.<ContentTemplate>builder()
                .cache(cacheClient)
                .keyPrefix(CacheKeys.TEMPLATE_PREFIX)
                .loadedKey(CacheKeys.TEMPLATE_LOADED)
                .softTtl(CacheKeys.CONTENT_SOFT_TTL)
                .softRevalidate(CacheSupport.softRevalidateEnabled(cacheClient))
                .loader(this::loadAllVersions)
                .partitioner(t -> CacheKeys.TEMPLATE_PARTITION)
                // Versioned field: one cache entry per retained version, never a mixed pairing.
                .fielder(t -> CacheKeys.templateField(t.getId(), t.getVersion()))
                .serializer(this::toJson)
                .deserializer(this::parseTemplate)
                .build();
    }

    /**
     * Saves the given template as the row's next version, retaining up to {@code retain} previous versions.
     * The editor's copy must carry the version it was loaded from -- a mismatch with the stored current
     * version means someone else saved in between, and the save is refused (cheap lost-update guard).
     */
    protected Boolean saveTemplate(final ContentTemplate edited, final int retain) {
        if (edited == null || edited.getId() == null || edited.getId().isBlank()) {
            log.warn("Refusing to save a template with no id");
            return false;
        }
        final TemplateRecord existing = pointReadRecord(edited.getId()).orElse(null);
        final ContentTemplate current = existing == null ? null : existing.getCurrent();
        if (current != null && edited.getVersion() != current.getVersion()) {
            log.warn("Refusing stale save of template '{}': edited from v{} but stored is v{}",
                    edited.getId(), edited.getVersion(), current.getVersion());
            return false;
        }
        edited.setVersion(current == null ? 1 : current.getVersion() + 1);
        edited.setModified(LocalDateTime.now());
        final List<ContentTemplate> previous = new ArrayList<>();
        if (current != null) {
            previous.add(current);
            previous.addAll(existing.getPrevious());
        }
        final List<ContentTemplate> trimmed =
                previous.size() > retain ? previous.subList(0, Math.max(0, retain)) : previous;
        final TemplateRecord row = new TemplateRecord(edited.getId(), edited, trimmed);
        return putRecord(row) && cache.put(edited);
    }

    /** The latest version of every template, for pickers and the manager page. */
    protected List<ContentTemplate> getAllTemplates() {
        List<ContentTemplate> versions = cache.getPartition(CacheKeys.TEMPLATE_PARTITION);
        if (versions.isEmpty()) {
            // An empty partition on a table that HAS rows means the cache was cleared and (with soft
            // revalidate off -- local mode) will never rebuild itself. The table is tiny; answer from a
            // scan so the manager page and pickers survive an admin "clear all caches".
            versions = loadAllVersions();
        }
        final Map<String, ContentTemplate> newest = versions.stream()
                .collect(Collectors.toMap(ContentTemplate::getId, t -> t,
                        BinaryOperator.maxBy(Comparator.comparingInt(ContentTemplate::getVersion))));
        return newest.values().stream()
                .sorted(Comparator.comparing(ContentTemplate::getName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    /** The latest version of one template, empty when the id is unknown. */
    protected Optional<ContentTemplate> getTemplate(final String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return getAllTemplates().stream().filter(t -> id.equals(t.getId())).findFirst()
                .or(() -> pointReadRecord(id).map(TemplateRecord::getCurrent));
    }

    /** One specific retained version, empty when it has aged out of retention. */
    protected Optional<ContentTemplate> getTemplate(final String id, final int version) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return cache.getOne(CacheKeys.TEMPLATE_PARTITION, CacheKeys.templateField(id, version),
                () -> pointReadRecord(id).map(rec -> rec.findVersion(version)));
    }

    /** The whole row -- current plus history -- for the admin history dialog. Uncached (admin path). */
    protected Optional<TemplateRecord> getTemplateRecord(final String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return pointReadRecord(id);
    }

    protected Boolean deleteTemplate(final String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        // Read first: every retained version has its own cache field, and each must be removed surgically.
        // NEVER invalidate() here -- in local mode the cache client is the datastore.
        final List<ContentTemplate> versions =
                pointReadRecord(id).map(TemplateRecord::getAllVersions).orElse(List.of());
        final Map<String, AttributeValue> key = Map.of(ID, AttributeValue.builder().s(id).build());
        final boolean deleted = persistence.deleteItem(b -> b.tableName(TEMPLATES_TABLE).key(key))
                .sdkHttpResponse().isSuccessful();
        if (deleted) {
            for (final ContentTemplate version : versions) {
                cache.removeOne(CacheKeys.TEMPLATE_PARTITION,
                        CacheKeys.templateField(version.getId(), version.getVersion()));
            }
        }
        return deleted;
    }

    public void clearCache() {
        cache.invalidate();
    }

    private boolean putRecord(final TemplateRecord row) {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(ID, persistence.toStrAttr(row.getId()));
        try {
            map.put(CONTENT, persistence.toStrAttr(mapper.writeValueAsString(row)));
        } catch (final IOException ex) {
            final String error = "Unable to serialize template record: " + row.getId();
            log.warn(error);
            throw new IllegalStateException(error);
        }
        return persistence.putItem(b -> b.tableName(TEMPLATES_TABLE).item(map)).sdkHttpResponse().isSuccessful();
    }

    private Optional<TemplateRecord> pointReadRecord(final String id) {
        final Map<String, AttributeValue> key = Map.of(ID, AttributeValue.builder().s(id).build());
        try {
            final AttributeValue content =
                    persistence.getItem(b -> b.key(key).tableName(TEMPLATES_TABLE).build()).item().get(CONTENT);
            return Optional.ofNullable(content == null ? null : parseRecord(content.s()));
        } catch (final RuntimeException ex) {
            log.debug("TemplateDAO: unable to read template ({})", id, ex);
            return Optional.empty();
        }
    }

    private List<ContentTemplate> loadAllVersions() {
        return persistence.scanAll(b -> b.consistentRead(false).limit(1000).tableName(TEMPLATES_TABLE).build())
                .stream()
                .map(it -> it.get(CONTENT))
                .filter(content -> content != null)
                .map(content -> parseRecord(content.s()))
                .filter(rec -> rec != null)
                .flatMap(rec -> rec.getAllVersions().stream())
                .toList();
    }

    private TemplateRecord parseRecord(final String json) {
        try {
            return mapper.readValue(json, TemplateRecord.class);
        } catch (final IOException ex) {
            log.error("Unable to parse template record: " + json, ex);
            return null;
        }
    }

    private ContentTemplate parseTemplate(final String json) {
        try {
            return mapper.readValue(json, ContentTemplate.class);
        } catch (final IOException ex) {
            log.error("Unable to parse template: " + json, ex);
            return null;
        }
    }

    private String toJson(final ContentTemplate template) {
        try {
            return mapper.writeValueAsString(template);
        } catch (final IOException ex) {
            log.error("Unable to serialize template: " + template.getId(), ex);
            return null;
        }
    }
}
