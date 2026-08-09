package org.paulsens.trip.action;

import java.util.ArrayList;
import java.util.List;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.content.StarterTemplates;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Placeholder;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/** The template manager's contracts: privilege gating, referenced-delete refusal, starters, detection. */
public class TemplateCommandsTest {

    @BeforeClass
    public void warmUp() {
        DAO.getInstance();     // seeds the starters and the fake content that references text-only
        // Other suite tests clear caches; locally a cleared partition never rebuilds, so the starter rows
        // (still in the store) vanish from list reads. Re-saving each stored current -- a version-matching
        // save -- puts them back in the cache.
        for (final String id : StarterTemplates.IDS) {
            DAO.getInstance().getTemplateRecord(id)
                    .ifPresent(rec -> DAO.getInstance().saveTemplate(rec.getCurrent(), 5));
        }
    }

    private static TemplateCommands as(final boolean siteAdmin) {
        final TemplateCommands commands = new TemplateCommands();
        commands.setCallerSource(() -> new Caller(
                Person.Id.from("tpl-tester"), siteAdmin,
                new AuditActor("tpl-tester@example.com", "tpl-tester"), new PrivilegeCommands()));
        return commands;
    }

    @Test
    public void savesRequireContentAdmin() {
        final TemplateCommands denied = as(false);
        final ContentTemplate tpl = new ContentTemplate("tc-denied", 0, "X", null, "{{a}}",
                List.of(new Placeholder("a", Placeholder.Type.TEXT, "A", null, false)), null, null);
        Assert.assertFalse(denied.saveTemplate(tpl));
        Assert.assertFalse(denied.deleteTemplate("anything"));
        Assert.assertEquals(denied.installStarterTemplates(), 0);

        final TemplateCommands admin = as(true);
        Assert.assertTrue(admin.saveTemplate(tpl));
        Assert.assertEquals(tpl.getVersion(), 1);
        Assert.assertFalse(admin.saveTemplate(null));
        final ContentTemplate blankId = new ContentTemplate(" ", 0, "X", null, "b", null, null, null);
        Assert.assertFalse(admin.saveTemplate(blankId));
    }

    @Test
    public void referencedTemplatesRefuseToDelete() {
        // FakeData's intro/event instances reference text-only -- deleting it would break published pages.
        final TemplateCommands admin = as(true);
        Assert.assertFalse(admin.deleteTemplate(StarterTemplates.TEXT_ONLY_ID));
        Assert.assertTrue(DAO.getInstance().getTemplate(StarterTemplates.TEXT_ONLY_ID).isPresent());
        Assert.assertFalse(admin.deleteTemplate(" "));
    }

    @Test
    public void unreferencedTemplatesDeleteAndReinstall() {
        final TemplateCommands admin = as(true);
        // Nothing references the image starter; it deletes -- and install restores exactly the missing one.
        Assert.assertEquals(admin.installStarterTemplates(), 0, "all starters present already");
        Assert.assertTrue(admin.deleteTemplate(StarterTemplates.IMAGE_ID));
        Assert.assertTrue(DAO.getInstance().getTemplate(StarterTemplates.IMAGE_ID).isEmpty());
        Assert.assertEquals(admin.installStarterTemplates(), 1);
        Assert.assertTrue(DAO.getInstance().getTemplate(StarterTemplates.IMAGE_ID).isPresent());
    }

    @Test
    public void unknownTemplateAnswersBlankUnderThatId() {
        final TemplateCommands admin = as(true);
        final ContentTemplate blank = admin.getTemplate("tc-never-saved");
        Assert.assertEquals(blank.getId(), "tc-never-saved");
        Assert.assertEquals(blank.getVersion(), 0);
        Assert.assertNotNull(admin.getTemplate(null));
        Assert.assertEquals(admin.getTemplate("tc-never-saved", 3).getId(), "tc-never-saved");
    }

    @Test
    public void historyAndRestore() {
        final TemplateCommands admin = as(true);
        final ContentTemplate tpl = new ContentTemplate("tc-history", 0, "H", null, "one {{a}}",
                List.of(new Placeholder("a", Placeholder.Type.TEXT, "A", null, false)), null, null);
        Assert.assertTrue(admin.saveTemplate(tpl));
        final ContentTemplate edit = admin.getTemplate("tc-history");
        edit.setBody("two {{a}}");
        Assert.assertTrue(admin.saveTemplate(edit));

        Assert.assertEquals(admin.getHistory("tc-history").stream().map(ContentTemplate::getVersion).toList(),
                List.of(2, 1));
        Assert.assertTrue(admin.restoreTemplate("tc-history", 1));
        final ContentTemplate restored = admin.getTemplate("tc-history");
        Assert.assertEquals(restored.getVersion(), 3, "restore is a new version");
        Assert.assertEquals(restored.getBody(), "one {{a}}");
        Assert.assertFalse(admin.restoreTemplate("tc-history", 99));
        Assert.assertFalse(admin.restoreTemplate("no-such", 1));
        Assert.assertTrue(admin.getHistory("no-such").isEmpty());
    }

    @Test
    public void getVersionFallsBackToLatest() {
        final TemplateCommands admin = as(true);
        final ContentTemplate current = admin.getTemplate(StarterTemplates.YOUTUBE_VIDEO_ID, 999);
        Assert.assertEquals(current.getId(), StarterTemplates.YOUTUBE_VIDEO_ID,
                "an aged-out pinned version answers the latest rather than nothing");
        Assert.assertTrue(current.getVersion() >= 1);
    }

    @Test
    public void detectPlaceholdersAppendsOnlyMissingOnes() {
        final TemplateCommands admin = as(true);
        final ContentTemplate tpl = new ContentTemplate("tc-detect", 0, "D", null,
                "{{known}} and {{fresh}} and {{fresh}}",
                new ArrayList<>(List.of(
                        new Placeholder("known", Placeholder.Type.URL, "Known", null, true))),
                null, null);
        admin.detectPlaceholders(tpl);
        Assert.assertEquals(tpl.getPlaceholders().size(), 2, "one existing + one detected, deduped");
        final Placeholder detected = tpl.getPlaceholders().get(1);
        Assert.assertEquals(detected.getName(), "fresh");
        Assert.assertEquals(detected.getType(), Placeholder.Type.TEXT, "detected placeholders default TEXT");
        final Placeholder known = tpl.getPlaceholders().get(0);
        Assert.assertEquals(known.getType(), Placeholder.Type.URL, "existing declarations untouched");
        admin.detectPlaceholders(null);     // must not throw
    }

    @Test
    public void placeholderTypesForTheMenu() {
        Assert.assertEquals(as(true).getPlaceholderTypes(), List.of(Placeholder.Type.values()));
    }

    @Test
    public void templatesListIncludesStarters() {
        final List<String> ids = as(true).getTemplates().stream().map(ContentTemplate::getId).toList();
        Assert.assertTrue(ids.contains(StarterTemplates.TEXT_ONLY_ID), "" + ids);
        Assert.assertTrue(ids.contains(StarterTemplates.YOUTUBE_VIDEO_ID), "" + ids);
    }
}
