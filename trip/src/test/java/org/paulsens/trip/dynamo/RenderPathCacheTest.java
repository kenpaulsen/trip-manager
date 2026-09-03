package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.paulsens.trip.content.MarketingPageBootstrap;
import org.paulsens.trip.content.OrgPageBootstrap;
import org.paulsens.trip.content.StarterTemplates;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Organization;
import org.testng.Assert;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

/**
 * The performance contract behind the content-template pages: once the caches are warm, the RENDER path
 * (section listings + template lookups) performs ZERO persistence reads -- everything answers from the
 * stale-while-revalidate caches. A regression here puts DynamoDB round trips on every public page view.
 */
public class RenderPathCacheTest {

    /** Counts store READS; writes are free to touch the store (they happen on admin actions only). */
    static final class CountingPersistence implements Persistence {
        private final InMemoryPersistence delegate = new InMemoryPersistence();
        final AtomicInteger reads = new AtomicInteger();

        @Override
        public PutItemResponse putItem(final Consumer<PutItemRequest.Builder> req) {
            return delegate.putItem(req);
        }

        @Override
        public DeleteItemResponse deleteItem(final Consumer<DeleteItemRequest.Builder> req) {
            return delegate.deleteItem(req);
        }

        @Override
        public GetItemResponse getItem(final Consumer<GetItemRequest.Builder> req) {
            reads.incrementAndGet();
            return delegate.getItem(req);
        }

        @Override
        public ScanResponse scan(final Consumer<ScanRequest.Builder> req) {
            reads.incrementAndGet();
            return delegate.scan(req);
        }

        @Override
        public QueryResponse query(final Consumer<QueryRequest.Builder> req) {
            reads.incrementAndGet();
            return delegate.query(req);
        }

        @Override
        public List<Map<String, AttributeValue>> scanAll(final Consumer<ScanRequest.Builder> req) {
            reads.incrementAndGet();
            return delegate.scanAll(req);
        }

        @Override
        public List<Map<String, AttributeValue>> queryAll(final Consumer<QueryRequest.Builder> req) {
            reads.incrementAndGet();
            return delegate.queryAll(req);
        }
    }

    @Test
    public void warmRenderPathPerformsNoPersistenceReads() {
        final CountingPersistence counting = new CountingPersistence();
        final ObjectMapper mapper = new ObjectMapper();
        mapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        final TemplateDAO templates = new TemplateDAO(mapper, counting);
        final ContentDAO content = new ContentDAO(mapper, counting);

        // Seed a small v2-shaped page: templates, a page section, a container with one child.
        for (final ContentTemplate starter : StarterTemplates.all()) {
            Assert.assertTrue(templates.saveTemplate(starter, 5));
        }
        final String page = "page:cache-test";
        Assert.assertTrue(content.saveContent(instance("rp-intro", page, StarterTemplates.TEXT_ONLY_ID,
                Map.of("body", "<p>hi</p>")), 5));
        Assert.assertTrue(content.saveContent(instance("rp-holder", page, StarterTemplates.CONTAINER_ID,
                Map.of()), 5));
        Assert.assertTrue(content.saveContent(instance("rp-child", "rp-holder",
                StarterTemplates.TEXT_ONLY_ID, Map.of("body", "<p>child</p>")), 5));
        // An ORGANIZATION's site page: its own section partition, rendered by the same path with the same
        // zero-read contract -- a tenant's home page must cost no more than the shared one.
        final String orgPage = OrgPageBootstrap.pageKey(
                Organization.Id.from("1d88a054-74c7-4293-a40c-b007e09520f3"));
        Assert.assertTrue(content.saveContent(instance("rp-org-welcome", orgPage, StarterTemplates.TEXT_ONLY_ID,
                Map.of("body", "<p>welcome</p>")), 5));
        // The product's own marketing page: band sections plus a band container with its children, each
        // rendered through the same cached template lookups (the band's own title slot included).
        final String marketing = OrgPageBootstrap.MARKETING_PAGE_KEY;
        final String features = MarketingPageBootstrap.idOf(MarketingPageBootstrap.FEATURES);
        for (final ContentInstance row : MarketingPageBootstrap.rows(id -> 1)) {
            Assert.assertTrue(content.saveContent(row, 5), row.getTitle());
        }

        // Warm every cache the render path touches (the first request may hit the store; that is allowed).
        content.getContentForSection(page);
        content.getContentForSection("rp-holder");
        content.getContentForSection(orgPage);
        content.getContentForSection(marketing);
        content.getContentForSection(features);
        templates.getAllTemplates();
        templates.getTemplate(StarterTemplates.TEXT_ONLY_ID, 1);
        templates.getTemplate(StarterTemplates.CONTAINER_ID, 1);
        templates.getTemplate(StarterTemplates.BAND_HERO_ID, 1);
        templates.getTemplate(StarterTemplates.BAND_FEATURES_ID, 1);
        templates.getTemplate(StarterTemplates.FEATURE_CARD_ID, 1);

        // The render pass: what an anonymous page view executes, twice for good measure.
        counting.reads.set(0);
        for (int i = 0; i < 2; i++) {
            content.getContentForSection(page);
            content.getContentForSection("rp-holder");
            content.getContentForSection(orgPage);
            content.getContentForSection(marketing);
            content.getContentForSection(features);
            templates.getTemplate(StarterTemplates.TEXT_ONLY_ID, 1);
            templates.getTemplate(StarterTemplates.CONTAINER_ID, 1);
            templates.getTemplate(StarterTemplates.PILGRIMAGES_ID, 1);
            // A container's ROW resolves the same pinned version the rest of the render path uses
            // (ContentCommands.childRow) -- once per child on every public page view.
            templates.getTemplate(StarterTemplates.CONTAINER_ID, 1);
            // A band container: its title slot (renderTitle), wrapper and row all read this one entry.
            templates.getTemplate(StarterTemplates.BAND_FEATURES_ID, 1);
            templates.getTemplate(StarterTemplates.FEATURE_CARD_ID, 1);
            templates.getTemplate(StarterTemplates.BAND_HERO_ID, 1);
        }
        Assert.assertEquals(counting.reads.get(), 0,
                "the warm render path must answer entirely from cache -- no live store reads");

        // Why childRow must NOT reach for the friendlier unversioned lookup: it rescans the whole table
        // whenever the cache is not authoritative, which on the render path would be once per child.
        counting.reads.set(0);
        templates.getTemplate(StarterTemplates.CONTAINER_ID);
        Assert.assertTrue(counting.reads.get() > 0,
                "if this ever becomes cache-served, childRow may resolve the LATEST row instead");
    }

    private static ContentInstance instance(final String id, final String section, final String templateId,
            final Map<String, String> values) {
        return new ContentInstance(id, section, id, templateId, 1, new HashMap<>(values),
                null, 0, 0, null, "test");
    }
}
