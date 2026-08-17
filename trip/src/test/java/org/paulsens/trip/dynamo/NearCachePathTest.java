package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.cache.CountingCacheClient;
import org.paulsens.trip.cache.NearCacheClient;
import org.paulsens.trip.cache.NearCacheContext;
import org.paulsens.trip.content.StarterTemplates;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.ContentTemplate;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The near-cache's DAL-level contract, the sibling of {@link RenderPathCacheTest}: that test proves the warm
 * render path performs zero PERSISTENCE reads; this one proves that with {@link Cached#YES} bound the warm
 * render path performs zero SHARED-CACHE reads either (everything answers from the heap), that a write
 * punches through immediately (same-JVM read-your-writes), and that {@link Cached#NO} always reaches the
 * shared cache.
 */
public class NearCachePathTest {

    @Test
    public void warmYesRenderPathPerformsNoSharedCacheReads() {
        final CountingCacheClient counting = new CountingCacheClient();
        final NearCacheClient near = new NearCacheClient(counting, null);
        final ObjectMapper mapper = mapper();
        final TemplateDAO templates = new TemplateDAO(mapper, new InMemoryPersistence(), near);
        final ContentDAO content = new ContentDAO(mapper, new InMemoryPersistence(), near);

        // Seed the same v2-shaped page RenderPathCacheTest uses (writes may touch anything they like).
        for (final ContentTemplate starter : StarterTemplates.all()) {
            Assert.assertTrue(templates.saveTemplate(starter, 5));
        }
        final String page = "page:near-cache-test";
        Assert.assertTrue(content.saveContent(instance("nc-intro", page, StarterTemplates.TEXT_ONLY_ID,
                Map.of("body", "<p>hi</p>")), 5));
        Assert.assertTrue(content.saveContent(instance("nc-holder", page, StarterTemplates.CONTAINER_ID,
                Map.of()), 5));
        Assert.assertTrue(content.saveContent(instance("nc-child", "nc-holder",
                StarterTemplates.TEXT_ONLY_ID, Map.of("body", "<p>child</p>")), 5));

        // Two warm YES passes (may read the shared cache freely). Two, not one: the very first section
        // read takes PartitionScanCache's cold buildAndAnswer path, which answers from its own scan
        // WITHOUT reading the partition hash -- so that hash only enters the near-cache on the second pass.
        yes(() -> renderPass(templates, content, page));
        yes(() -> renderPass(templates, content, page));

        // The warm pass: an anonymous page view with Cached.YES must not touch the shared cache at all.
        counting.reads.set(0);
        counting.readLog.clear();
        yes(() -> renderPass(templates, content, page));
        yes(() -> renderPass(templates, content, page));
        Assert.assertEquals(counting.reads.get(), 0,
                "the warm YES render path must answer entirely from the heap -- no shared-cache reads; "
                        + "saw: " + counting.readLog);

        // Same-JVM read-your-writes: a save invalidates, so the next YES read reaches the shared cache.
        // Edit the READ-BACK instance: saveContent's lost-update guard requires the stored version.
        final ContentInstance edited = content.getContent("nc-intro").orElseThrow();
        edited.getValues().put("body", "<p>edited</p>");
        Assert.assertTrue(content.saveContent(edited, 5));
        counting.reads.set(0);
        final int afterWrite = yes(() -> countingAfter(counting, () -> content.getContentForSection(page)));
        Assert.assertTrue(afterWrite > 0,
                "a write must drop the heap entry so the next read sees the shared cache");

        // Declared-fresh reads always reach the shared cache, warm heap or not.
        counting.reads.set(0);
        NearCacheContext.call(Cached.NO, () -> content.getContentForSection(page));
        Assert.assertTrue(counting.reads.get() > 0, "Cached.NO must never be served from the heap");
    }

    /** The read sequence an anonymous v2 page view executes (see RenderPathCacheTest). */
    private static Object renderPass(final TemplateDAO templates, final ContentDAO content, final String page) {
        content.getContentForSection(page);
        content.getContentForSection("nc-holder");
        templates.getTemplate(StarterTemplates.TEXT_ONLY_ID, 1);
        templates.getTemplate(StarterTemplates.CONTAINER_ID, 1);
        templates.getTemplate(StarterTemplates.PILGRIMAGES_ID, 1);
        templates.getTemplate(StarterTemplates.CONTAINER_ID, 1);
        return null;
    }

    private static int countingAfter(final CountingCacheClient counting, final Supplier<?> read) {
        read.get();
        return counting.reads.get();
    }

    private static <T> T yes(final Supplier<T> read) {
        return NearCacheContext.call(Cached.YES, read);
    }

    private static ObjectMapper mapper() {
        final ObjectMapper mapper = new ObjectMapper();
        mapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    private static ContentInstance instance(final String id, final String section, final String templateId,
            final Map<String, String> values) {
        return new ContentInstance(id, section, id, templateId, 1, new HashMap<>(values),
                null, 0, 0, null, "test");
    }
}
