package org.paulsens.trip.action;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
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
        past.getValues().put("msg", "past");
        past.setEventDate(LocalDateTime.now().minusMinutes(1));
        final ContentInstance future = content.createContent(section, "cc-test-tpl");
        future.setTitle("future");
        future.getValues().put("msg", "future");
        future.setEventDate(LocalDateTime.now().plusDays(1));
        final ContentInstance undated = content.createContent(section, "cc-test-tpl");
        undated.setTitle("undated");
        undated.getValues().put("msg", "undated");
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
    public void editModeNeedsBothTheRequestAndTheRightToEditThePage() {
        final ContentCommands content = as(ADMIN, false);
        // Asked for by someone who may edit the page (an admin, a container's editor): edit mode.
        Assert.assertTrue(content.editModeFor("true", FakeData.PAGE_KEY, ADMIN, false));
        Assert.assertTrue(content.editModeFor("true", FakeData.PAGE_KEY, EVENTS_ONLY, false));
        // Asked for by anyone else: NOT edit mode, so the page lists only what is visible now. This used to be
        // the bare request parameter, and ?edit=true showed any signed-in visitor the hidden sections.
        Assert.assertFalse(content.editModeFor("true", FakeData.PAGE_KEY, NOBODY, false));
        Assert.assertFalse(content.editModeFor("true", FakeData.PAGE_KEY, null, true), "never for a visitor");
        Assert.assertTrue(content.editModeFor("true", FakeData.PAGE_KEY, NOBODY, true),
                "the host's own extra admission (showAll, the landing page's media admin)");
        // Not asked for: never, whoever it is.
        for (final String requested : new String[] {null, "", "false", "TRUE", "1"}) {
            Assert.assertFalse(content.editModeFor(requested, FakeData.PAGE_KEY, ADMIN, true), "'" + requested + "'");
        }
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
        first.getValues().put("msg", "first");
        final ContentInstance second = content.createContent(section, "cc-test-tpl");
        second.setTitle("second");
        second.getValues().put("msg", "second");
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

    // ------------------------------------------------------------------ the content dialog's prompts and picker

    @Test
    public void richTextPromptsReadContentAndWidgetsAreNamedByType() {
        final ContentCommands content = new ContentCommands();
        Assert.assertEquals(content.promptLabel(new Placeholder("b", Placeholder.Type.RICH_TEXT, "Text", null, true)),
                "Content", "the old starter label");
        Assert.assertEquals(content.promptLabel(new Placeholder("b", Placeholder.Type.RICH_TEXT, "Body", null, true)),
                "Content");
        Assert.assertEquals(content.promptLabel(new Placeholder("q", Placeholder.Type.RICH_TEXT, "Quotation", null,
                true)), "Quotation", "a specific label stays");
        Assert.assertEquals(content.promptLabel(new Placeholder("t", Placeholder.Type.TEXT, "Text", null, false)),
                "Text", "a plain TEXT prompt labeled Text is not a rich-text body");
        Assert.assertEquals(content.promptLabel(null), "");
        Assert.assertEquals(content.widgetIdFor(new Placeholder("b", Placeholder.Type.RICH_TEXT, "x", null, false)),
                "phRich");
        Assert.assertEquals(content.widgetIdFor(new Placeholder("p", Placeholder.Type.PERSON, "x", null, false)),
                "phPerson");
        Assert.assertEquals(content.widgetIdFor(new Placeholder("m", Placeholder.Type.MULTI_CHOICE, "x", null,
                false)), "phMulti");
        Assert.assertEquals(content.widgetIdFor(new Placeholder("c", Placeholder.Type.CHOICE, "x", null, false)),
                "phChoice");
        Assert.assertEquals(content.widgetIdFor(new Placeholder("u", Placeholder.Type.URL, "x", null, false)),
                "phText");
        Assert.assertEquals(content.widgetIdFor(null), "phText");
    }

    @Test
    public void thePickerCardsRenderEveryStarterAsAFilledInSection() {
        final ContentCommands content = new ContentCommands();
        final List<ContentCommands.TemplateCard> cards = content.templateCards(FakeData.PAGE_KEY);
        Assert.assertFalse(cards.isEmpty());
        for (final ContentCommands.TemplateCard card : cards) {
            Assert.assertFalse(card.name().isBlank(), card.id());
            Assert.assertTrue(card.icon().startsWith("pi-"), card.id() + " " + card.icon());
            Assert.assertTrue(card.searchText().contains(card.name().toLowerCase()), card.id());
            Assert.assertEquals(card.searchText(), card.searchText().toLowerCase(), card.id());
            if (StarterTemplates.IDS.contains(card.id())) {
                Assert.assertFalse(card.html().isBlank(), card.id() + " renders a sample, whatever its kind");
                Assert.assertFalse(card.html().contains("{{"), card.id() + " leaves a token unfilled");
                Assert.assertFalse(card.tags().isEmpty(), card.id() + " is findable by a tag");
                Assert.assertTrue(card.searchText().contains(card.tags().get(0)), card.id());
            }
            Assert.assertEquals(card.kindLabel(), switch (card.kind()) {
                case "CONTAINER" -> "Group";
                case "PROGRAMMATIC" -> "Live";
                default -> "";
            }, card.id());
            Assert.assertEquals(card.holds().isEmpty(), !"CONTAINER".equals(card.kind()), card.id());
        }
        final ContentCommands.TemplateCard hero = card(cards, StarterTemplates.BAND_HERO_ID);
        Assert.assertTrue(hero.html().contains("A weekend that changes"), "the hero's headline sample");
        Assert.assertEquals(hero.name(), "Opening banner");
        Assert.assertEquals(hero.kindLabel(), "", "a plain section template carries no kind badge");
        Assert.assertTrue(hero.fullWidth());
        Assert.assertTrue(hero.searchText().contains("hero"), "the old jargon stays findable: " + hero.searchText());
        Assert.assertTrue(hero.fields().contains("Headline *"), "a required prompt is marked: " + hero.fields());
        Assert.assertTrue(hero.fields().contains("Eyebrow"), hero.fields().toString());
        Assert.assertTrue(hero.html().contains("src=\"" + StarterTemplates.SAMPLE_PICTURE + "\""),
                "a picture prompt with no sample shows the bundled picture: " + hero.html());
        final ContentCommands.TemplateCard albums = card(cards, StarterTemplates.PHOTO_ALBUMS_ID);
        Assert.assertEquals(albums.icon(), "pi-images");
        Assert.assertEquals(albums.kindLabel(), "Live");
        Assert.assertTrue(albums.html().contains("homeAlbum"), "a live type shows its static sample");
        Assert.assertFalse(card(cards, StarterTemplates.TEXT_ONLY_ID).fullWidth());
        final ContentCommands.TemplateCard stats = card(cards, StarterTemplates.BAND_STATS_ID);
        Assert.assertEquals(stats.holds(), "Stat item (up to 4)");
        Assert.assertEquals(card(cards, StarterTemplates.CONTAINER_ID).holds(), "Any item");
        Assert.assertEquals(card(cards, StarterTemplates.BAND_FAQ_ID).holds(), "Text Only");
        // A template with no samples renders its labels, so a hand-authored template still gets a picture.
        final ContentTemplate bare = new ContentTemplate("bare-" + UUID.randomUUID(), 1, "Bare", "d",
                "<div class=\"contentItem\"><b>{{heading}}</b>{{body}}<a href=\"{{more}}\">x</a></div>",
                List.of(new Placeholder("heading", Placeholder.Type.TEXT, "Heading", null, true),
                        new Placeholder("body", Placeholder.Type.RICH_TEXT, "Content", null, true),
                        new Placeholder("more", Placeholder.Type.URL, "Link", null, false)), null, null);
        Assert.assertTrue(content.sampleRender(bare).contains("<b>Heading</b>"));
        Assert.assertTrue(content.sampleRender(bare).contains("href=\"\""), "a link has no sample");
        Assert.assertEquals(content.sampleRender(null), "");
        final ContentTemplate mail = new ContentTemplate("mail-" + UUID.randomUUID(), 1, "Subject", "d", "<p>x</p>",
                List.of(), null, null, TemplateKind.MAIL, null, null, null);
        Assert.assertEquals(content.sampleRender(mail), "", "an email body is never a page section");
    }

    private static ContentCommands.TemplateCard card(final List<ContentCommands.TemplateCard> cards,
            final String id) {
        return cards.stream().filter(c -> id.equals(c.id())).findFirst().orElseThrow();
    }

    /** A stored picture value must be an absolute URL; only the SAMPLE render lets the bundled picture through. */
    @Test
    public void theSamplePictureNeverReachesARealRender() {
        final ContentCommands content = as(ADMIN, false);
        final ContentTemplate image = DAO.getInstance().getTemplate(StarterTemplates.IMAGE_ID, Cached.NO).orElseThrow();
        Assert.assertTrue(content.sampleRender(image).contains("src=\"" + StarterTemplates.SAMPLE_PICTURE + "\""));
        final ContentInstance real = content.createContent("cc.sample-picture", StarterTemplates.IMAGE_ID);
        real.getValues().put("imageUrl", StarterTemplates.SAMPLE_PICTURE);
        real.getValues().put("altText", "x");
        Assert.assertTrue(content.render(real).contains("src=\"\""), "a site-relative picture renders nothing");
    }

    @Test
    public void aGroupsSampleIsItsWrapperAndRowAroundSampleItems() {
        final ContentCommands content = new ContentCommands();
        final String features = content.sampleRender(starter(StarterTemplates.BAND_FEATURES_ID));
        Assert.assertEquals(count(features, "class=\"feature-cell\""), 3, features);
        Assert.assertTrue(features.contains("<h3 class=\"feature-title\">Group chat</h3>"), "the items' own titles");
        Assert.assertTrue(features.contains("band-center\">Feature cards</h2>"), "the group's title in its slot");
        Assert.assertTrue(features.contains("<div class=\"contentItem\"><div>"), "the page's wrappers around a child");
        Assert.assertTrue(features.contains("class=\"feature-card\""), "built on the admitted template");
        Assert.assertTrue(features.trim().endsWith("</section>"), "the once-around wrapper closes: " + features);
        Assert.assertFalse(features.contains("{{"), features);

        final String faq = content.sampleRender(starter(StarterTemplates.BAND_FAQ_ID));
        Assert.assertTrue(faq.contains("<summary class=\"faqQuestion\">When does registration open?</summary>"), faq);
        final String logos = content.sampleRender(starter(StarterTemplates.BAND_LOGOS_ID));
        Assert.assertEquals(count(logos, StarterTemplates.SAMPLE_PICTURE), 3, "three logo pictures: " + logos);

        // No title slot: the title goes above, as the page writes it; no allow-list: plain text items.
        final String plain = content.sampleRender(starter(StarterTemplates.CONTAINER_ID));
        Assert.assertTrue(plain.startsWith("<h3 class=\"contentTitle\">Container</h3>"), plain);
        Assert.assertTrue(plain.contains("<div class=\"contentTitle\">Welcome evening</div>"), plain);
        Assert.assertEquals(count(plain, "contentText"), 3, plain);

        // Only live items admitted: their static sample, and no title (a live item titles itself).
        final String people = content.sampleRender(starter(StarterTemplates.PEOPLE_LIST_ID));
        Assert.assertEquals(count(people, "class=\"personCard\""), 3, people);
        Assert.assertEquals(count(people, "class=\"peopleRow\""), 3, people);

        // Fewer items when the group holds fewer; an empty group when nothing it admits exists.
        final Map<String, ContentTemplate> known = new HashMap<>();
        known.put(StarterTemplates.TEXT_ONLY_ID, starter(StarterTemplates.TEXT_ONLY_ID));
        final ContentTemplate two = new ContentTemplate("cc-two", 1, "Two", null, "<li>{{child}}</li>", List.of(), null,
                null, TemplateKind.CONTAINER, null, 2, null);
        Assert.assertEquals(count(content.sampleRenderContainer(two, known), "<li>"), 2);
        final ContentTemplate orphan = new ContentTemplate("cc-orphan", 1, "Orphan", null, "<li>{{child}}</li>",
                List.of(), null, null, TemplateKind.CONTAINER, List.of("no-such-template"), null, null);
        Assert.assertEquals(content.sampleRenderContainer(orphan, known), "<h3 class=\"contentTitle\">Orphan</h3>");
        final ContentTemplate untyped = new ContentTemplate("cc-untyped", 1, "Untyped", null, "", List.of(), null, null,
                TemplateKind.PROGRAMMATIC, null, null, "no-such-type");
        Assert.assertEquals(content.sampleRender(untyped), "", "an unregistered type has no sample");
    }

    private static ContentTemplate starter(final String id) {
        return DAO.getInstance().getTemplate(id, Cached.NO).orElseThrow();
    }

    private static int count(final String haystack, final String needle) {
        return haystack.split(Pattern.quote(needle), -1).length - 1;
    }

    @Test
    public void aChildOnlyTemplateIsOfferedInsideItsGroupAndNeverAtPageLevel() {
        final ContentCommands content = as(ADMIN, false);
        final List<String> atPage = content.getTemplateChoicesFor("cc.page-childonly").stream()
                .map(ContentTemplate::getId).toList();
        Assert.assertFalse(atPage.contains(StarterTemplates.FEATURE_CARD_ID), "a feature card needs its grid");
        Assert.assertFalse(atPage.contains(StarterTemplates.STAT_ITEM_ID));
        Assert.assertTrue(atPage.contains(StarterTemplates.BAND_FEATURES_ID), "its group is offered");
        Assert.assertTrue(atPage.contains(StarterTemplates.BAND_HERO_ID),
                "a full-width template is flagged on a classic page, never hidden");
        Assert.assertTrue(atPage.contains(StarterTemplates.TEXT_ONLY_ID));

        final ContentInstance band = content.createContent("cc.page-childonly", StarterTemplates.BAND_FEATURES_ID);
        band.setTitle("Why us");
        Assert.assertTrue(content.saveContent(band));
        Assert.assertEquals(content.getTemplateChoicesFor(band.getId()).stream().map(ContentTemplate::getId).toList(),
                List.of(StarterTemplates.FEATURE_CARD_ID), "inside its group nothing changed");
        Assert.assertNotNull(content.autoStartContent(band.getId()), "and the single choice still skips the picker");

        // A group with no allow-list takes any item that is not itself a group, child-only ones included.
        final ContentInstance open = content.createContent("cc.page-childonly", StarterTemplates.CONTAINER_ID);
        open.setAllowedChildTemplateIds(null);
        Assert.assertTrue(content.saveContent(open));
        Assert.assertTrue(content.getTemplateChoicesFor(open.getId()).stream()
                .anyMatch(t -> StarterTemplates.STAT_ITEM_ID.equals(t.getId())));
    }

    @Test
    public void onAClassicPageTheFullWidthTemplatesTrailTheOffer() {
        final ContentCommands content = new ContentCommands();
        final List<ContentCommands.TemplateCard> asOffered = content.templateCards(FakeData.PAGE_KEY);
        Assert.assertEquals(content.templateCards(FakeData.PAGE_KEY, true), asOffered,
                "a full-width page lists the offer as it is");
        final List<ContentCommands.TemplateCard> classic = content.templateCards(FakeData.PAGE_KEY, false);
        Assert.assertEquals(classic.size(), asOffered.size(), "nothing is hidden");
        final int firstBand = firstFullWidth(classic);
        Assert.assertTrue(firstBand > 0, "the page's own templates come first");
        Assert.assertTrue(classic.subList(firstBand, classic.size()).stream()
                .allMatch(ContentCommands.TemplateCard::fullWidth), "then one trailing group of bands");
        Assert.assertEquals(classic.subList(0, firstBand),
                asOffered.stream().filter(c -> !c.fullWidth()).toList(), "each group keeps its own order");
    }

    private static int firstFullWidth(final List<ContentCommands.TemplateCard> cards) {
        for (int i = 0; i < cards.size(); i++) {
            if (cards.get(i).fullWidth()) {
                return i;
            }
        }
        return -1;
    }

    @Test
    public void aNewSectionIsSavedInFrontOfTheOneChosen() {
        final ContentCommands content = as(ADMIN, false);
        final String page = "cc.page-insert-" + UUID.randomUUID();
        final List<String> ids = new ArrayList<>();
        for (final String title : List.of("A", "B", "C")) {
            ids.add(added(content, page, title, null).getId());
        }
        Assert.assertEquals(content.idsFor(page, true), ids);

        final ContentInstance fresh = content.createContent(page, "cc-test-tpl");
        fresh.setTitle("New");
        fresh.getValues().put("msg", "new");
        Assert.assertEquals(content.landingAnchor(fresh, page), fresh.getId(), "a new section lands on itself");
        Assert.assertTrue(content.saveNewContentAt(fresh, ids.get(1)));
        Assert.assertEquals(content.idsFor(page, true),
                List.of(ids.get(0), fresh.getId(), ids.get(1), ids.get(2)), "in front of B");
        Assert.assertEquals(content.getAllForSection(page).stream().map(ContentInstance::getPosition).toList(),
                List.of(0, 1, 2, 3), "and the rest renumbered");
        Assert.assertEquals(content.landingAnchor(fresh, page), "", "once stored it is an edit: reload in place");

        // An edit carrying a stale place keeps its position.
        fresh.setTitle("Renamed");
        Assert.assertTrue(content.saveNewContentAt(fresh, ids.get(2)));
        Assert.assertEquals(content.idsFor(page, true).indexOf(fresh.getId()), 1);

        // No place, or one that is not in the section, saves at the end like any Add.
        final ContentInstance last = added(content, page, "Last", " ");
        final ContentInstance stray = added(content, page, "Stray", "no-such-section");
        final List<String> order = content.idsFor(page, true);
        Assert.assertEquals(order.subList(order.size() - 2, order.size()), List.of(last.getId(), stray.getId()));
        Assert.assertFalse(content.saveNewContentAt(null, ids.get(0)));

        // First place: in front of the first section.
        final ContentInstance first = added(content, page, "First", order.get(0));
        Assert.assertEquals(content.idsFor(page, true).get(0), first.getId());

        // A refused save moves nothing, and the item keeps the position it was created with.
        final List<String> before = content.idsFor(page, true);
        final ContentCommands stranger = as(NOBODY, false);
        final ContentInstance refused = stranger.createContent(page, "cc-test-tpl");
        refused.setTitle("Refused");
        refused.getValues().put("msg", "x");
        final int atEnd = refused.getPosition();
        Assert.assertFalse(stranger.saveNewContentAt(refused, before.get(0)));
        Assert.assertEquals(refused.getPosition(), atEnd);
        Assert.assertEquals(content.idsFor(page, true), before);
    }

    @Test
    public void theFillInPreviewShowsAnExampleUntilAPromptIsTouched() {
        final ContentCommands content = as(ADMIN, false);
        final String page = "cc.page-live-" + UUID.randomUUID();
        final ContentInstance fresh = content.createContent(page, "cc-test-tpl");
        Assert.assertTrue(content.isNewContent(fresh));
        Assert.assertFalse(content.isNewContent(null));
        Assert.assertTrue(content.liveHtml(fresh, null, null).contains(">Msg<"), "untouched: the prompt's example");
        Assert.assertFalse(content.liveHtml(fresh, "other, msg", "").contains("Msg"), "touched and blank: blank");
        fresh.getValues().put("msg", "typed");
        Assert.assertTrue(content.liveHtml(fresh, "", null).contains(">typed<"), "a value that is there shows");
        Assert.assertEquals(fresh.getValues().get("msg"), "typed", "and the working copy is never changed");
        Assert.assertEquals(content.liveHtml(fresh, "", "1"), "", "examples hidden: the page's own render instead");
        Assert.assertEquals(content.liveHtml(null, "", null), "");
        Assert.assertSame(content.liveInstance(fresh, "", null), fresh, "a plain section is its own preview");
        Assert.assertNull(content.liveInstance(null, "", null));

        // A picture prompt shows the bundled sample until it is typed in; then the page's own rule decides.
        final ContentInstance image = content.createContent(page, StarterTemplates.IMAGE_ID);
        Assert.assertTrue(content.liveHtml(image, "", null)
                .contains("src=\"" + StarterTemplates.SAMPLE_PICTURE + "\""));
        image.getValues().put("imageUrl", StarterTemplates.SAMPLE_PICTURE);
        Assert.assertTrue(content.liveHtml(image, "imageUrl", null).contains("src=\"\""),
                "a typed site-relative picture renders nothing, as on the page");

        // An item that already exists never shows an example.
        fresh.setTitle("Stored");
        Assert.assertTrue(content.saveContent(fresh));
        final ContentInstance stored = content.getContent(fresh.getId());
        Assert.assertFalse(content.isNewContent(stored));
        stored.getValues().put("msg", "");
        Assert.assertEquals(content.liveHtml(stored, "", null), "");
        Assert.assertSame(content.liveInstance(stored, "", null), stored);

        // A group has no examples of its own: the drawer retitles its sample in the browser.
        final ContentInstance group = content.createContent(page, StarterTemplates.CONTAINER_ID);
        Assert.assertEquals(content.liveHtml(group, "", null), "");
        Assert.assertSame(content.liveInstance(group, "", null), group);
    }

    @Test
    public void aNewLiveSectionBorrowsTheSitesRealValuesForChoicesNotMadeYet() {
        final ContentCommands content = as(ADMIN, false);
        final ContentInstance person = content.createContent(FakeData.PAGE_KEY, StarterTemplates.PERSON_ID);
        Assert.assertEquals(content.liveHtml(person, "", null), "", "a live section renders through its fragment");

        final ContentInstance live = content.liveInstance(person, "", null);
        Assert.assertNotSame(live, person, "a transient copy");
        Assert.assertEquals(live.getValues().get("personId"), ADMIN.getValue(), "the signed-in person's own card");
        Assert.assertFalse(live.getTitle().isBlank(), "and a title to show");
        Assert.assertTrue(person.getValues().get("personId").isBlank(), "the working copy keeps what was typed");

        Assert.assertTrue(content.liveInstance(person, "personId", null).getValues().get("personId").isBlank(),
                "a choice the author touched, even cleared, is theirs");
        Assert.assertSame(content.liveInstance(person, "", "1"), person, "examples hidden");
        person.setTitle("Our director");
        Assert.assertEquals(content.liveInstance(person, "", null).getTitle(), "Our director");

        // Nobody signed in has no card of their own, so there is nothing real to borrow.
        final ContentCommands anonymous = as(null, true);
        final ContentInstance theirs = anonymous.createContent(FakeData.PAGE_KEY, StarterTemplates.PERSON_ID);
        Assert.assertSame(anonymous.liveInstance(theirs, "", null), theirs);
    }

    @Test
    public void theTitleOfAPlainSectionOnAPageShowsNowhere() {
        final ContentCommands content = as(ADMIN, false);
        final String page = "cc.page-title-" + UUID.randomUUID();
        Assert.assertTrue(content.isTitleUnseen(content.createContent(page, "cc-test-tpl"), page));
        Assert.assertFalse(content.isTitleUnseen(content.createContent(page, StarterTemplates.CONTAINER_ID), page),
                "a group shows its title");
        Assert.assertFalse(content.isTitleUnseen(content.createContent(page, StarterTemplates.PERSON_ID), page),
                "a live type titles itself");
        Assert.assertFalse(content.isTitleUnseen(content.createContent("some-group", "cc-test-tpl"), page),
                "a group's row shows its item's title");
        Assert.assertFalse(content.isTitleUnseen(null, page));

        // The drawer's heading says what is happening: adding or editing, a section or a group's item.
        final ContentInstance stored = added(content, page, "Kept", null);
        Assert.assertEquals(content.drawerTitle(null, page, page), "Add a section", "the picker step");
        Assert.assertEquals(content.drawerTitle(content.createContent(page, "cc-test-tpl"), page, page),
                "Add a section");
        Assert.assertEquals(content.drawerTitle(content.getContent(stored.getId()), page, page), "Edit section");
        Assert.assertEquals(content.drawerTitle(null, "some-group", page), "Add an item");
        Assert.assertEquals(content.drawerTitle(content.getContent(stored.getId()), "some-group", page), "Edit item");
        Assert.assertEquals(content.drawerTitle(null, null, page), "Add a section");
        Assert.assertFalse(content.isTitleUnseen(content.createContent(page, "cc-test-tpl"), null));
    }

    @Test
    public void theInsertPointIsNamedAndMovesOneSectionAtATime() {
        final ContentCommands content = as(ADMIN, false);
        final String page = "cc.page-where-" + UUID.randomUUID();
        final String first = added(content, page, "First <b>one</b>", null).getId();
        final String second = added(content, page, "Second", null).getId();

        Assert.assertEquals(content.insertLabel(page, page, second), "Adding above \u201cSecond\u201d");
        Assert.assertEquals(content.insertLabel(page, page, first), "Adding above \u201cFirst one\u201d",
                "a title's markup is not part of its name");
        Assert.assertEquals(content.insertLabel(page, page, ""), "Adding at the end of the page");
        Assert.assertEquals(content.insertLabel(page, page, null), "Adding at the end of the page");
        Assert.assertEquals(content.insertLabel(page, page, "no-such-section"), "Adding at the end of the page");
        Assert.assertEquals(content.insertLabel(second, page, null), "Adding to \u201cSecond\u201d", "inside a group");
        Assert.assertEquals(content.insertLabel("no-such-group", page, null), "Adding to this group");
        Assert.assertEquals(content.insertLabel(null, page, null), "");
        Assert.assertEquals(content.insertLabel(" ", page, null), "");

        final ContentInstance untitled = content.createContent(page, StarterTemplates.CONTAINER_ID);
        Assert.assertTrue(content.saveContent(untitled));
        Assert.assertEquals(content.insertLabel(page, page, untitled.getId()), "Adding above an untitled section");

        Assert.assertEquals(content.moveInsertPoint(page, "", true), untitled.getId(), "up from the end");
        Assert.assertEquals(content.moveInsertPoint(page, untitled.getId(), true), second);
        Assert.assertEquals(content.moveInsertPoint(page, second, true), first);
        Assert.assertEquals(content.moveInsertPoint(page, first, true), first, "the top is the top");
        Assert.assertEquals(content.moveInsertPoint(page, first, false), second);
        Assert.assertEquals(content.moveInsertPoint(page, untitled.getId(), false), "", "down from the last: the end");
        Assert.assertEquals(content.moveInsertPoint(page, null, false), "", "the end is the end");
        Assert.assertEquals(content.moveInsertPoint(page, "no-such-section", true), untitled.getId());
    }

    private static ContentInstance added(final ContentCommands content, final String page, final String title,
            final String beforeId) {
        final ContentInstance instance = content.createContent(page, "cc-test-tpl");
        instance.setTitle(title);
        instance.getValues().put("msg", title);
        Assert.assertTrue(content.saveNewContentAt(instance, beforeId), title);
        return instance;
    }

    @Test
    public void anItemAddedInsideAGroupLandsOnTheGroup() {
        final ContentCommands content = as(ADMIN, false);
        final ContentInstance child = content.createContent("events", StarterTemplates.TEXT_ONLY_ID);
        Assert.assertEquals(content.landingAnchor(child, FakeData.PAGE_KEY), "events");
        Assert.assertEquals(content.landingAnchor(null, FakeData.PAGE_KEY), "");
        Assert.assertEquals(content.landingAnchor(child, null), "");
        child.setSection(null);
        Assert.assertEquals(content.landingAnchor(child, FakeData.PAGE_KEY), "");
    }

    @Test
    public void aLivePreviewShowsTheSitesOwnDataOrNothing() {
        final ContentCommands content = as(ADMIN, false);
        final ContentInstance file = content.previewInstance(FakeData.PAGE_KEY, StarterTemplates.FILE_ID);
        Assert.assertNotNull(file, "the seeded library has a visible document");
        Assert.assertFalse(file.getValues().get("mediaId").isBlank());
        Assert.assertEquals(file.getSection(), FakeData.PAGE_KEY);
        Assert.assertEquals(file.getVersion(), 0, "transient: never saved");
        Assert.assertTrue(DAO.getInstance().getContent(file.getId(), Cached.NO).isEmpty());

        final ContentInstance person = content.previewInstance(FakeData.PAGE_KEY, StarterTemplates.PERSON_ID);
        Assert.assertEquals(person.getValues().get("personId"), ADMIN.getValue(), "the signed-in person's own card");
        Assert.assertNotNull(content.previewInstance(FakeData.PAGE_KEY, StarterTemplates.PILGRIMAGES_ID)
                .getValues().get("language"), "the seeded site lists public trips");

        Assert.assertNull(content.previewInstance(FakeData.PAGE_KEY, StarterTemplates.TEXT_ONLY_ID),
                "a section template's preview is its sample render");
        Assert.assertNull(content.previewInstance(FakeData.PAGE_KEY, "no-such-template"));
        Assert.assertNull(content.previewInstance(" ", StarterTemplates.FILE_ID));
        Assert.assertNull(content.previewInstance(FakeData.PAGE_KEY, null));
        Assert.assertNull(as(NOBODY, false).previewInstance(FakeData.PAGE_KEY, StarterTemplates.FILE_ID),
                "only someone who may edit the section previews into it");
        Assert.assertNull(as(null, true).previewInstance(FakeData.PAGE_KEY, StarterTemplates.PERSON_ID),
                "nobody is signed in, so there is no card of their own to show");

        // Another organization's template is not this site's to preview; an unregistered type has no values.
        final ContentTemplate foreign = new ContentTemplate("cc-foreign-file", 0, "Foreign file", null, "", List.of(),
                null, null, TemplateKind.PROGRAMMATIC, null, null, StarterTemplates.FILE_ID);
        foreign.setOrgId(FakeData.ACME_ORG_ID);
        Assert.assertTrue(DAO.getInstance().saveTemplate(foreign, 5));
        Assert.assertNull(content.previewInstance(FakeData.PAGE_KEY, foreign.getId()));
        final ContentTemplate retired = new ContentTemplate("cc-retired-type", 0, "Retired", null, "", List.of(),
                null, null, TemplateKind.PROGRAMMATIC, null, null, "no-such-type");
        Assert.assertTrue(DAO.getInstance().saveTemplate(retired, 5));
        Assert.assertNull(content.previewInstance(FakeData.PAGE_KEY, retired.getId()));
    }

    @Test
    public void aBlankRequiredPromptIsRefusedOnSave() {
        final ContentCommands content = as(Person.Id.newInstance(), true);
        final ContentInstance blank = content.createContent(FakeData.PAGE_KEY, StarterTemplates.TEXT_ONLY_ID);
        blank.setTitle("Blank body");
        blank.getValues().put("body", "<p><br></p>");
        Assert.assertFalse(content.saveContent(blank), "an empty paragraph is no content");
        Assert.assertFalse(content.saveContent(withBody(content, "")), "nothing typed");
        Assert.assertTrue(content.saveContent(withBody(content, "<p>Something</p>")), "a filled prompt saves");
    }

    private static ContentInstance withBody(final ContentCommands content, final String body) {
        final ContentInstance instance = content.createContent(FakeData.PAGE_KEY, StarterTemplates.TEXT_ONLY_ID);
        instance.setTitle("Body " + body.length());
        instance.getValues().put("body", body);
        return instance;
    }
}
