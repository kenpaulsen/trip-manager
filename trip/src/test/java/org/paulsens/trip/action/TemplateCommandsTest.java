package org.paulsens.trip.action;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.content.ProgrammaticTypes;
import org.paulsens.trip.content.StarterTemplates;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Placeholder;
import org.paulsens.trip.model.TemplateKind;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.paulsens.trip.cache.Cached;

/** The template manager's contracts: privilege gating, referenced-delete refusal, starters, detection. */
public class TemplateCommandsTest {

    @BeforeClass
    public void warmUp() {
        DAO.getInstance();     // seeds the starters and the fake content that references text-only
        // Other suite tests clear caches; locally a cleared partition never rebuilds, so the starter rows
        // (still in the store) vanish from list reads. Re-saving each stored current -- a version-matching
        // save -- puts them back in the cache.
        for (final String id : StarterTemplates.IDS) {
            DAO.getInstance().getTemplateRecord(id, Cached.NO)
                    .ifPresent(rec -> DAO.getInstance().saveTemplate(rec.getCurrent(), 5));
        }
    }

    @Test
    public void aTemplateIsSharedOrOwnedByOneRealOrganization() {
        final TemplateCommands admin = as(true);
        final ContentTemplate template = new ContentTemplate("scope-" + System.nanoTime(), 0, "Scoped", null,
                "<p>{{msg}}</p>", List.of(new Placeholder("msg", Placeholder.Type.TEXT, "Msg", null, true)),
                null, null);
        template.setOrgId("");
        Assert.assertTrue(admin.saveTemplate(template), "the Scope menu's 'shared' choice submits blank");
        Assert.assertNull(template.getOrgId(), "...stored as null, the pre-org-sites row shape");
        Assert.assertEquals(admin.scopeLabel(template), "Shared");
        Assert.assertEquals(admin.scopeLabel(null), "Shared");

        template.setOrgId("no-such-org");
        Assert.assertFalse(admin.saveTemplate(template), "an owner that is not an organization is refused");

        template.setOrgId(FakeData.ACME_ORG_ID);
        Assert.assertTrue(admin.saveTemplate(template));
        Assert.assertEquals(admin.getTemplate(template.getId()).getOrgId(), FakeData.ACME_ORG_ID);
        Assert.assertEquals(admin.scopeLabel(template), "Acme Inc");
        Assert.assertTrue(admin.getScopeChoices().stream()
                .anyMatch(org -> FakeData.ACME_ORG_ID.equals(org.getId().getValue())));
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
    public void kindFilteredListingNarrowsAndFailsOpen() {
        final TemplateCommands admin = as(true);
        final List<ContentTemplate> mailOnly = admin.getTemplates("MAIL");
        Assert.assertFalse(mailOnly.isEmpty(), "the three MAIL starters exist");
        Assert.assertTrue(mailOnly.stream().allMatch(t -> t.getKind() == TemplateKind.MAIL));
        Assert.assertTrue(mailOnly.size() < admin.getTemplates().size(), "a real narrowing, not the full list");
        // Lower case works (the menu link passes MAIL, but hand-typed URLs happen).
        Assert.assertEquals(admin.getTemplates("mail"), mailOnly);
        // Blank, null, and bogus all fail OPEN to the full list -- an empty ?kind= arrives as "".
        Assert.assertEquals(admin.getTemplates(""), admin.getTemplates());
        Assert.assertEquals(admin.getTemplates(null), admin.getTemplates());
        Assert.assertEquals(admin.getTemplates("NOT_A_KIND"), admin.getTemplates());
    }

    @Test
    public void referencedTemplatesRefuseToDelete() {
        // FakeData's intro/event instances reference text-only -- deleting it would break published pages.
        final TemplateCommands admin = as(true);
        Assert.assertFalse(admin.deleteTemplate(StarterTemplates.TEXT_ONLY_ID));
        Assert.assertTrue(DAO.getInstance().getTemplate(StarterTemplates.TEXT_ONLY_ID, Cached.NO).isPresent());
        Assert.assertFalse(admin.deleteTemplate(" "));
    }

    @Test
    public void unreferencedTemplatesDeleteAndReinstall() {
        final TemplateCommands admin = as(true);
        // Nothing references the image starter; it deletes -- and install restores exactly the missing one.
        Assert.assertEquals(admin.installStarterTemplates(), 0, "all starters present already");
        Assert.assertTrue(admin.deleteTemplate(StarterTemplates.IMAGE_ID));
        Assert.assertTrue(DAO.getInstance().getTemplate(StarterTemplates.IMAGE_ID, Cached.NO).isEmpty());
        Assert.assertEquals(admin.installStarterTemplates(), 1);
        Assert.assertTrue(DAO.getInstance().getTemplate(StarterTemplates.IMAGE_ID, Cached.NO).isPresent());
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
        // CHOICE, MULTI_CHOICE and PERSON are dialog-meaningless without a programmatic type behind them,
        // so the menu excludes all three.
        final List<Placeholder.Type> types = as(true).getPlaceholderTypes();
        Assert.assertFalse(types.contains(Placeholder.Type.CHOICE));
        Assert.assertFalse(types.contains(Placeholder.Type.MULTI_CHOICE));
        Assert.assertFalse(types.contains(Placeholder.Type.PERSON));
        Assert.assertEquals(types.size(), Placeholder.Type.values().length - 3);
    }

    @Test
    public void kindIsImmutableAfterCreation() {
        final TemplateCommands admin = as(true);
        final ContentTemplate created = new ContentTemplate("tpl-kind-lock", 0, "Kind Lock", null, "",
                List.of(), null, null, TemplateKind.CONTAINER, null, null, null);
        Assert.assertTrue(admin.saveTemplate(created));

        final ContentTemplate flipped = admin.getTemplate("tpl-kind-lock");
        flipped.setKind(TemplateKind.STANDARD);
        flipped.setBody("<p>now standard?</p>");
        Assert.assertFalse(admin.saveTemplate(flipped), "kind may not change after creation");
        Assert.assertTrue(admin.deleteTemplate("tpl-kind-lock"));
    }

    @Test
    public void containerConfigIsValidated() {
        final TemplateCommands admin = as(true);
        final ContentTemplate zeroMax = new ContentTemplate("tpl-bad-max", 0, "Bad Max", null, "",
                List.of(), null, null, TemplateKind.CONTAINER, null, 0, null);
        Assert.assertFalse(admin.saveTemplate(zeroMax), "a zero child limit is nonsense");

        final ContentTemplate nested = new ContentTemplate("tpl-nested", 0, "Nested", null, "",
                List.of(), null, null, TemplateKind.CONTAINER,
                List.of(StarterTemplates.CONTAINER_ID), null, null);
        Assert.assertFalse(admin.saveTemplate(nested), "containers may not allow containers");

        // A container body is the row wrapped around each child, so it must say where the child renders:
        // without the slot every child would silently vanish from the page.
        final ContentTemplate slotless = new ContentTemplate("tpl-slotless", 0, "Slotless", null,
                "<p>no slot here</p>", List.of(), null, null, TemplateKind.CONTAINER, null, null, null);
        Assert.assertFalse(admin.saveTemplate(slotless), "a body must mark where the child goes");

        final ContentTemplate unbalanced = new ContentTemplate("tpl-unbalanced", 0, "Unbalanced", null,
                "<li>{{child}}", List.of(), null, null, TemplateKind.CONTAINER, null, null, null);
        Assert.assertFalse(admin.saveTemplate(unbalanced), "a row is validated like any other fragment");

        // The once-around wrapper: an unclosed region would emit a stray <ul> into the page.
        final ContentTemplate halfRegion = new ContentTemplate("tpl-half-region", 0, "Half", null,
                "<ul>{{children:start}}<li>{{child}}</li></ul>",
                List.of(), null, null, TemplateKind.CONTAINER, null, null, null);
        Assert.assertFalse(admin.saveTemplate(halfRegion), "{{children:start}} must be closed");

        final String wrapped = "<ul class=\"ev\">{{children:start}}<li>{{child:title}}{{child}}</li>"
                + "{{children:end}}</ul>";
        final ContentTemplate wrapping = new ContentTemplate("tpl-wrapped", 0, "Wrapped", null, wrapped,
                List.of(), null, null, TemplateKind.CONTAINER, null, null, null);
        Assert.assertTrue(admin.saveTemplate(wrapping), "a paired region carrying a wrapper saves");
        Assert.assertEquals(admin.getTemplate("tpl-wrapped").getBody(), wrapped);
        Assert.assertTrue(admin.deleteTemplate("tpl-wrapped"));

        final String row = "<li class=\"ev\"><h4>{{child:title}}</h4>{{child}}</li>";
        final ContentTemplate sound = new ContentTemplate("tpl-good-container", 0, "Good", null,
                row, List.of(), null, null, TemplateKind.CONTAINER,
                List.of(StarterTemplates.TEXT_ONLY_ID), 3, null);
        Assert.assertTrue(admin.saveTemplate(sound));
        Assert.assertEquals(admin.getTemplate("tpl-good-container").getBody(), row,
                "the container's row is kept verbatim");
        Assert.assertTrue(admin.getTemplate("tpl-good-container").getPlaceholders().isEmpty(),
                "a container declares no placeholders of its own -- it reads the child's");

        final ContentTemplate blank = new ContentTemplate("tpl-blank-container", 0, "Blank", null, "",
                List.of(), null, null, TemplateKind.CONTAINER, null, null, null);
        Assert.assertTrue(admin.saveTemplate(blank), "blank means the built-in row");
        Assert.assertTrue(admin.deleteTemplate("tpl-blank-container"));
        Assert.assertTrue(admin.getChildTemplateChoices().stream()
                .noneMatch(t -> t.getKind() == TemplateKind.CONTAINER),
                "the allowed-children picker never offers containers");
        Assert.assertTrue(admin.deleteTemplate("tpl-good-container"));
    }

    @Test
    public void programmaticTemplatesCopyTheirTypeProperties() {
        final TemplateCommands admin = as(true);
        final ContentTemplate unknown = new ContentTemplate("tpl-bad-type", 0, "Bad Type", null, "",
                List.of(), null, null, TemplateKind.PROGRAMMATIC, null, null, "no-such-type");
        Assert.assertFalse(admin.saveTemplate(unknown), "the type must be registered");

        final ContentTemplate created = new ContentTemplate("tpl-pilg-copy", 0, "Pilg Copy", null, "",
                List.of(), null, null, TemplateKind.PROGRAMMATIC, null, null, "pilgrimages");
        Assert.assertTrue(admin.saveTemplate(created));
        Assert.assertFalse(admin.getTemplate("tpl-pilg-copy").getPlaceholders().isEmpty(),
                "the type's properties become the placeholders on create");
        Assert.assertTrue(admin.deleteTemplate("tpl-pilg-copy"));
    }

    /**
     * A programmatic row written against an older registry (production's starters predate
     * {@code includeOrgs}) reads with its type's LIVE properties in the manager, the row itself is left
     * alone by reading, and the next save writes the live list back -- the stored list is advisory.
     */
    @Test
    public void programmaticRowsReadTheirTypesLivePropertiesAndCatchUpOnSave() {
        final TemplateCommands admin = as(true);
        final ContentTemplate stale = new ContentTemplate("tpl-stale-albums", 0, "Stale Albums", null, "",
                List.of(new Placeholder("windowDays", Placeholder.Type.TEXT, "Window", null, false)),
                null, null, TemplateKind.PROGRAMMATIC, null, null, "photo-albums");
        Assert.assertTrue(DAO.getInstance().saveTemplate(stale, 5));
        final List<String> live = ProgrammaticTypes.byId("photo-albums").orElseThrow().getProperties().stream()
                .map(Placeholder::getName).toList();

        final ContentTemplate shown = admin.getTemplate("tpl-stale-albums");
        Assert.assertEquals(shown.getPlaceholders().stream().map(Placeholder::getName).toList(), live,
                "the manager's copy carries the live list");
        Assert.assertEquals(admin.getTemplate("tpl-stale-albums", 1).getPlaceholders().stream()
                .map(Placeholder::getName).toList(), live, "so does a specific retained version");
        Assert.assertEquals(DAO.getInstance().getTemplate("tpl-stale-albums", Cached.NO).orElseThrow()
                .getPlaceholders().size(), 1, "reading rewrites nothing");

        shown.setPlaceholders(List.of());   // whatever the editor submits, the type decides
        Assert.assertTrue(admin.saveTemplate(shown));
        Assert.assertEquals(DAO.getInstance().getTemplate("tpl-stale-albums", Cached.NO).orElseThrow()
                .getPlaceholders().stream().map(Placeholder::getName).toList(), live,
                "a save writes the live list into the row");
        Assert.assertTrue(admin.deleteTemplate("tpl-stale-albums"));
    }

    @Test
    public void brokenTemplateBodiesAreRejected() {
        final TemplateCommands admin = as(true);
        final ContentTemplate broken = new ContentTemplate("tpl-broken-body", 0, "Broken", null,
                "<div><p>never closed</div>", List.of(), null, null);
        Assert.assertFalse(admin.saveTemplate(broken), "a structurally broken body must not save");
        broken.setBody("<div><p>fine</p></div>");
        Assert.assertTrue(admin.saveTemplate(broken));
        Assert.assertTrue(admin.deleteTemplate("tpl-broken-body"));
    }

    @Test
    public void aRichTextTokenInsideAParagraphWarnsButStillSaves() {
        final TemplateCommands admin = as(true);
        final ContentTemplate nested = new ContentTemplate("tc-rich-in-p", 0, "Nested", null,
                "<p><img src=\"{{imageUrl}}\">{{caption}}</p>",
                List.of(new Placeholder("imageUrl", Placeholder.Type.IMAGE_URL, "Image", null, false),
                        new Placeholder("caption", Placeholder.Type.RICH_TEXT, "Caption", null, false)),
                null, null);
        // Advisory only: the body is valid HTML, and an existing template must never become unsaveable.
        // The warning itself needs a FacesContext, so the message text is covered by RichTextRulesTest.
        Assert.assertTrue(admin.saveTemplate(nested));
        Assert.assertEquals(nested.getVersion(), 1);
        Assert.assertTrue(admin.deleteTemplate("tc-rich-in-p"));
    }

    @Test
    public void templatesListIncludesStarters() {
        final List<String> ids = as(true).getTemplates().stream().map(ContentTemplate::getId).toList();
        Assert.assertTrue(ids.contains(StarterTemplates.TEXT_ONLY_ID), "" + ids);
        Assert.assertTrue(ids.contains(StarterTemplates.YOUTUBE_VIDEO_ID), "" + ids);
    }

    @Test
    public void tagsAreNormalizedOnSaveAndOfferedBackForReuse() {
        Assert.assertEquals(TemplateCommands.normalizeTags(null), List.of());
        final List<String> typed = new ArrayList<>(List.of("  Hero ", "HERO", "", "full   width", "x".repeat(45)));
        typed.add(null);
        Assert.assertEquals(TemplateCommands.normalizeTags(typed),
                List.of("hero", "full width", "x".repeat(TemplateCommands.MAX_TAG_LENGTH)),
                "trimmed, lower-cased, collapsed, deduplicated, cut to length");
        final List<String> many = new ArrayList<>();
        for (int i = 0; i < TemplateCommands.MAX_TAGS + 5; i++) {
            many.add("tag" + i);
        }
        Assert.assertEquals(TemplateCommands.normalizeTags(many).size(), TemplateCommands.MAX_TAGS);

        final TemplateCommands admin = as(true);
        final ContentTemplate tpl = new ContentTemplate("tc-tags-" + System.nanoTime(), 0, "Tagged", null, "{{a}}",
                List.of(new Placeholder("a", Placeholder.Type.TEXT, "A", null, false)), null, null);
        tpl.setTags(List.of(" Zebra-Crossing ", "zebra-crossing", "Okapi"));
        tpl.setChildOnly(true);
        tpl.setFullWidth(true);
        Assert.assertTrue(admin.saveTemplate(tpl));
        final ContentTemplate stored = admin.getTemplate(tpl.getId());
        Assert.assertEquals(stored.getTags(), List.of("zebra-crossing", "okapi"));
        Assert.assertTrue(stored.isChildOnly() && stored.isFullWidth(), "a section template keeps both flags");

        Assert.assertEquals(admin.completeTag("ZEBRA"), List.of("zebra", "zebra-crossing"),
                "what was typed, as it would be stored, then the tag in use that holds it");
        Assert.assertEquals(admin.completeTag(" Zebra-Crossing"), List.of("zebra-crossing"), "a tag in use, once");
        final List<String> crossings = admin.completeTag("cross");
        Assert.assertEquals(crossings.get(0), "cross", "the typed word leads, so one Enter takes it");
        Assert.assertTrue(crossings.contains("zebra-crossing"), crossings.toString());
        final List<String> all = admin.completeTag(null);
        Assert.assertTrue(all.containsAll(List.of("okapi", "zebra-crossing", "hero", "full-width")), all.toString());
        Assert.assertEquals(all, all.stream().sorted().distinct().toList(), "each once, in order");
        Assert.assertEquals(admin.completeTag("no-tag-holds-this"), List.of("no-tag-holds-this"),
                "a new tag is offered as itself");
        final List<String> startsFirst = admin.completeTag("o");
        Assert.assertTrue(startsFirst.indexOf("okapi") < startsFirst.indexOf("zebra-crossing"),
                "a tag that starts with what was typed comes before one that only holds it: " + startsFirst);
    }

    @Test
    public void listingFlagsAreClearedWhereTheyCannotApply() {
        final TemplateCommands admin = as(true);
        final ContentTemplate mail = new ContentTemplate("tc-mail-flags-" + System.nanoTime(), 0, "Subject", null,
                "<p>x</p>", List.of(), null, null, TemplateKind.MAIL, null, null, null);
        mail.setTags(List.of("hero"));
        mail.setChildOnly(true);
        mail.setFullWidth(true);
        Assert.assertTrue(admin.saveTemplate(mail));
        final ContentTemplate storedMail = admin.getTemplate(mail.getId());
        Assert.assertTrue(storedMail.getTags().isEmpty(), "no Add dialog offers an email body");
        Assert.assertFalse(storedMail.isChildOnly() || storedMail.isFullWidth());

        final ContentTemplate group = new ContentTemplate("tc-group-flags-" + System.nanoTime(), 0, "Group", null, "",
                List.of(), null, null, TemplateKind.CONTAINER, null, null, null);
        group.setChildOnly(true);
        group.setFullWidth(true);
        Assert.assertTrue(admin.saveTemplate(group));
        final ContentTemplate storedGroup = admin.getTemplate(group.getId());
        Assert.assertFalse(storedGroup.isChildOnly(), "a group can never sit inside a group");
        Assert.assertTrue(storedGroup.isFullWidth(), "but it may be a band");
    }

    @Test
    public void usageCountsCurrentItemsPerTemplateWithinTheScope() {
        final String id = "tc-usage-" + System.nanoTime();
        Assert.assertTrue(DAO.getInstance().saveTemplate(new ContentTemplate(id, 0, "Counted", null, "<p>{{a}}</p>",
                List.of(new Placeholder("a", Placeholder.Type.TEXT, "A", null, false)), null, null), 5));
        // Pages of this test's own, so no other test's page gains a row: one of no organization, one of Acme's.
        final String acmePage = "page:org:" + FakeData.ACME_ORG_ID + ":" + id;
        saveItem(id + "-shared", "page:shared:" + id, id);
        saveItem(id + "-acme", acmePage, id);
        // A group on Acme's page with one item inside: the item's organization comes from its group's row.
        saveItem(id + "-group", acmePage, StarterTemplates.CONTAINER_ID);
        saveItem(id + "-inside", id + "-group", id);

        final TemplateCommands admin = as(true);
        Assert.assertEquals(admin.usageCounts(null).get(id), Integer.valueOf(3), "every page");
        Assert.assertEquals(admin.usageCounts().get(id), Integer.valueOf(3), "the shared site has no scope of its own");
        Assert.assertEquals(admin.usageCounts(FakeData.ACME_ORG_ID).get(id), Integer.valueOf(2),
                "an organization's pages only, its groups' items included");
        Assert.assertNull(admin.usageCounts("no-such-org").get(id));
        Assert.assertNull(admin.usageCounts(null).get("no-such-template"), "an unused template has no entry");
        Assert.assertEquals(admin.usedBy(id), 3, "one template's count, for the Add drawer");
        Assert.assertEquals(admin.usedBy("no-such-template"), 0);
        Assert.assertEquals(admin.usedBy(null), 0);
        Assert.assertTrue(admin.usageCounts(null).get(StarterTemplates.TEXT_ONLY_ID) > 0, "the seeded page uses it");
        Assert.assertTrue(as(false).usageCounts(null).isEmpty(), "site-wide counts are for site staff");
        Assert.assertTrue(as(false).usageCounts(FakeData.ACME_ORG_ID).isEmpty(), "and an org's for its own editors");
    }

    private static void saveItem(final String id, final String section, final String templateId) {
        Assert.assertTrue(DAO.getInstance().saveContent(new ContentInstance(id, section, "Usage " + id, templateId, 1,
                new HashMap<>(), null, 0, 0, null, "tpl-tester"), 5), id);
    }

    @Test
    public void aDeepLinkOpensABlankTemplateOrOneTheCallerMayAuthor() {
        final TemplateCommands admin = as(true);
        final ContentTemplate fresh = admin.deepLinkTemplate("1", null);
        Assert.assertEquals(fresh.getId(), "", "what the New Template button starts from");
        Assert.assertEquals(fresh.getVersion(), 0);
        final ContentTemplate edit = admin.deepLinkTemplate(null, StarterTemplates.TEXT_ONLY_ID);
        Assert.assertEquals(edit.getId(), StarterTemplates.TEXT_ONLY_ID);
        Assert.assertTrue(edit.getVersion() > 0, "the stored row, as an editable copy");
        Assert.assertEquals(admin.deepLinkTemplate("1", StarterTemplates.TEXT_ONLY_ID).getId(), "", "new wins");

        Assert.assertNull(admin.deepLinkTemplate(null, null), "no deep link at all");
        Assert.assertNull(admin.deepLinkTemplate("0", " "));
        Assert.assertNull(admin.deepLinkTemplate("", "no-such-template"), "an unknown id opens nothing");
        Assert.assertNull(as(false).deepLinkTemplate(null, StarterTemplates.TEXT_ONLY_ID),
                "a row the caller may only read opens nothing");
    }
}
