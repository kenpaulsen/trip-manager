package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.ContentRecord;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Placeholder;
import org.paulsens.trip.model.TemplateRecord;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

/**
 * The versioning and cache contracts of the two content-template DAOs: monotonic versions, history trimmed
 * to the retention count, the versioned template cache field, surgical (never whole-cache) deletes, and the
 * section-move cleanup.
 */
public class TemplateContentDAOTest {

    private static final ObjectMapper MAPPER = DAO.getInstance().getMapper();

    private Persistence store;
    private TemplateDAO templates;
    private ContentDAO contents;

    @BeforeMethod
    public void fresh() {
        store = new InMemoryPersistence();
        templates = new TemplateDAO(MAPPER, store);
        contents = new ContentDAO(MAPPER, store);
    }

    private static ContentTemplate template(final String id, final String body) {
        return new ContentTemplate(id, 0, "Name-" + id, "desc", body,
                new ArrayList<>(List.of(new Placeholder("x", Placeholder.Type.TEXT, "X", null, false))),
                null, null);
    }

    private static ContentInstance instance(final String id, final String section) {
        return new ContentInstance(id, section, "T-" + id, "tpl", 1,
                new HashMap<>(Map.of("x", "v")), null, 0, 0, null, null);
    }

    @Test
    public void savesBumpVersionsAndTrimHistory() {
        final ContentTemplate tpl = template("t1", "v1 {{x}}");
        Assert.assertTrue(templates.saveTemplate(tpl, 2));
        Assert.assertEquals(tpl.getVersion(), 1);
        for (int i = 2; i <= 5; i++) {
            tpl.setBody("v" + i + " {{x}}");
            Assert.assertTrue(templates.saveTemplate(tpl, 2));
            Assert.assertEquals(tpl.getVersion(), i);
        }
        final TemplateRecord record = templates.getTemplateRecord("t1").orElseThrow();
        Assert.assertEquals(record.getCurrent().getVersion(), 5);
        Assert.assertEquals(record.getPrevious().stream().map(ContentTemplate::getVersion).toList(),
                List.of(4, 3), "history should be newest-first and trimmed to 2");
        Assert.assertNull(record.findVersion(2), "trimmed versions are gone");
    }

    @Test
    public void staleSaveIsRefused() {
        final ContentTemplate tpl = template("t2", "one {{x}}");
        Assert.assertTrue(templates.saveTemplate(tpl, 5));
        final ContentTemplate stale = template("t2", "concurrent {{x}}");     // still claims version 0
        Assert.assertFalse(templates.saveTemplate(stale, 5),
                "a save from a version other than the stored current must be refused");
        Assert.assertEquals(templates.getTemplate("t2").orElseThrow().getBody(), "one {{x}}");
    }

    @Test
    public void oldVersionsRemainReadableByVersionedField() {
        final ContentTemplate tpl = template("t3", "old {{x}}");
        Assert.assertTrue(templates.saveTemplate(tpl, 5));
        tpl.setBody("new {{x}}");
        Assert.assertTrue(templates.saveTemplate(tpl, 5));

        Assert.assertEquals(templates.getTemplate("t3").orElseThrow().getBody(), "new {{x}}");
        Assert.assertEquals(templates.getTemplate("t3", 1).orElseThrow().getBody(), "old {{x}}",
                "content pinned to v1 must keep rendering v1");
        Assert.assertTrue(templates.getTemplate("t3", 99).isEmpty());
        Assert.assertTrue(templates.getTemplate(null).isEmpty());
        Assert.assertTrue(templates.getTemplate(" ", 1).isEmpty());
    }

    @Test
    public void getAllTemplatesAnswersOnlyNewestPerId() {
        final ContentTemplate a = template("a1", "a {{x}}");
        Assert.assertTrue(templates.saveTemplate(a, 5));
        a.setBody("a2 {{x}}");
        Assert.assertTrue(templates.saveTemplate(a, 5));
        Assert.assertTrue(templates.saveTemplate(template("b1", "b {{x}}"), 5));

        final List<ContentTemplate> all = templates.getAllTemplates();
        Assert.assertEquals(all.size(), 2);
        Assert.assertEquals(all.stream().filter(t -> t.getId().equals("a1")).findFirst().orElseThrow()
                .getVersion(), 2);
    }

    /**
     * The partition answers the whole-table read only when a scan populated it. An admin "clear all caches"
     * plus any single save leaves it holding exactly that one template — and where nothing rebuilds it (local
     * mode) treating a non-empty partition as the truth hid every other template from the manager page and
     * the Add pickers. It also made the unit suite order-dependent: whichever class cleared caches first
     * decided what a later class could see.
     */
    @Test
    public void aClearedCacheAndOneSaveStillListsEveryTemplate() {
        final CacheClient client = new InMemoryCacheClient();
        final TemplateDAO dao = new TemplateDAO(MAPPER, store, client);
        for (final String id : List.of("c1", "c2", "c3")) {
            Assert.assertTrue(dao.saveTemplate(template(id, id + " {{x}}"), 5));
        }
        client.clearNamespace(CacheKeys.FORMAT_VERSION);

        final ContentTemplate one = dao.getTemplate("c2").orElseThrow();
        one.setBody("edited {{x}}");
        Assert.assertTrue(dao.saveTemplate(one, 5), "the write-through repopulates exactly one field");

        Assert.assertEquals(dao.getAllTemplates().stream().map(ContentTemplate::getId).sorted().toList(),
                List.of("c1", "c2", "c3"), "a partly repopulated partition is not the whole table");
    }

    @Test
    public void deleteRemovesOnlyThatTemplate() {
        final ContentTemplate keep = template("keep", "keep {{x}}");
        final ContentTemplate drop = template("drop", "drop {{x}}");
        Assert.assertTrue(templates.saveTemplate(keep, 5));
        Assert.assertTrue(templates.saveTemplate(drop, 5));
        drop.setBody("drop2 {{x}}");
        Assert.assertTrue(templates.saveTemplate(drop, 5));

        Assert.assertTrue(templates.deleteTemplate("drop"));
        Assert.assertTrue(templates.getTemplate("drop").isEmpty());
        Assert.assertTrue(templates.getTemplate("drop", 1).isEmpty(), "history fields must go too");
        // The load-bearing assertion: in local mode the cache IS the datastore, so a delete that
        // invalidated the whole cache would have emptied every other row as well.
        Assert.assertTrue(templates.getTemplate("keep").isPresent());
        Assert.assertFalse(templates.deleteTemplate(" "));
    }

    @Test
    public void contentPartitionsBySection() {
        Assert.assertTrue(contents.saveContent(instance("c1", "home.events"), 5));
        Assert.assertTrue(contents.saveContent(instance("c2", "home.events"), 5));
        Assert.assertTrue(contents.saveContent(instance("c3", "home.intro"), 5));

        Assert.assertEquals(contents.getContentForSection("home.events").size(), 2);
        Assert.assertEquals(contents.getContentForSection("home.intro").size(), 1);
        Assert.assertTrue(contents.getContentForSection("nowhere").isEmpty());
        Assert.assertTrue(contents.getContentForSection(" ").isEmpty());
    }

    @Test
    public void contentOrdersByPositionThenEventDate() {
        final ContentInstance late = instance("late", "s");
        late.setPosition(1);
        final ContentInstance early = instance("early", "s");
        early.setPosition(0);
        final ContentInstance sooner = instance("sooner", "s");
        sooner.setPosition(1);
        sooner.setEventDate(LocalDateTime.now().plusDays(1));
        late.setEventDate(LocalDateTime.now().plusDays(30));
        Assert.assertTrue(contents.saveContent(late, 5));
        Assert.assertTrue(contents.saveContent(early, 5));
        Assert.assertTrue(contents.saveContent(sooner, 5));

        Assert.assertEquals(contents.getContentForSection("s").stream().map(ContentInstance::getId).toList(),
                List.of("early", "sooner", "late"));
    }

    @Test
    public void sectionMoveCleansTheOldPartition() {
        final ContentInstance moved = instance("mv", "old.section");
        Assert.assertTrue(contents.saveContent(moved, 5));
        final ContentInstance edited = contents.getContent("mv").orElseThrow();
        edited.setSection("new.section");
        Assert.assertTrue(contents.saveContent(edited, 5));

        Assert.assertTrue(contents.getContentForSection("old.section").isEmpty(),
                "a moved instance must not keep rendering in its old section until GC TTL");
        Assert.assertEquals(contents.getContentForSection("new.section").size(), 1);
    }

    @Test
    public void contentHistoryRestoreSourceAndDelete() {
        final ContentInstance c = instance("h1", "s");
        Assert.assertTrue(contents.saveContent(c, 5));
        c.getValues().put("x", "second");
        Assert.assertTrue(contents.saveContent(c, 5));

        final ContentRecord record = contents.getContentRecord("h1").orElseThrow();
        Assert.assertEquals(record.getCurrent().getVersion(), 2);
        Assert.assertEquals(record.findVersion(1).getValues().get("x"), "v");
        Assert.assertEquals(record.getAllVersions().size(), 2);

        Assert.assertTrue(contents.getAllContentRecords().stream()
                .anyMatch(r -> r.getId().equals("h1")));

        Assert.assertTrue(contents.saveContent(instance("h2", "s"), 5));
        Assert.assertTrue(contents.deleteContent("h1"));
        Assert.assertTrue(contents.getContent("h1").isEmpty());
        Assert.assertEquals(contents.getContentForSection("s").size(), 1, "other rows must survive");
        Assert.assertTrue(contents.getContent("h1").isEmpty(), "repeat delete stays gone");
        Assert.assertFalse(contents.deleteContent(" "));
    }

    @Test
    public void contentStaleSaveIsRefused() {
        final ContentInstance c = instance("g1", "s");
        Assert.assertTrue(contents.saveContent(c, 5));
        final ContentInstance stale = instance("g1", "s");     // version 0 again
        Assert.assertFalse(contents.saveContent(stale, 5));
    }

    @Test
    public void refusalsForMissingIdentity() {
        Assert.assertFalse(templates.saveTemplate(null, 5));
        Assert.assertFalse(templates.saveTemplate(template(" ", "x"), 5));
        Assert.assertFalse(contents.saveContent(null, 5));
        final ContentInstance noSection = instance("ns", null);
        Assert.assertFalse(contents.saveContent(noSection, 5));
        Assert.assertTrue(templates.getTemplateRecord(" ").isEmpty());
        Assert.assertTrue(contents.getContentRecord(" ").isEmpty());
        Assert.assertTrue(contents.getContent(null).isEmpty());
    }

    @Test
    public void contentTrimsHistoryToTheRetentionCount() {
        final ContentInstance c = instance("trim", "s");
        Assert.assertTrue(contents.saveContent(c, 2));
        for (int i = 2; i <= 5; i++) {
            c.setTitle("t" + i);
            Assert.assertTrue(contents.saveContent(c, 2));
        }
        final ContentRecord record = contents.getContentRecord("trim").orElseThrow();
        Assert.assertEquals(record.getCurrent().getVersion(), 5);
        Assert.assertEquals(record.getPrevious().stream().map(ContentInstance::getVersion).toList(),
                List.of(4, 3));
    }

    @Test
    public void positionTiesBreakByIdForStableOrder() {
        final ContentInstance b = instance("tie-b", "ties");
        final ContentInstance a = instance("tie-a", "ties");
        Assert.assertTrue(contents.saveContent(b, 5));
        Assert.assertTrue(contents.saveContent(a, 5));
        Assert.assertEquals(contents.getContentForSection("ties").stream().map(ContentInstance::getId).toList(),
                List.of("tie-a", "tie-b"));
    }

    @Test
    public void corruptRowsAreSkippedNotFatal() {
        Assert.assertTrue(templates.saveTemplate(template("good", "g {{x}}"), 5));
        putCorruptRow(TemplateDAO.TEMPLATES_TABLE, "corrupt-tpl");
        putCorruptRow(ContentDAO.CONTENT_TABLE, "corrupt-content");
        Assert.assertTrue(contents.saveContent(instance("good-c", "s"), 5));

        // Fresh DAOs over the same rows: list reads fall to the scan loaders, which must skip the junk.
        final TemplateDAO rescan = new TemplateDAO(MAPPER, store);
        Assert.assertEquals(rescan.getAllTemplates().stream().map(ContentTemplate::getId).toList(),
                List.of("good"));
        Assert.assertTrue(rescan.getTemplate("corrupt-tpl").isEmpty(), "junk answers empty, not an error");
        Assert.assertTrue(rescan.getTemplate("corrupt-tpl", 1).isEmpty());

        final ContentDAO rescanContent = new ContentDAO(MAPPER, store);
        Assert.assertTrue(rescanContent.getContent("corrupt-content").isEmpty());
        Assert.assertEquals(rescanContent.getAllContentRecords().stream().map(ContentRecord::getId).toList(),
                List.of("good-c"));
    }

    private void putCorruptRow(final String table, final String id) {
        final Map<String, AttributeValue> row = Map.of(
                "id", AttributeValue.builder().s(id).build(),
                "content", AttributeValue.builder().s("this is not json").build());
        Assert.assertTrue(store.putItem(b -> b.tableName(table).item(row)).sdkHttpResponse().isSuccessful());
    }

    @Test
    public void coldCacheRebuildsFromTheStoreWhenRevalidateIsOn() {
        // A cache client that is NOT the in-memory one turns soft revalidate on (production shape): a cold
        // partition read must rebuild from the row scan -- the loader path local mode never runs.
        Assert.assertTrue(contents.saveContent(instance("cold-1", "cold.section"), 5));
        final ContentDAO cold = new ContentDAO(MAPPER, store, revalidatingClient());
        Assert.assertEquals(cold.getContentForSection("cold.section").size(), 1);

        Assert.assertTrue(templates.saveTemplate(template("cold-tpl", "c {{x}}"), 5));
        final TemplateDAO coldTemplates = new TemplateDAO(MAPPER, store, revalidatingClient());
        Assert.assertEquals(coldTemplates.getTemplate("cold-tpl").orElseThrow().getBody(), "c {{x}}");
    }

    private static CacheClient revalidatingClient() {
        return Mockito.mock(CacheClient.class, AdditionalAnswers.delegatesTo(new InMemoryCacheClient()));
    }

    @Test
    public void aFailedPutRefusesTheSave() {
        final Persistence failingPuts = new InMemoryPersistence() {
            @Override
            public PutItemResponse putItem(final Consumer<PutItemRequest.Builder> request) {
                return (PutItemResponse) PutItemResponse.builder()
                        .sdkHttpResponse(SdkHttpResponse.builder().statusCode(500).build())
                        .build();
            }
        };
        Assert.assertFalse(new TemplateDAO(MAPPER, failingPuts).saveTemplate(template("f", "b {{x}}"), 5));
        Assert.assertFalse(new ContentDAO(MAPPER, failingPuts).saveContent(instance("f", "s"), 5));
    }

    @Test
    public void pointReadFailureAnswersEmptyNotException() {
        final Persistence throwing = new ThrowingPersistence();
        final TemplateDAO angryTemplates = new TemplateDAO(MAPPER, throwing);
        Assert.assertTrue(angryTemplates.getTemplateRecord("x").isEmpty());
        final ContentDAO angryContents = new ContentDAO(MAPPER, throwing);
        Assert.assertTrue(angryContents.getContent("x").isEmpty());
    }

    /** A store whose point reads blow up, to prove the read paths degrade to empty answers. */
    private static final class ThrowingPersistence implements Persistence {
        @Override
        public GetItemResponse getItem(final Consumer<GetItemRequest.Builder> request) {
            throw new IllegalStateException("store down");
        }
    }

    @Test
    public void reorderRewritesPositionsSilentlyAndSkipsForeignRows() {
        Assert.assertTrue(contents.saveContent(instance("ra", "sec-a"), 5));
        Assert.assertTrue(contents.saveContent(instance("rb", "sec-a"), 5));
        Assert.assertTrue(contents.saveContent(instance("other", "sec-b"), 5));

        // Bad arguments are refused outright.
        Assert.assertFalse(contents.reorderContent(null, List.of("ra")));
        Assert.assertFalse(contents.reorderContent("  ", List.of("ra")));
        Assert.assertFalse(contents.reorderContent("sec-a", null));

        // A foreign-section id and an unknown id are skipped, not failed; positions follow list order.
        Assert.assertTrue(contents.reorderContent("sec-a", List.of("rb", "ra", "other", "ghost")));
        final List<String> ordered = contents.getContentForSection("sec-a").stream()
                .map(ContentInstance::getId).toList();
        Assert.assertEquals(ordered, List.of("rb", "ra"));
        Assert.assertEquals(contents.getContent("other").orElseThrow().getSection(), "sec-b",
                "a row from another section is untouched");

        // Version-silent: no bump, no history growth -- and re-applying the same order writes nothing.
        Assert.assertEquals(contents.getContent("ra").orElseThrow().getVersion(), 1);
        Assert.assertEquals(contents.getContentRecord("ra").orElseThrow().getPrevious().size(), 0);
        Assert.assertTrue(contents.reorderContent("sec-a", List.of("rb", "ra")),
                "an already-ordered section is a clean no-op");
    }

    @Test
    public void garbageRowsDegradeToEmptyNotErrors() {
        // A row whose JSON does not parse must be skipped by every reader, never thrown to a page.
        final Map<String, AttributeValue> junk = new HashMap<>();
        junk.put("id", AttributeValue.builder().s("junk-row").build());
        junk.put("content", AttributeValue.builder().s("{not json").build());
        store.putItem(b -> b.tableName("content").item(junk));

        Assert.assertTrue(contents.getContent("junk-row").isEmpty());
        Assert.assertTrue(contents.getAllContentRecords().isEmpty());
        Assert.assertTrue(contents.getContentForSection("anything").isEmpty());
        Assert.assertTrue(contents.reorderContent("anything", List.of("junk-row")),
                "reorder SKIPS the unparsable row (skips are not failures) and touches nothing");
    }

    @Test
    public void pointReadsSurviveAClearedCache() {
        // With the in-memory client, softRevalidate is off and a cleared partition never rebuilds (the
        // documented local-mode trade). What must still work is every path that point-reads the row.
        Assert.assertTrue(templates.saveTemplate(template("cc", "cc {{x}}"), 5));
        templates.clearCache();
        Assert.assertEquals(templates.getTemplate("cc").orElseThrow().getBody(), "cc {{x}}",
                "getTemplate(id) must fall back to the row when the cache is cold");
        Assert.assertEquals(templates.getTemplate("cc", 1).orElseThrow().getBody(), "cc {{x}}",
                "getOne must consult the point loader while the loaded marker is absent");
        Assert.assertTrue(contents.saveContent(instance("cd", "s"), 5));
        contents.clearCache();
        Assert.assertEquals(contents.getContent("cd").orElseThrow().getSection(), "s");
    }
}
