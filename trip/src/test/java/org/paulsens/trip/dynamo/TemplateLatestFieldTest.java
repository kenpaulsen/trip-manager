package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.paulsens.trip.cache.CountingCacheClient;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Placeholder;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The template cache's LATEST field ({@code CacheKeys.templateLatestField}), what a "Stay on Latest" content instance
 * renders with: every save moves it, a delete removes it, the scan rebuild writes it, and none of that disturbs the
 * versioned fields or the one-row-per-template listing.
 */
public class TemplateLatestFieldTest {

    private static final ObjectMapper MAPPER = DAO.getInstance().getMapper();

    private static ContentTemplate template(final String id, final String body) {
        return new ContentTemplate(id, 0, "Name-" + id, "desc", body,
                new ArrayList<>(List.of(new Placeholder("x", Placeholder.Type.TEXT, "X", null, false))),
                null, null);
    }

    @Test
    public void everySaveMovesTheLatestFieldAndADeleteRemovesIt() {
        final TemplateDAO dao = new TemplateDAO(MAPPER, new InMemoryPersistence());
        Assert.assertTrue(dao.getLatestTemplate("lf-a").isEmpty(), "unknown before the first save");
        Assert.assertTrue(dao.saveTemplate(template("lf-a", "<p>{{x}}</p>"), 5));
        Assert.assertEquals(dao.getLatestTemplate("lf-a").orElseThrow().getVersion(), 1);

        final ContentTemplate next = dao.getTemplate("lf-a", 1).orElseThrow();
        next.setBody("<h1>{{x}}</h1>");
        Assert.assertTrue(dao.saveTemplate(next, 5));
        final ContentTemplate latest = dao.getLatestTemplate("lf-a").orElseThrow();
        Assert.assertEquals(latest.getVersion(), 2);
        Assert.assertEquals(latest.getBody(), "<h1>{{x}}</h1>");
        Assert.assertEquals(dao.getTemplate("lf-a", 1).orElseThrow().getBody(), "<p>{{x}}</p>",
                "the versioned field of the old version is untouched");
        Assert.assertEquals(dao.getAllTemplates().stream().filter(t -> "lf-a".equals(t.getId())).count(), 1,
                "the latest copy never shows up as a second row");

        Assert.assertTrue(dao.deleteTemplate("lf-a"));
        Assert.assertTrue(dao.getLatestTemplate("lf-a").isEmpty(), "a deleted template has no latest version");
        Assert.assertTrue(dao.getLatestTemplate(null).isEmpty());
        Assert.assertTrue(dao.getLatestTemplate(" ").isEmpty());
    }

    @Test
    public void aClearedCacheReadsTheRowOnceAndThenAnswersFromCache() {
        final RenderPathCacheTest.CountingPersistence store = new RenderPathCacheTest.CountingPersistence();
        final TemplateDAO dao = new TemplateDAO(MAPPER, store);
        Assert.assertTrue(dao.saveTemplate(template("lf-b", "<p>{{x}}</p>"), 5));
        dao.clearCache();

        store.reads.set(0);
        Assert.assertEquals(dao.getLatestTemplate("lf-b").orElseThrow().getVersion(), 1);
        Assert.assertEquals(store.reads.get(), 1, "one point read of the row");
        store.reads.set(0);
        Assert.assertEquals(dao.getLatestTemplate("lf-b").orElseThrow().getVersion(), 1);
        Assert.assertEquals(store.reads.get(), 0, "then cached");
    }

    /**
     * A template written behind this process's back (a script, another task) reaches the latest field through the
     * scan rebuild, like every versioned field: the cold build of a revalidating cache writes it, so the lookup is a
     * cache hit with no read of the store.
     */
    @Test
    public void theScanBuildWritesTheLatestField() {
        final RenderPathCacheTest.CountingPersistence store = new RenderPathCacheTest.CountingPersistence();
        final TemplateDAO writer = new TemplateDAO(MAPPER, store);
        Assert.assertTrue(writer.saveTemplate(template("lf-c", "<p>{{x}}</p>"), 5));
        final ContentTemplate next = writer.getTemplate("lf-c", 1).orElseThrow();
        next.setBody("<b>{{x}}</b>");
        Assert.assertTrue(writer.saveTemplate(next, 5));

        // A revalidating cache (not the local-mode in-memory client): its first listing scans the table.
        final TemplateDAO reader = new TemplateDAO(MAPPER, store, new CountingCacheClient());
        Assert.assertEquals(reader.getAllTemplates().size(), 1);
        store.reads.set(0);
        final ContentTemplate latest = reader.getLatestTemplate("lf-c").orElseThrow();
        Assert.assertEquals(latest.getVersion(), 2);
        Assert.assertEquals(latest.getBody(), "<b>{{x}}</b>");
        Assert.assertEquals(reader.getTemplate("lf-c", 1).orElseThrow().getVersion(), 1);
        Assert.assertEquals(store.reads.get(), 0, "both answered from what the scan wrote");
        Assert.assertTrue(reader.getLatestTemplate("lf-unknown").isEmpty(),
                "after a scan, a missing field is an authoritative 'no such template'");
    }
}
