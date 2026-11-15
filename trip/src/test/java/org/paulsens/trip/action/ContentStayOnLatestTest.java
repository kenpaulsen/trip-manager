package org.paulsens.trip.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Placeholder;
import org.paulsens.trip.model.TemplateKind;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * "Stay on Latest" (the content dialog's first version choice): an instance that follows its template renders the
 * template's CURRENT version with no further edit, its values carried across in memory, and the flag survives
 * every path that saves, copies or serializes an instance. Each test owns its template ids, so the suite's other
 * template saves never move them.
 */
public class ContentStayOnLatestTest {

    private static final Person.Id ADMIN = Person.Id.from("stay-on-latest-admin");

    @BeforeClass
    public void seedPrivilege() {
        final PrivilegeCommands priv = new PrivilegeCommands();
        priv.savePrivilege(priv.createPrivilege(PrivilegeCommands.CONTENT_ADMIN, "content admins", null,
                List.of(ADMIN)));
    }

    private static ContentCommands admin() {
        final ContentCommands content = new ContentCommands();
        content.setCallerSource(() -> new Caller(ADMIN, false,
                new AuditActor(ADMIN.getValue() + "@example.com", ADMIN.getValue()), new PrivilegeCommands()));
        return content;
    }

    private static void saveTemplate(final String id, final String body, final Placeholder... prompts) {
        final int current = DAO.getInstance().getTemplate(id, Cached.NO).map(ContentTemplate::getVersion).orElse(0);
        Assert.assertTrue(DAO.getInstance().saveTemplate(new ContentTemplate(id, current, "Name " + id, null, body,
                List.of(prompts), null, null), 5));
    }

    private static Placeholder text(final String name, final String label) {
        return new Placeholder(name, Placeholder.Type.TEXT, label, null, false);
    }

    private static ContentInstance stored(final String id) {
        return DAO.getInstance().getContent(id, Cached.NO).orElseThrow();
    }

    private static ContentInstance onPage(final ContentCommands content, final String section, final String id) {
        return content.getForSection(section).stream().filter(row -> id.equals(row.getId())).findFirst()
                .orElseThrow();
    }

    @Test
    public void aStayOnLatestInstanceRendersEachNewTemplateVersionWithNoEdit() {
        final ContentCommands content = admin();
        saveTemplate("sol-render", "<p>{{msg}}</p>", text("msg", "Message"));
        final String section = "sol.section-render";
        final ContentInstance follows = content.createContent(section, "sol-render");
        follows.setTitle("Follows");
        follows.getValues().put("msg", "hello");
        Assert.assertTrue(content.retargetTemplateVersion(follows, ContentCommands.STAY_ON_LATEST));
        Assert.assertTrue(content.saveContent(follows));
        final ContentInstance pinned = content.createContent(section, "sol-render");
        pinned.setTitle("Pinned");
        pinned.getValues().put("msg", "hello");
        Assert.assertTrue(content.saveContent(pinned));
        Assert.assertTrue(stored(follows.getId()).isStayOnLatest(), "Apply stores the choice");
        Assert.assertEquals(stored(follows.getId()).getTemplateVersion(), 1);
        Assert.assertFalse(stored(pinned.getId()).isStayOnLatest(), "a new instance is pinned by default");

        saveTemplate("sol-render", "<h1>{{msg}}</h1>", text("msg", "Message"));
        Assert.assertEquals(content.render(onPage(content, section, follows.getId())), "<h1>hello</h1>",
                "the page follows the template's new version");
        Assert.assertEquals(content.render(stored(follows.getId())), "<h1>hello</h1>",
                "so does a row read without the section listing");
        Assert.assertEquals(content.render(onPage(content, section, pinned.getId())), "<p>hello</p>",
                "a pinned sibling keeps its own version");
        Assert.assertEquals(stored(follows.getId()).getTemplateVersion(), 1, "a read never writes");
        Assert.assertEquals(stored(follows.getId()).getVersion(), 1, "no new content version either");
    }

    @Test
    public void renamedPlaceholdersCarryAcrossInMemoryAndOnTheNextSave() {
        final ContentCommands content = admin();
        saveTemplate("sol-rename", "<p>{{cap}}</p>", text("cap", "Caption"));
        final String section = "sol.section-rename";
        final ContentInstance item = content.createContent(section, "sol-rename");
        item.setTitle("Renamed");
        item.getValues().put("cap", "kept");
        Assert.assertTrue(content.retargetTemplateVersion(item, ContentCommands.STAY_ON_LATEST));
        Assert.assertTrue(content.saveContent(item));

        // v2 renames the prompt but keeps its label.
        saveTemplate("sol-rename", "<div>{{caption}}</div>", text("caption", "Caption"));
        final ContentInstance shown = onPage(content, section, item.getId());
        Assert.assertEquals(shown.getTemplateVersion(), 2);
        Assert.assertEquals(shown.getValues().get("caption"), "kept", "matched by its unchanged label");
        Assert.assertEquals(content.render(shown), "<div>kept</div>");
        Assert.assertEquals(stored(item.getId()).getValues().get("cap"), "kept", "the stored row is untouched");

        final ContentInstance editing = content.getContent(item.getId());
        Assert.assertEquals(editing.getTemplateVersion(), 2, "the dialog opens on the version that renders");
        Assert.assertEquals(content.pinnedVersion(editing), ContentCommands.STAY_ON_LATEST);
        Assert.assertEquals(content.placeholdersOf(editing).getFirst().getName(), "caption");
        Assert.assertTrue(content.saveContent(editing));
        Assert.assertEquals(stored(item.getId()).getTemplateVersion(), 2, "a save stores the carried values");
        Assert.assertEquals(stored(item.getId()).getValues().get("caption"), "kept");
        Assert.assertTrue(stored(item.getId()).isStayOnLatest());
    }

    @Test
    public void aSaveCarriesAStaleStayOnLatestCopyToTheCurrentVersion() {
        final ContentCommands content = admin();
        saveTemplate("sol-stale", "<p>{{a}}</p>", text("a", "Words"));
        final ContentInstance item = content.createContent("sol.section-stale", "sol-stale");
        item.setTitle("Stale");
        item.getValues().put("a", "words");
        item.setStayOnLatest(true);
        // The template moves on while the dialog (or a script's row) still holds v1.
        saveTemplate("sol-stale", "<p>{{b}}</p>", text("b", "Words"));
        Assert.assertTrue(content.saveContent(item));
        final ContentInstance saved = stored(item.getId());
        Assert.assertEquals(saved.getTemplateVersion(), 2);
        Assert.assertEquals(saved.getValues(), Map.of("b", "words"));
    }

    @Test
    public void theFlagSurvivesCopyRestoreAndAJacksonRoundTrip() throws Exception {
        final ContentCommands content = admin();
        saveTemplate("sol-copy", "<p>{{msg}}</p>", text("msg", "Message"));
        final ContentInstance item = content.createContent("sol.section-copy", "sol-copy");
        item.setTitle("Copied");
        item.getValues().put("msg", "x");
        item.setStayOnLatest(true);
        Assert.assertTrue(item.copy().isStayOnLatest(), "copy");
        Assert.assertTrue(content.saveContent(item));
        final ContentInstance pinnedAgain = content.getContent(item.getId());
        Assert.assertTrue(content.retargetTemplateVersion(pinnedAgain, "1"), "pins in place");
        Assert.assertTrue(content.saveContent(pinnedAgain));
        Assert.assertFalse(stored(item.getId()).isStayOnLatest());
        Assert.assertTrue(content.restoreContent(item.getId(), 1));
        Assert.assertTrue(stored(item.getId()).isStayOnLatest(), "a restore brings back the version's choice");

        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final String json = mapper.writeValueAsString(item);
        Assert.assertTrue(json.contains("\"stayOnLatest\":true"), json);
        Assert.assertTrue(mapper.readValue(json, ContentInstance.class).isStayOnLatest());
        item.setStayOnLatest(false);
        final String pinnedJson = mapper.writeValueAsString(item);
        Assert.assertFalse(pinnedJson.contains("stayOnLatest"), "a pinned row's JSON is unchanged: " + pinnedJson);
        Assert.assertFalse(mapper.readValue(pinnedJson, ContentInstance.class).isStayOnLatest(),
                "absent reads as pinned, as every row written before the choice");
    }

    @Test
    public void theMenuOffersStayOnLatestFirstForEveryInstanceWithATemplate() {
        final ContentCommands content = admin();
        saveTemplate("sol-menu", "<p>{{msg}}</p>", text("msg", "Message"));
        final ContentInstance fresh = content.createContent("sol.section-menu", "sol-menu");
        Assert.assertTrue(content.offersVersionMenu(fresh), "a new instance of a one-version template");
        Assert.assertEquals(content.versionChoices(fresh), List.of(ContentCommands.STAY_ON_LATEST, "1"));
        Assert.assertEquals(content.choiceLabel(fresh, ContentCommands.STAY_ON_LATEST), "Stay on Latest");
        Assert.assertEquals(content.choiceLabel(fresh, "1"), "v1 (latest)");
        Assert.assertEquals(content.choiceLabel(fresh, "junk"), "");
        Assert.assertEquals(content.pinnedVersion(fresh), "1", "pinned to the newest version by default");

        saveTemplate("sol-menu", "<h2>{{msg}}</h2>", text("msg", "Message"));
        Assert.assertEquals(content.versionChoices(fresh), List.of(ContentCommands.STAY_ON_LATEST, "2", "1"));
        Assert.assertEquals(content.choiceLabel(fresh, "1"), "v1 (in use)");
        Assert.assertTrue(content.isTemplateOutdated(fresh));
        fresh.setStayOnLatest(true);
        Assert.assertFalse(content.isTemplateOutdated(fresh), "no upgrade hint for an instance that follows");
        Assert.assertEquals(content.choiceLabel(fresh, "1"), "v1", "and no version is 'in use'");
        Assert.assertEquals(content.pinnedVersion(fresh), ContentCommands.STAY_ON_LATEST, "reopens selected");

        final ContentInstance orphan = content.createContent("sol.section-menu", "sol-no-such-template");
        Assert.assertFalse(content.offersVersionMenu(orphan));
        Assert.assertTrue(content.versionChoices(orphan).isEmpty());
        Assert.assertFalse(content.offersVersionMenu(null));
    }

    @Test
    public void retargetingToLatestAndBackToAPinnedVersion() {
        final ContentCommands content = admin();
        saveTemplate("sol-retarget", "<p>{{cap}}</p>", text("cap", "Caption"));
        final ContentInstance item = content.createContent("sol.section-retarget", "sol-retarget");
        item.getValues().put("cap", "words");
        saveTemplate("sol-retarget", "<div>{{caption}}</div>", text("caption", "Caption"));

        Assert.assertTrue(content.retargetTemplateVersion(item, ContentCommands.STAY_ON_LATEST));
        Assert.assertTrue(item.isStayOnLatest());
        Assert.assertEquals(item.getTemplateVersion(), 2, "moved to the newest version");
        Assert.assertEquals(item.getValues(), Map.of("caption", "words"));

        Assert.assertTrue(content.retargetTemplateVersion(item, "1"), "a number pins it again");
        Assert.assertFalse(item.isStayOnLatest());
        Assert.assertEquals(item.getTemplateVersion(), 1);
        Assert.assertEquals(item.getValues(), Map.of("cap", "words"));

        Assert.assertTrue(content.retargetTemplateVersion(item, ContentCommands.STAY_ON_LATEST));
        Assert.assertTrue(content.retargetTemplateVersion(item, "2"), "pinning the version it is already on");
        Assert.assertFalse(item.isStayOnLatest());
        Assert.assertEquals(item.getTemplateVersion(), 2);
        Assert.assertFalse(content.retargetTemplateVersion(item, "2"), "and then there is nothing to do");

        final ContentInstance orphan = content.createContent("sol.section-retarget", "sol-no-such-template");
        Assert.assertFalse(content.retargetTemplateVersion(orphan, ContentCommands.STAY_ON_LATEST));
        Assert.assertFalse(orphan.isStayOnLatest());
    }

    @Test
    public void aGoneTemplateOrVersionFallsBackAsAPinnedInstanceDoes() {
        final ContentCommands content = admin();
        saveTemplate("sol-gone", "<p>{{msg}}</p>", text("msg", "Message"));
        final ContentInstance item = new ContentInstance("sol-gone-row", "sol.section-gone", "Gone", "sol-gone", 99,
                new HashMap<>(Map.of("msg", "aged", "other", "dropped")), null, 0, 0, null, null, null, null, true);
        Assert.assertEquals(content.render(item), "<p>aged</p>",
                "an aged-out saved-against version carries same-named values onto the latest");

        Assert.assertTrue(DAO.getInstance().deleteTemplate("sol-gone"));
        Assert.assertEquals(content.render(item), "", "a deleted template renders nothing, as before");
        Assert.assertEquals(content.getKind(item), "STANDARD");
    }

    @Test
    public void aStayOnLatestContainerTakesItsNewRowWithNoEdit() {
        final ContentCommands content = admin();
        final String id = "sol-box";
        Assert.assertTrue(DAO.getInstance().saveTemplate(new ContentTemplate(id, 0, "Box", null,
                "<li>{{child}}</li>", List.of(), null, null, TemplateKind.CONTAINER, null, null, null), 5));
        final ContentInstance box = content.createContent("page:sol-box-page", id);
        Assert.assertTrue(content.retargetTemplateVersion(box, ContentCommands.STAY_ON_LATEST));
        Assert.assertTrue(content.saveContent(box));
        final ContentInstance child = content.createContent(box.getId(), "sol-render-child");
        child.setTitle("Child");

        final ContentTemplate next = DAO.getInstance().getTemplate(id, 1, Cached.NO).orElseThrow();
        next.setBody("<p class=\"row\">{{child}}</p>");
        Assert.assertTrue(DAO.getInstance().saveTemplate(next, 5));
        final ContentInstance shown = onPage(content, "page:sol-box-page", box.getId());
        Assert.assertEquals(content.getKind(shown), "CONTAINER");
        Assert.assertEquals(content.childRowBefore(shown, child, 0), "<p class=\"row\">");
        Assert.assertEquals(content.childRowAfter(shown, child, 0), "</p>");
    }
}
