package org.paulsens.trip.action;

import java.time.LocalDateTime;
import java.util.List;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.content.ProgrammaticTypes;
import org.paulsens.trip.content.StarterTemplates;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.ContentRecord;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Placeholder;
import org.paulsens.trip.model.TemplateKind;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.paulsens.trip.cache.Cached;

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
        // Other suite tests clear caches, and locally a cleared partition never rebuilds -- while the fake
        // ROWS survive in the store, so re-running addFakeData skips them and the cache stays empty. The
        // documented remedy (see TemplateCommandsTest.warmUp): re-save every stored current, a
        // version-matching save that puts each row back into its partition.
        FakeData.addFakeData();
        DAO.getInstance().getAllContentRecords(Cached.NO).stream()
                .map(ContentRecord::getCurrent)
                .filter(current -> current != null)
                .forEach(current -> DAO.getInstance().saveContent(current, 5));
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

    /**
     * Production's pilgrimage and photo-album starters were installed before their types declared
     * {@code includeOrgs}, and the content dialog iterated the STORED row's placeholders, so the
     * "Organizations shown" menu never appeared on visitqueenofpeace.com (2026-09-01). Whatever a
     * programmatic row carries, the prompts the dialog reads -- and the values a new instance seeds --
     * come from the registry.
     */
    @Test
    public void programmaticPromptsComeFromTheRegistryNotTheStoredRow() {
        final ContentCommands admin = as(ADMIN, false);
        final ContentTemplate stale = new ContentTemplate("cc-stale-pilgrimages", 0, "Stale Pilgrimages",
                null, "", List.of(new Placeholder("language", Placeholder.Type.CHOICE, "Language", null, true)),
                null, null, TemplateKind.PROGRAMMATIC, null, null, "pilgrimages");
        Assert.assertTrue(DAO.getInstance().saveTemplate(stale, 5), "the DAO stores the row as given");

        final ContentInstance section = admin.createContent("cc.section-stale", "cc-stale-pilgrimages");
        Assert.assertTrue(section.getValues().containsKey("includeOrgs"),
                "a new instance seeds a value for the live property");
        final List<String> names = admin.placeholdersOf(section).stream().map(Placeholder::getName).toList();
        Assert.assertEquals(names, ProgrammaticTypes.byId("pilgrimages").orElseThrow().getProperties().stream()
                .map(Placeholder::getName).toList(), "the dialog's prompts are the type's live list");
        Assert.assertTrue(names.contains("includeOrgs") && names.contains("cfpwOnly"),
                "the org curation list AND the CFPW-only provider menu are both offered: " + names);
        Assert.assertTrue(admin.placeholdersOf(admin.getContent("fake-albums")).stream()
                        .anyMatch(ph -> ph.getName().equals("includeOrgs")
                                && ph.getType() == Placeholder.Type.MULTI_CHOICE),
                "the photo-albums section offers the same checkbox menu");

        // Everything else is unchanged: STANDARD prompts are the stored list; nothing resolves to nothing.
        Assert.assertEquals(admin.placeholdersOf(admin.createContent("cc.section-stale", "cc-test-tpl"))
                .stream().map(Placeholder::getName).toList(), List.of("msg"));
        Assert.assertTrue(admin.placeholdersOf(null).isEmpty());
        Assert.assertTrue(admin.placeholdersOf(admin.createContent("cc.section-stale", "no-such-template"))
                .isEmpty());
        Assert.assertTrue(DAO.getInstance().deleteTemplate("cc-stale-pilgrimages"));
    }

    @Test
    public void aNewContainerStartsWithItsTemplatesAllowList() {
        final ContentCommands content = as(ADMIN, false);
        final ContentTemplate restricted = new ContentTemplate("cc-restricted-holder", 0, "Holder", null, "",
                List.of(), null, null, TemplateKind.CONTAINER, List.of("text-only", "image"), null, null);
        Assert.assertTrue(DAO.getInstance().saveTemplate(restricted, 5));
        final ContentInstance created = content.createContent("cc.section-holder", "cc-restricted-holder");
        Assert.assertEquals(created.getAllowedChildTemplateIds(), List.of("text-only", "image"),
                "the dialog shows what the template decided, ready to override");
        Assert.assertEquals(content.createContent("cc.section-holder", StarterTemplates.CONTAINER_ID)
                .getAllowedChildTemplateIds(), null, "an unrestricted container template starts empty");
        Assert.assertNull(content.createContent("cc.section-holder", "cc-test-tpl").getAllowedChildTemplateIds(),
                "not a container: nothing to inherit");
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
        // 'events' is the seeded CONTAINER instance whose editorPrivilege is eventAdmin: its id is the
        // section key of its children, and holding the privilege grants exactly that scope.
        final ContentCommands nobody = as(NOBODY, false);
        final ContentInstance denied = nobody.createContent("events", "cc-test-tpl");
        denied.setTitle("denied");
        Assert.assertFalse(nobody.saveContent(denied), "no privilege, no save");

        final ContentCommands eventsOnly = as(EVENTS_ONLY, false);
        final ContentInstance event = eventsOnly.createContent("events", "cc-test-tpl");
        event.setTitle("cc-event-admin-save");
        event.getValues().put("msg", "an event");
        Assert.assertTrue(eventsOnly.saveContent(event), "eventAdmin may edit the events container");
        final ContentInstance pageRow = eventsOnly.createContent(FakeData.PAGE_KEY, "cc-test-tpl");
        Assert.assertFalse(eventsOnly.saveContent(pageRow), "eventAdmin may NOT edit page-level sections");
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
        Assert.assertTrue(content.canEdit("events", ADMIN));
        Assert.assertTrue(content.canEdit(FakeData.PAGE_KEY, ADMIN));
        Assert.assertTrue(content.canEdit("events", EVENTS_ONLY), "container editorPrivilege grants edit");
        Assert.assertFalse(content.canEdit(FakeData.PAGE_KEY, EVENTS_ONLY));
        Assert.assertFalse(content.canEdit("docs", EVENTS_ONLY), "another container's privilege differs");
        Assert.assertFalse(content.canEdit("events", NOBODY));
        Assert.assertFalse(content.canEdit(null, ADMIN));
        Assert.assertFalse(content.canEdit("events", null));
        // A site administrator passes without any privilege row, matching the save path's short-circuit.
        Assert.assertTrue(as(NOBODY, true).canEdit(FakeData.PAGE_KEY, NOBODY));
    }

    @Test
    public void canEditPageIncludesContainerEditors() {
        final ContentCommands content = as(ADMIN, false);
        Assert.assertTrue(content.canEditPage(FakeData.PAGE_KEY, ADMIN));
        Assert.assertTrue(content.canEditPage(FakeData.PAGE_KEY, EVENTS_ONLY),
                "a container editor gets the Edit-page button even without contentAdmin");
        Assert.assertFalse(content.canEditPage(FakeData.PAGE_KEY, NOBODY));
        Assert.assertFalse(content.canEditPage(FakeData.PAGE_KEY, null));
        Assert.assertFalse(content.canEditPage(null, EVENTS_ONLY));
    }

    @Test
    public void containerConfigFieldsAreContentAdminOnly() {
        final ContentCommands eventsOnly = as(EVENTS_ONLY, false);
        final ContentInstance sneak = eventsOnly.createContent("events", "cc-test-tpl");
        sneak.setTitle("sneaky");
        sneak.getValues().put("msg", "x");
        sneak.setEditorPrivileges(List.of("eventAdmin"));   // an editor may not mint privileges
        sneak.setAllowedChildTemplateIds(List.of("cc-test-tpl"));
        Assert.assertTrue(eventsOnly.saveContent(sneak));
        final ContentInstance stored = as(ADMIN, false).getContent(sneak.getId());
        Assert.assertNull(stored.getEditorPrivileges(),
                "a non-contentAdmin save must not establish editor privileges");
        Assert.assertNull(stored.getAllowedChildTemplateIds(),
                "nor a child allow-list");
        Assert.assertTrue(as(ADMIN, false).deleteContent(sneak.getId()));
    }

    @Test
    public void invalidEditorPrivilegeChipsAreDroppedOnSave() {
        final ContentCommands admin = as(ADMIN, false);
        final ContentInstance holder = admin.createContent("cc.section-chips",
                StarterTemplates.CONTAINER_ID);
        holder.setTitle("Chips holder");
        // Chips are free-typed: names with no stored privilege row (and blanks/dupes) are IGNORED.
        holder.setEditorPrivileges(List.of("eventAdmin", "noSuchPrivilege", " ", "eventAdmin"));
        Assert.assertTrue(admin.saveContent(holder));
        Assert.assertEquals(admin.getContent(holder.getId()).getEditorPrivileges(),
                List.of("eventAdmin"), "unknown, blank, and duplicate names are dropped");

        final ContentInstance cleared = admin.getContent(holder.getId());
        cleared.setEditorPrivileges(List.of("noSuchPrivilege"));
        Assert.assertTrue(admin.saveContent(cleared));
        Assert.assertNull(admin.getContent(holder.getId()).getEditorPrivileges(),
                "only-invalid chips collapse to none, not to a junk list");
        Assert.assertTrue(admin.deleteContent(holder.getId()));
    }

    @Test
    public void instanceAllowListTightensTheContainer() {
        final ContentCommands admin = as(ADMIN, false);
        // The seeded docs container allows ONLY File children via its per-instance list, even though its
        // template (the generic container starter) declares no restriction.
        Assert.assertEquals(admin.getTemplateChoicesFor("docs").stream()
                        .map(ContentTemplate::getId).toList(),
                List.of(StarterTemplates.FILE_ID), "the instance allow-list filters the Add choices");
        final ContentInstance wrong = admin.createContent("docs", "cc-test-tpl");
        wrong.setTitle("not a file");
        wrong.getValues().put("msg", "x");
        Assert.assertFalse(admin.saveContent(wrong), "the instance allow-list gates the save path too");

        // Exactly one effective choice: the Add flow skips the picker and starts the instance directly.
        final ContentInstance started = admin.autoStartContent("docs");
        Assert.assertNotNull(started, "a single-choice section starts without the picker");
        Assert.assertEquals(started.getTemplateId(), StarterTemplates.FILE_ID);
        Assert.assertNull(admin.autoStartContent(FakeData.PAGE_KEY),
                "many choices keep the picker");
    }

    @Test
    public void editorChipHelpersAnswerPrivilegeNames() {
        final ContentCommands admin = as(ADMIN, false);
        Assert.assertTrue(admin.completeEditorPriv("event").contains("eventAdmin"));
        Assert.assertTrue(admin.completeEditorPriv("EVENT").contains("eventAdmin"), "match ignores case");
        Assert.assertTrue(admin.completeEditorPriv(null).contains("contentAdmin"), "empty query lists all");
        Assert.assertTrue(admin.completeEditorPriv("zzz-no-such").isEmpty());
        final String json = admin.getEditorPrivNamesJson();
        Assert.assertTrue(json.startsWith("[") && json.endsWith("]"), json);
        Assert.assertTrue(json.contains("\"eventAdmin\""), json);
    }

    @Test
    public void frameClassRotatesThePalette() {
        final ContentCommands content = as(ADMIN, false);
        Assert.assertEquals(content.frameClass(0), "tripEditFrame tripEditFrame0");
        Assert.assertEquals(content.frameClass(7), "tripEditFrame tripEditFrame2");
        Assert.assertEquals(content.frameClass(-1), "tripEditFrame tripEditFrame4",
                "defensive: never a negative suffix");
    }

    @Test
    public void containerRulesGateChildren() {
        final ContentCommands admin = as(ADMIN, false);
        // A dedicated restrictive container: only text-only children, at most one.
        final ContentTemplate strict = new ContentTemplate("cc-strict-container", 0, "Strict", null, "",
                List.of(), null, null, TemplateKind.CONTAINER,
                List.of(StarterTemplates.TEXT_ONLY_ID), 1, null);
        Assert.assertTrue(DAO.getInstance().saveTemplate(strict, 5));
        final ContentInstance holder = admin.createContent("cc.section-container", "cc-strict-container");
        holder.setTitle("Strict holder");
        Assert.assertTrue(admin.saveContent(holder));

        final ContentInstance wrongKind = admin.createContent(holder.getId(), "cc-test-tpl");
        wrongKind.setTitle("not allowed");
        wrongKind.getValues().put("msg", "x");
        Assert.assertFalse(admin.saveContent(wrongKind), "outside the allow-list");

        final ContentInstance first = admin.createContent(holder.getId(), StarterTemplates.TEXT_ONLY_ID);
        first.setTitle("first child");
        first.getValues().put("body", "<p>one</p>");
        Assert.assertTrue(admin.saveContent(first), "an allowed child under the limit saves");
        Assert.assertTrue(admin.saveContent(admin.getContent(first.getId())),
                "re-saving an existing child never counts against the limit");

        final ContentInstance second = admin.createContent(holder.getId(), StarterTemplates.TEXT_ONLY_ID);
        second.setTitle("second child");
        second.getValues().put("body", "<p>two</p>");
        Assert.assertFalse(admin.saveContent(second), "the container is full");

        final ContentInstance nested = admin.createContent(holder.getId(), StarterTemplates.CONTAINER_ID);
        nested.setTitle("nested container");
        Assert.assertFalse(admin.saveContent(nested), "containers cannot nest");

        final ContentInstance selfHosting = admin.getContent(holder.getId());
        selfHosting.setSection(holder.getId());
        Assert.assertFalse(admin.saveContent(selfHosting), "an item cannot live inside itself");

        Assert.assertTrue(admin.deleteContent(holder.getId()));
    }

    @Test
    public void deletingAContainerCascadesToItsChildren() {
        final ContentCommands admin = as(ADMIN, false);
        final ContentInstance holder = admin.createContent("cc.section-cascade",
                StarterTemplates.CONTAINER_ID);
        holder.setTitle("Doomed");
        Assert.assertTrue(admin.saveContent(holder));
        final ContentInstance child = admin.createContent(holder.getId(), StarterTemplates.TEXT_ONLY_ID);
        child.setTitle("child");
        child.getValues().put("body", "<p>going too</p>");
        Assert.assertTrue(admin.saveContent(child));

        Assert.assertTrue(admin.deleteContent(holder.getId()));
        Assert.assertNull(admin.getContent(child.getId()).getSection(),
                "the child row is gone (a blank answers unknown ids)");
        Assert.assertTrue(admin.getAllForSection(holder.getId()).isEmpty());
    }

    @Test
    public void applyOrderRewritesPositionsWithoutVersions() {
        final ContentCommands admin = as(ADMIN, false);
        final String section = "cc.section-order";
        final ContentInstance a = admin.createContent(section, "cc-test-tpl");
        a.setTitle("a");
        a.getValues().put("msg", "a");
        final ContentInstance b = admin.createContent(section, "cc-test-tpl");
        b.setTitle("b");
        b.getValues().put("msg", "b");
        Assert.assertTrue(admin.saveContent(a));
        Assert.assertTrue(admin.saveContent(b));

        Assert.assertTrue(admin.applyOrder(section, List.of(b.getId(), a.getId(), "no-such-id")));
        final List<String> ordered = admin.getAllForSection(section).stream()
                .map(ContentInstance::getTitle).toList();
        Assert.assertEquals(ordered, List.of("b", "a"));
        Assert.assertEquals(admin.getContent(a.getId()).getVersion(), 1,
                "reordering is version-silent: no history churn");
        Assert.assertTrue(admin.getHistory(a.getId()).size() <= 1, "no snapshot was pushed");

        Assert.assertFalse(as(NOBODY, false).applyOrder(section, List.of(a.getId())), "privilege-gated");
        Assert.assertFalse(admin.applyOrder(" ", List.of(a.getId())));
        Assert.assertFalse(admin.applyOrder(section, List.of()));
    }

    @Test
    public void brokenAuthoredHtmlIsRejected() {
        final ContentCommands admin = as(ADMIN, false);
        // cc-test-tpl's 'msg' is TEXT (escaped -- broken markup there is harmless and allowed); rich text
        // and markup titles are the injection points that must parse.
        final ContentTemplate richTpl = new ContentTemplate("cc-rich-tpl", 0, "Rich", null, "{{body}}",
                List.of(new Placeholder("body", Placeholder.Type.RICH_TEXT, "Body", null, true)),
                null, null);
        Assert.assertTrue(DAO.getInstance().saveTemplate(richTpl, 5));

        final ContentInstance broken = admin.createContent("cc.section-html", "cc-rich-tpl");
        broken.setTitle("ok title");
        broken.getValues().put("body", "<div><p>never closed</div>");
        Assert.assertFalse(admin.saveContent(broken), "misnested rich text must not reach the page");

        broken.getValues().put("body", "<div><p>fine</p></div>");
        Assert.assertTrue(admin.saveContent(broken), "well-formed rich text saves");

        final ContentInstance badTitle = admin.createContent("cc.section-html", "cc-rich-tpl");
        badTitle.setTitle("<b>never closed");
        badTitle.getValues().put("body", "<p>fine</p>");
        Assert.assertFalse(admin.saveContent(badTitle), "a markup title must parse too");
    }

    /**
     * The container writes the row around each child, so a layout change is a template edit rather than an
     * XHTML edit. The seeded default must reproduce what the page markup used to hardcode.
     */
    @Test
    public void aContainerWritesTheRowAroundEachChild() {
        final ContentCommands admin = as(ADMIN, false);
        // The seeded 'events' container carries no body of its own, so it renders the built-in row.
        final ContentInstance events = admin.getContent("events");
        final ContentInstance child = admin.createContent("events", StarterTemplates.TEXT_ONLY_ID);
        child.setTitle("Opening Mass");
        child.getValues().put("body", "<p>Details</p>");
        Assert.assertTrue(admin.saveContent(child));
        Assert.assertEquals(admin.childRowBefore(events, child, 0),
                "<div class=\"contentTitle\">Opening Mass</div>", "the default row is today's markup");
        Assert.assertEquals(admin.childRowAfter(events, child, 0), "");

        // Its own container template, so the layout choice belongs to the template and not to the page.
        final ContentTemplate listy = new ContentTemplate("cc-row-container", 0, "Listy", null,
                "<li id=\"{{child:id}}\"><b>{{child:index}}. {{child:title}}</b>{{child}}</li>",
                List.of(), null, null, TemplateKind.CONTAINER, null, null, null);
        Assert.assertTrue(DAO.getInstance().saveTemplate(listy, 5));
        final ContentInstance holder = admin.createContent("cc.section-row", "cc-row-container");
        holder.setTitle("Listy holder");
        Assert.assertTrue(admin.saveContent(holder));

        Assert.assertEquals(admin.childRowBefore(holder, child, 2),
                "<li id=\"" + child.getId() + "\"><b>3. Opening Mass</b>", "1-based index for the reader");
        Assert.assertEquals(admin.childRowAfter(holder, child, 2), "</li>");

        Assert.assertTrue(admin.deleteContent(holder.getId()));
        Assert.assertTrue(admin.deleteContent(child.getId()));
    }

    /** The wrapper a row cannot express -- emitted once around the list, not once per child. */
    @Test
    public void aContainerWrapsTheWholeChildListOnce() {
        final ContentCommands admin = as(ADMIN, false);
        final ContentTemplate listed = new ContentTemplate("cc-wrap-container", 0, "Wrapped", null,
                "<ul class=\"evList\">{{children:start}}<li>{{child:title}}{{child}}</li>"
                        + "{{children:end}}</ul>",
                List.of(), null, null, TemplateKind.CONTAINER, null, null, null);
        Assert.assertTrue(DAO.getInstance().saveTemplate(listed, 5));
        final ContentInstance holder = admin.createContent("cc.section-wrap", "cc-wrap-container");
        holder.setTitle("Wrapped holder");
        Assert.assertTrue(admin.saveContent(holder));
        final ContentInstance child = admin.createContent(holder.getId(), StarterTemplates.TEXT_ONLY_ID);
        child.setTitle("Rome");
        child.getValues().put("body", "<p>x</p>");
        Assert.assertTrue(admin.saveContent(child));

        Assert.assertEquals(admin.childrenBefore(holder), "<ul class=\"evList\">");
        Assert.assertEquals(admin.childrenAfter(holder), "</ul>");
        Assert.assertEquals(admin.childRowBefore(holder, child, 0), "<li>Rome", "the row alone repeats");
        Assert.assertEquals(admin.childRowAfter(holder, child, 0), "</li>");

        // The seeded container has no region, so it wraps nothing -- the page emits only the rows.
        final ContentInstance events = admin.getContent("events");
        Assert.assertEquals(admin.childrenBefore(events), "");
        Assert.assertEquals(admin.childrenAfter(events), "");
        Assert.assertEquals(admin.childrenBefore(null), "", "defensive: no wrapper without a container");

        Assert.assertTrue(admin.deleteContent(holder.getId()));
    }

    /** A programmatic child builds its heading from live data; the container must not repeat it. */
    @Test
    public void aProgrammaticChildGetsNoContainerWrittenTitle() {
        final ContentCommands admin = as(ADMIN, false);
        final ContentInstance events = admin.getContent("events");
        final ContentInstance albums = admin.getContent("fake-albums");
        Assert.assertEquals(admin.getKind(albums), "PROGRAMMATIC", "fixture guard");
        Assert.assertEquals(admin.childRowBefore(events, albums, 0), "<div class=\"contentTitle\"></div>",
                "the heading box stays empty (trip.css collapses it) rather than duplicating the title");
    }

    /**
     * A band places its own heading with {@code {{container:title}}}: the wrapper carries the title and the
     * page's separate heading is suppressed, so the title is written exactly once. The seeded Events
     * container has no such slot and keeps its heading above the rows.
     */
    @Test
    public void aBandContainerPlacesItsOwnTitleOnce() {
        final ContentCommands admin = as(ADMIN, false);
        final ContentInstance band = admin.createContent("cc.section-band", StarterTemplates.BAND_FEATURES_ID);
        band.setTitle("What & why");
        Assert.assertTrue(admin.saveContent(band));
        final ContentInstance card = admin.createContent(band.getId(), StarterTemplates.FEATURE_CARD_ID);
        card.setTitle("Fast");
        card.getValues().put("text", "<p>quick</p>");
        Assert.assertTrue(admin.saveContent(card));

        Assert.assertEquals(admin.renderTitle(band), "", "the band writes its own heading");
        Assert.assertTrue(admin.childrenBefore(band)
                        .contains("<h2 class=\"band-heading band-center\">What &amp; why</h2>"),
                "escaped into the band's own heading element: " + admin.childrenBefore(band));
        Assert.assertFalse(admin.childrenBefore(band).contains("{{container"), "no token may leak");
        Assert.assertTrue(admin.childRowBefore(band, card, 0).contains("<h3 class=\"feature-title\">Fast</h3>"));
        Assert.assertFalse(admin.childrenAfter(band).contains("{{"));

        final ContentInstance events = admin.getContent("events");
        Assert.assertEquals(admin.renderTitle(events), "<h3 class=\"contentTitle\">Events</h3>",
                "a container without the slot keeps the page-written heading");
        Assert.assertTrue(admin.deleteContent(band.getId()));
    }

    /** Defensive: an unresolvable container still lists its children with the built-in row. */
    @Test
    public void aMissingContainerTemplateStillRendersTheRow() {
        final ContentCommands admin = as(ADMIN, false);
        final ContentInstance orphan = admin.createContent("cc.section-orphan", "cc-no-such-template");
        orphan.setTitle("Orphan");
        Assert.assertEquals(admin.childRowBefore(orphan, orphan, 0),
                "<div class=\"contentTitle\">Orphan</div>");
        Assert.assertEquals(admin.childRowBefore(null, null, 0), "<div class=\"contentTitle\"></div>");
    }

    @Test
    public void includeHelpersDescribeInstances() {
        final ContentCommands admin = as(ADMIN, false);
        final ContentInstance events = admin.getContent("events");
        Assert.assertEquals(admin.getKind(events), "CONTAINER");
        Assert.assertEquals(admin.typeId(events), "");
        Assert.assertEquals(admin.renderTitle(events), "<h3 class=\"contentTitle\">Events</h3>");

        final ContentInstance albums = admin.getContent("fake-albums");
        Assert.assertEquals(admin.getKind(albums), "PROGRAMMATIC");
        Assert.assertEquals(admin.typeId(albums), "photo-albums");

        final ContentInstance intro = admin.getContent("fake-intro");
        Assert.assertEquals(admin.getKind(intro), "STANDARD");
        Assert.assertTrue(admin.isVisibleNow(intro));
        Assert.assertFalse(admin.isVisibleNow(admin.getContent("fake-event-past")));
        Assert.assertEquals(admin.getKind(null), "STANDARD");
        Assert.assertEquals(admin.typeId(null), "");
        Assert.assertEquals(admin.renderTitle(null), "");

        // getForView: the editor sees expired children, the public does not.
        Assert.assertTrue(admin.getForView("events", true).stream()
                .anyMatch(c -> c.getId().equals("fake-event-past")));
        Assert.assertTrue(admin.getForView("events", false).stream()
                .noneMatch(c -> c.getId().equals("fake-event-past")));

        final var children = admin.childrenFor(admin.getForView(FakeData.PAGE_KEY, false), false);
        Assert.assertTrue(children.containsKey("events"));
        Assert.assertTrue(children.containsKey("docs"));
        Assert.assertFalse(children.containsKey("fake-intro"), "only containers get child lists");
        Assert.assertTrue(admin.childrenFor(null, false).isEmpty());

        // Template choices: page level offers everything; a container excludes containers.
        Assert.assertTrue(admin.getTemplateChoicesFor(FakeData.PAGE_KEY).stream()
                .anyMatch(t -> t.getId().equals(StarterTemplates.CONTAINER_ID)));
        Assert.assertTrue(admin.getTemplateChoicesFor("events").stream()
                .noneMatch(t -> t.getId().equals(StarterTemplates.CONTAINER_ID)));

        // Choices: the pilgrimage language dropdown comes from the registry; STANDARD instances have none.
        Assert.assertFalse(admin.getChoices(admin.getContent("fake-pilgrimages-en"), "language").isEmpty());
        Assert.assertTrue(admin.getChoices(intro, "language").isEmpty());

        // The arrange dialog's inputs: a mutable id list in display order, and per-row title labels.
        final List<String> ids = admin.idsFor("events", true);
        Assert.assertTrue(ids.contains("fake-event-future"));
        Assert.assertEquals(admin.titleOf("events"), "Events");
        Assert.assertEquals(admin.titleOf("never-heard-of-it"), "never-heard-of-it",
                "an unknown id labels as itself rather than blank");
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
        final ContentTemplate tpl = DAO.getInstance().getTemplate("cc-test-tpl", Cached.NO).orElseThrow();
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

    @Test
    public void wysiwygWrappersAreCleanedOffRichTextOnSave() {
        final ContentCommands content = as(ADMIN, false);
        final ContentTemplate rich = new ContentTemplate("cc-caption-tpl", 0, "Rich", null,
                "<div>{{caption}}</div>",
                List.of(new Placeholder("caption", Placeholder.Type.RICH_TEXT, "Caption", null, false)),
                null, null);
        Assert.assertTrue(DAO.getInstance().saveTemplate(rich, 5));

        final ContentInstance item = content.createContent("cc.section-rich", "cc-caption-tpl");
        item.setTitle("Rich");
        item.getValues().put("caption", "<p class=\"ql-align-center\">Thurs, <strong>Aug 27</strong></p>"
                + "<p><br></p>");
        Assert.assertTrue(content.saveContent(item));

        Assert.assertEquals(DAO.getInstance().getContent(item.getId(), Cached.NO).orElseThrow()
                        .getValues().get("caption"),
                "<p style=\"text-align:center\">Thurs, <strong>Aug 27</strong></p>",
                "the editor's trailing paragraph goes, and alignment stops depending on Quill's stylesheet");
    }

    @Test
    public void aTemplateVersionChangeCarriesValuesAcross() {
        final ContentCommands content = as(ADMIN, false);
        final ContentTemplate v1 = new ContentTemplate("cc-versioned", 0, "Versioned", null, "<p>{{cap}}</p>",
                List.of(new Placeholder("cap", Placeholder.Type.TEXT, "Caption", null, false)), null, null);
        Assert.assertTrue(DAO.getInstance().saveTemplate(v1, 5));
        final ContentInstance item = content.createContent("cc.section-versions", "cc-versioned");
        item.setTitle("Versioned");
        item.getValues().put("cap", "kept");
        Assert.assertTrue(content.saveContent(item));
        Assert.assertEquals(item.getTemplateVersion(), 1);

        // v2 renames the placeholder but keeps its label, and adds a second hole.
        final ContentTemplate v2 = new ContentTemplate("cc-versioned", 1, "Versioned", null,
                "<div>{{caption}}{{extra}}</div>",
                List.of(new Placeholder("caption", Placeholder.Type.TEXT, "Caption", null, false),
                        new Placeholder("extra", Placeholder.Type.TEXT, "Extra", null, false)), null, null);
        Assert.assertTrue(DAO.getInstance().saveTemplate(v2, 5));

        Assert.assertEquals(content.getTemplateVersions(item), List.of(2, 1), "newest first");
        Assert.assertTrue(content.isTemplateOutdated(item));
        Assert.assertEquals(content.pinnedVersion(item), "1");
        Assert.assertEquals(content.versionLabel(item, 2), "v2 (latest)");
        Assert.assertEquals(content.versionLabel(item, 1), "v1 (in use)");

        Assert.assertTrue(content.retargetTemplateVersion(item, "2"));
        Assert.assertEquals(item.getTemplateVersion(), 2);
        Assert.assertEquals(item.getValues().get("caption"), "kept", "matched by its unchanged label");
        Assert.assertEquals(item.getValues().get("extra"), "", "the new hole is declared but empty");
        Assert.assertFalse(item.getValues().containsKey("cap"), "the old name is gone");
        Assert.assertFalse(content.isTemplateOutdated(item));

        Assert.assertTrue(content.saveContent(item), "the new pin survives the save");
        Assert.assertEquals(DAO.getInstance().getContent(item.getId(),
                Cached.NO).orElseThrow().getTemplateVersion(), 2);
    }

    @Test
    public void aVersionChangeIsIgnoredWhenThereIsNothingToDo() {
        final ContentCommands content = as(ADMIN, false);
        final ContentInstance item = content.createContent("cc.section-noop", "cc-test-tpl");
        Assert.assertFalse(content.retargetTemplateVersion(null, "2"));
        Assert.assertFalse(content.retargetTemplateVersion(item, null));
        Assert.assertFalse(content.retargetTemplateVersion(item, "  "));
        Assert.assertFalse(content.retargetTemplateVersion(item, "not-a-number"));
        Assert.assertFalse(content.retargetTemplateVersion(item, "1"), "already pinned there");
        Assert.assertFalse(content.retargetTemplateVersion(item, "99"), "no such retained version");
        Assert.assertEquals(item.getTemplateVersion(), 1, "a refused retarget changes nothing");

        Assert.assertTrue(content.getTemplateVersions(null).isEmpty());
        Assert.assertEquals(content.pinnedVersion(null), "");
        Assert.assertFalse(content.isTemplateOutdated(null));
        Assert.assertEquals(content.versionLabel(null, 3), "v3");
    }

    /**
     * The frozen-id round trip behind the landing page's session-scope conversion: the view keeps only
     * ids, and the resolver must honor the FROZEN order (row identity for decode), drop rows deleted
     * since the freeze, and never blow up on ids that no longer resolve.
     */
    @Test
    public void frozenIdsResolveCurrentCopiesInFrozenOrder() {
        final ContentCommands content = as(ADMIN, false);
        final String section = "cc.section-frozen";
        final ContentInstance first = content.createContent(section, "cc-test-tpl");
        first.setTitle("first");
        final ContentInstance second = content.createContent(section, "cc-test-tpl");
        second.setTitle("second");
        Assert.assertTrue(content.saveContent(first));
        Assert.assertTrue(content.saveContent(second));

        final java.util.List<String> frozen = content.idsOf(content.getForView(section, true));
        Assert.assertEquals(frozen.size(), 2, "both rows freeze");

        final java.util.List<String> reversed = new java.util.ArrayList<>(frozen);
        java.util.Collections.reverse(reversed);
        Assert.assertEquals(content.idsOf(content.forFrozenIds(section, true, reversed)), reversed,
                "the FROZEN order wins over the cache's natural order");

        reversed.add("no-such-row");
        Assert.assertEquals(content.forFrozenIds(section, true, reversed).size(), 2,
                "an id deleted since the freeze drops out rather than erroring");
        Assert.assertTrue(content.forFrozenIds(section, true, null).isEmpty());
        Assert.assertTrue(content.idsOf(null).isEmpty());
    }

    /** The child-id map round trip, against the seeded 'events' CONTAINER. */
    @Test
    public void frozenChildIdsRoundTripThroughTheResolvers() {
        final ContentCommands content = as(ADMIN, false);
        final java.util.List<ContentInstance> sections = content.getForView("page:trip-index", true);
        final java.util.HashMap<String, java.util.List<String>> childIds =
                content.childIdsFor(sections, true);
        Assert.assertTrue(childIds.containsKey("events"),
                "the seeded events CONTAINER should appear in the frozen child map");

        final java.util.HashMap<String, java.util.List<ContentInstance>> resolved =
                content.childrenForFrozen(childIds, true);
        Assert.assertEquals(content.idsOf(resolved.get("events")), childIds.get("events"),
                "children resolve in their frozen order");
        Assert.assertTrue(content.childrenForFrozen(null, true).isEmpty());
        Assert.assertTrue(content.childIdsFor(null, true).isEmpty());
    }
}
