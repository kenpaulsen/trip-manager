package org.paulsens.trip.action;

import java.time.LocalDateTime;
import java.util.List;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Placeholder;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * The content bean's contracts: visibility filtering, version pinning, the undo path, and -- the part a page
 * gate cannot provide -- the server-side privilege re-check on every mutating call.
 */
public class ContentCommandsTest {

    private static final Person.Id ADMIN = Person.Id.from("content-admin-person");
    private static final Person.Id EVENTS_ONLY = Person.Id.from("event-admin-person");
    private static final Person.Id NOBODY = Person.Id.from("no-priv-person");

    private final PrivilegeCommands priv = new PrivilegeCommands();

    @BeforeClass
    public void seedPrivileges() {
        DAO.getInstance();
        priv.savePrivilege(priv.createPrivilege(PrivilegeCommands.CONTENT_ADMIN, "content admins", null,
                List.of(ADMIN)));
        priv.savePrivilege(priv.createPrivilege(PrivilegeCommands.EVENT_ADMIN, "event admins", null,
                List.of(EVENTS_ONLY)));
        // A template to author against.
        final ContentTemplate tpl = new ContentTemplate("cc-test-tpl", 0, "CC Test", null, "<p>{{msg}}</p>",
                List.of(new Placeholder("msg", Placeholder.Type.TEXT, "Msg", null, true)), null, null);
        Assert.assertTrue(DAO.getInstance().saveTemplate(tpl, 5));
    }

    private static ContentCommands as(final Person.Id who, final boolean siteAdmin) {
        final ContentCommands content = new ContentCommands();
        content.setCallerSource(() -> new Caller(who, siteAdmin,
                new AuditActor(who == null ? null : who.getValue() + "@example.com",
                        who == null ? null : who.getValue()),
                new PrivilegeCommands()));
        return content;
    }

    @Test
    public void createSeedsValuesAndPinsTheTemplateVersion() {
        final ContentCommands content = as(ADMIN, false);
        final ContentInstance created = content.createContent("cc.section-create", "cc-test-tpl");
        Assert.assertEquals(created.getTemplateId(), "cc-test-tpl");
        Assert.assertEquals(created.getTemplateVersion(), 1, "pinned to the template's current version");
        Assert.assertTrue(created.getValues().containsKey("msg"), "one empty value per placeholder");
        Assert.assertNotNull(created.getId());

        final ContentInstance unknownTpl = content.createContent("cc.section-create", "no-such-template");
        Assert.assertEquals(unknownTpl.getTemplateId(), "no-such-template");
        Assert.assertEquals(unknownTpl.getTemplateVersion(), 0);
    }

    @Test
    public void expiredEventsDropFromThePublicViewOnly() {
        final ContentCommands content = as(ADMIN, false);
        final String section = "cc.section-expiry";
        final ContentInstance past = content.createContent(section, "cc-test-tpl");
        past.setTitle("past");
        past.setEventDate(LocalDateTime.now().minusMinutes(1));
        final ContentInstance future = content.createContent(section, "cc-test-tpl");
        future.setTitle("future");
        future.setEventDate(LocalDateTime.now().plusDays(1));
        final ContentInstance undated = content.createContent(section, "cc-test-tpl");
        undated.setTitle("undated");
        Assert.assertTrue(content.saveContent(past));
        Assert.assertTrue(content.saveContent(future));
        Assert.assertTrue(content.saveContent(undated));

        final List<String> visible = content.getForSection(section).stream()
                .map(ContentInstance::getTitle).toList();
        Assert.assertEqualsNoOrder(visible.toArray(), new String[] {"future", "undated"});
        Assert.assertEquals(content.getAllForSection(section).size(), 3, "editors still see expired rows");
    }

    @Test
    public void privilegeChecksGateEveryMutation() {
        final ContentCommands nobody = as(NOBODY, false);
        final ContentInstance denied = nobody.createContent("home.events", "cc-test-tpl");
        denied.setTitle("denied");
        Assert.assertFalse(nobody.saveContent(denied), "no privilege, no save");

        final ContentCommands eventsOnly = as(EVENTS_ONLY, false);
        final ContentInstance event = eventsOnly.createContent("home.events", "cc-test-tpl");
        event.setTitle("cc-event-admin-save");
        Assert.assertTrue(eventsOnly.saveContent(event), "eventAdmin may edit home.events");
        final ContentInstance intro = eventsOnly.createContent("home.intro", "cc-test-tpl");
        Assert.assertFalse(eventsOnly.saveContent(intro), "eventAdmin may NOT edit other sections");
        Assert.assertFalse(eventsOnly.deleteContent("fake-intro"), "nor delete them");

        final ContentCommands admin = as(ADMIN, false);
        Assert.assertTrue(admin.deleteContent(event.getId()), "contentAdmin edits everything");
        Assert.assertFalse(admin.saveContent(null));
        final ContentInstance sectionless = admin.createContent(" ", "cc-test-tpl");
        Assert.assertFalse(admin.saveContent(sectionless));
        Assert.assertFalse(admin.deleteContent("no-such-content"));
    }

    @Test
    public void canEditAnswersThePageGate() {
        final ContentCommands content = as(ADMIN, false);
        Assert.assertTrue(content.canEdit("home.events", ADMIN));
        Assert.assertTrue(content.canEdit("home.intro", ADMIN));
        Assert.assertTrue(content.canEdit("home.events", EVENTS_ONLY));
        Assert.assertFalse(content.canEdit("home.intro", EVENTS_ONLY));
        Assert.assertFalse(content.canEdit("home.events", NOBODY));
        Assert.assertFalse(content.canEdit(null, ADMIN));
        Assert.assertFalse(content.canEdit("home.events", null));
        // A site administrator passes without any privilege row, matching the save path's short-circuit.
        Assert.assertTrue(as(NOBODY, true).canEdit("home.intro", NOBODY));
    }

    @Test
    public void historyAndRestoreKeepVersionsLinear() {
        final ContentCommands content = as(ADMIN, false);
        final String section = "cc.section-restore";
        final ContentInstance first = content.createContent(section, "cc-test-tpl");
        first.setTitle("v1 title");
        first.getValues().put("msg", "first");
        Assert.assertTrue(content.saveContent(first));
        final String id = first.getId();

        final ContentInstance edited = content.getContent(id);
        edited.setTitle("v2 title");
        edited.getValues().put("msg", "second");
        Assert.assertTrue(content.saveContent(edited));

        Assert.assertEquals(content.getHistory(id).stream().map(ContentInstance::getVersion).toList(),
                List.of(2, 1));
        Assert.assertTrue(content.restoreContent(id, 1));
        final ContentInstance restored = content.getContent(id);
        Assert.assertEquals(restored.getVersion(), 3, "a restore is a new version, not a rewind");
        Assert.assertEquals(restored.getValues().get("msg"), "first");
        Assert.assertFalse(content.restoreContent(id, 99));
        Assert.assertFalse(content.restoreContent("no-such-id", 1));
        Assert.assertFalse(as(NOBODY, false).restoreContent(id, 1), "restore is privilege-gated too");
        Assert.assertTrue(content.getHistory("no-such-id").isEmpty());
    }

    @Test
    public void renderUsesThePinnedVersionWithFallback() {
        final ContentCommands content = as(ADMIN, false);
        final String section = "cc.section-render";
        final ContentInstance pinned = content.createContent(section, "cc-test-tpl");
        pinned.getValues().put("msg", "hello");
        Assert.assertTrue(content.saveContent(pinned));
        Assert.assertEquals(content.render(pinned), "<p>hello</p>");

        // Advance the template; the pinned instance must keep rendering its own version's body.
        final ContentTemplate tpl = DAO.getInstance().getTemplate("cc-test-tpl").orElseThrow();
        tpl.setBody("<h1>{{msg}}</h1>");
        Assert.assertTrue(DAO.getInstance().saveTemplate(tpl, 5));
        Assert.assertEquals(content.render(content.getContent(pinned.getId())), "<p>hello</p>");

        // An instance pointing at a vanished template renders nothing rather than erroring the page.
        final ContentInstance orphan = content.createContent(section, "gone-template");
        Assert.assertEquals(content.render(orphan), "");
        Assert.assertEquals(content.render(null), "");
    }

    @Test
    public void getContentAnswersBlankForUnknownIds() {
        final ContentCommands content = as(ADMIN, false);
        final ContentInstance blank = content.getContent("never-saved");
        Assert.assertNotNull(blank);
        Assert.assertNotNull(blank.getId());
        Assert.assertNull(blank.getSection(), "a blank has no section, so it can never be saved by accident");
    }

    @Test
    public void getForSectionToleratesBlankSections() {
        final ContentCommands content = as(ADMIN, false);
        Assert.assertTrue(content.getForSection(" ").isEmpty());
        Assert.assertTrue(content.getAllForSection(null).isEmpty());
    }
}
