package org.paulsens.trip.action;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.content.OrgPageBootstrap;
import org.paulsens.trip.content.StarterTemplates;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripPageOptions;
import org.primefaces.extensions.event.CompleteEvent;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * A trip page's own stylesheets (docs/conference.md "Look and feel"): one library row per stylesheet, saved in
 * place with a rev bump, gated to the org's admins and media admins, with the local store standing in for the
 * bucket. The media bean is REAL here (local mode: no bucket, so the bytes land in the local store); a mocked one
 * proves the bucket-side calls.
 */
public class TripStylesheetsTest {
    private static final String ORG = FakeData.CFPW_ORG_ID;

    private boolean editor;
    private String tripInView;
    private final Map<String, Object> stashed = new HashMap<>();
    private TripStylesheets sheets;
    private DAO dao;

    @BeforeClass
    public void seed() throws IOException {
        FakeData.initFakeData();
        FakeData.addFakeData();
        dao = DAO.getInstance();
    }

    @BeforeMethod
    public void setUp() throws Exception {
        editor = true;
        tripInView = null;
        stashed.clear();
        sheets = withMedia(new MediaCommands());
    }

    private TripStylesheets withMedia(final MediaCommands media) throws Exception {
        final TripStylesheets bean = new TripStylesheets() {
            @Override
            protected boolean mayEdit(final String orgId) {
                return editor;
            }

            @Override
            protected String tripIdInView() {
                return tripInView;
            }

            @Override
            protected void stash(final String key, final Object value) {
                stashed.put(key, value);
            }

            @Override
            protected Object viewValue(final String key) {
                return stashed.get(key);
            }
        };
        final Field field = TripStylesheets.class.getDeclaredField("media");
        field.setAccessible(true);
        field.set(bean, media);
        return bean;
    }

    /**
     * The conference import put each event's page stylesheet under {@code org/{id}/conferences/...}, not this bean's
     * folder; a trip linking one opened the editor on "New stylesheet" and the menu refused to read it (2026-11-07).
     */
    @Test
    public void anImportedStylesheetInTheOrgsOwnFolderOpensReadsAndSavesInPlace() throws IOException {
        final String key = "org/" + ORG + "/conferences/2026-test/summer-" + UUID.randomUUID() + ".css";
        final MediaItem imported = new MediaItem("imported-" + UUID.randomUUID(), key, "Summer page stylesheet",
                "Imported", "text/css", 20, null, 0, LocalDateTime.now(), "import", null, null, ORG);
        assertTrue(dao.saveMedia(imported));
        final Trip trip = Trip.builder().title("Imported Trip").orgId(ORG).build();
        final TripPageOptions options = new TripPageOptions();
        options.setStylesheetUrl(sheets.urlOf(imported));
        trip.setPage(options);

        assertEquals(sheets.linkedItem(trip).getId(), imported.getId(), "the trip's link resolves to it");
        assertTrue(sheets.openEditor(trip), "the Styles button opens it, not a new sheet");
        assertEquals(stashed.get("cssItemId"), imported.getId());
        assertEquals(stashed.get("cssTitle"), "Summer page stylesheet");
        stashed.clear();
        assertTrue(sheets.pick(imported.getId()), "the menu opens it too");
        assertTrue(sheets.saveOnly(imported.getId(), "Summer page stylesheet", ".a{color:red}"), "saved in place");
        assertEquals(dao.getMedia(imported.getId(), Cached.NO).orElseThrow().getS3Key(), key, "same key");
        assertEquals(sheets.read(imported.getId()), ".a{color:red}");

        // Never another organization's folder, never a file that is not a .css, never a path escape.
        assertFalse(TripStylesheets.isOrgStylesheet(new MediaItem("x1", "org/" + FakeData.ACME_ORG_ID + "/a.css",
                "t", null, "text/css", 1, null, 0, null, null, null, null, ORG)), "another org's folder");
        assertFalse(TripStylesheets.isOrgStylesheet(new MediaItem("x2", "org/" + ORG + "/a.txt", "t", null,
                "text/css", 1, null, 0, null, null, null, null, ORG)), "not a .css file");
        assertFalse(TripStylesheets.isOrgStylesheet(new MediaItem("x3", "org/" + ORG + "/../x/a.css", "t", null,
                "text/css", 1, null, 0, null, null, null, null, ORG)), "a path escape");
        assertFalse(TripStylesheets.isOrgStylesheet(new MediaItem("x4", "org/" + ORG + "/a.css", "t", null,
                "image/png", 1, null, 0, null, null, null, null, ORG)), "not a stylesheet");
        assertFalse(TripStylesheets.isOrgStylesheet(new MediaItem("x5", "org/" + ORG + "/a.css", "t", null,
                "text/css", 1, null, 0, null, null, null, null, null)), "no organization");
        assertFalse(TripStylesheets.isOrgStylesheet(null));
    }

    @Test
    public void keysAndSlugsAreStrict() {
        assertEquals(TripStylesheets.slugOf("PW Styles 2027!"), "pw-styles-2027");
        assertEquals(TripStylesheets.slugOf("   "), "styles");
        assertEquals(TripStylesheets.slugOf(null), "styles");
        assertTrue(TripStylesheets.slugOf("x".repeat(80)).length() <= 40);
        assertTrue(TripStylesheets.isStylesheetKey("org/" + ORG + "/stylesheets/pw-styles.css"));
        assertFalse(TripStylesheets.isStylesheetKey("org/" + ORG + "/stylesheets/../x.css"));
        assertFalse(TripStylesheets.isStylesheetKey("org/" + ORG + "/branding/logo-1.png"));
        assertFalse(TripStylesheets.isStylesheetKey("org/" + ORG + "/stylesheets/sub/x.css"));
        assertFalse(TripStylesheets.isStylesheetKey("org/" + ORG + "/stylesheets/x.js"));
        assertFalse(TripStylesheets.isStylesheetKey(null));
    }

    @Test
    public void onlyCssTextWithinTheCapIsAccepted() {
        assertNull(TripStylesheets.check(".a { color: red }\n\t/* ok */"));
        assertNotNull(TripStylesheets.check(null));
        assertNotNull(TripStylesheets.check("<style>.a{}</style>"), "a closing style tag is refused");
        assertNotNull(TripStylesheets.check(".a{}</STYLE ><script>"), "in any case");
        assertNull(TripStylesheets.check(".a{mask-image:url('data:image/svg+xml;utf8,<svg/>')} /* <style> */"),
                "a lone '<' is legal CSS: an inline SVG, a comment (the imported conference sheets hold both)");
        assertNull(TripStylesheets.check("x".repeat(120 * 1024)), "a 120 KB imported sheet fits");
        assertNotNull(TripStylesheets.check(".a{}\u0007"), "a control character is refused");
        assertNotNull(TripStylesheets.check("x".repeat(TripStylesheets.MAX_BYTES + 1)));
    }

    @Test
    public void createListsSavesInPlaceAndRenamesWithoutMovingTheKey() {
        final String title = "PW Styles " + UUID.randomUUID();
        final MediaItem created = sheets.create(ORG, title, ".contentItem .pw-note { color: red }");
        assertNotNull(created);
        assertEquals(created.getContentType(), "text/css");
        assertEquals(created.getOrgId(), ORG);
        assertTrue(created.getS3Key().startsWith("org/" + ORG + "/stylesheets/pw-styles-"));
        assertTrue(new MediaCommands().libraryItems(ORG, "text/css").stream()
                .anyMatch(item -> item.getId().equals(created.getId())), "the dialog's menu lists it");
        assertEquals(sheets.read(created.getId()), ".contentItem .pw-note { color: red }");
        assertEquals(sheets.urlOf(created), "/trip-stylesheets/" + created.getS3Key(),
                "locally the webapp serves it");
        assertEquals(sheets.localGet(created.getS3Key()).map(b -> new String(b, StandardCharsets.UTF_8))
                .orElse(null), ".contentItem .pw-note { color: red }");

        assertTrue(sheets.save(created.getId(), ".contentItem .pw-note { color: blue }"));
        final MediaItem saved = dao.getMedia(created.getId(), Cached.NO).orElseThrow();
        assertEquals(saved.getS3Key(), created.getS3Key(), "the same key, so every stored URL still works");
        assertEquals(saved.getRev(), 1, "a save bumps the rev for the cache buster");
        assertEquals(saved.getCacheBuster(), "?r=1");
        assertEquals(sheets.read(created.getId()), ".contentItem .pw-note { color: blue }");
        assertTrue(sheets.save(created.getId(), ".contentItem .pw-note { color: green }"));
        assertEquals(dao.getMedia(created.getId(), Cached.NO).orElseThrow().getRev(), 2);

        assertTrue(sheets.rename(created.getId(), created.getTitle()), "the same title is a no-op");
        assertTrue(sheets.rename(created.getId(), "Renamed styles"));
        final MediaItem renamed = dao.getMedia(created.getId(), Cached.NO).orElseThrow();
        assertEquals(renamed.getTitle(), "Renamed styles");
        assertEquals(renamed.getS3Key(), created.getS3Key());
        assertEquals(renamed.getRev(), 2, "a rename is not a rewrite");
        assertFalse(sheets.rename(created.getId(), " "));

        // A second stylesheet with the same title takes the next free key.
        final MediaItem second = sheets.create(ORG, title, ".a{}");
        assertNotNull(second);
        assertFalse(second.getS3Key().equals(created.getS3Key()));
        assertTrue(second.getS3Key().endsWith("-2.css"), second.getS3Key());

        // itemFor resolves the menu value back to the row, with or without a cache buster.
        assertEquals(sheets.itemFor(ORG, sheets.urlOf(created)).getId(), created.getId());
        assertEquals(sheets.itemFor(ORG, sheets.urlOf(created) + "?r=2").getId(), created.getId());
        assertNull(sheets.itemFor(FakeData.ACME_ORG_ID, sheets.urlOf(created)), "never another org's");
    }

    @Test
    public void theLiveEditorOpensSavesAndKeepsEditingWhatItCreated() throws IOException {
        final Trip trip = Trip.builder().title("Live Trip").orgId(ORG).openToPublic(true)
                .startDate(LocalDateTime.now().plusDays(30)).endDate(LocalDateTime.now().plusDays(32)).build();
        assertTrue(dao.saveTrip(trip));
        tripInView = trip.getId();

        // Opening on a trip that links nothing loads nothing; "new" loads the starter.
        assertFalse(sheets.prepare(trip, "true"));
        assertTrue(stashed.isEmpty());
        assertTrue(sheets.prepare(trip, "new"));
        assertNull(stashed.get("cssItemId"));
        assertEquals(stashed.get("cssText"), sheets.starterCss());
        assertFalse(sheets.prepare(null, "new"));

        // Save creates (stores only: the trip is untouched) and the editor goes on editing the new item.
        assertFalse(sheets.saveOnly(null, " ", ".a{}"), "a blank title is refused first");
        assertTrue(sheets.saveOnly(null, "Live " + UUID.randomUUID(), ".a{}"));
        final String createdId = (String) stashed.get("cssItemId");
        assertNotNull(createdId, "the editor now edits the stored item");
        final Trip stored = dao.getTrip(trip.getId(), Cached.NO).orElseThrow();
        assertTrue(stored.pageOrNull() == null || stored.pageOrNull().getStylesheetUrl() == null,
                "the trip is not linked");
        assertTrue(sheets.choices(ORG).stream().anyMatch(item -> item.getId().equals(createdId)), "in the menu");

        // A second save rewrites in place and applies the title.
        assertTrue(sheets.saveOnly(createdId, "Live renamed", ".b{}"));
        final MediaItem saved = dao.getMedia(createdId, Cached.NO).orElseThrow();
        assertEquals(saved.getTitle(), "Live renamed");
        assertEquals(saved.getRev(), 1);
        assertEquals(sheets.read(createdId), ".b{}");
        assertFalse(sheets.saveOnly(createdId, "Live renamed", "</style><nope>"), "a refused text");

        // Linked by the trip editor, the next open loads it; the menu pick loads any of the org's sheets.
        trip.getPage().setStylesheetUrl(sheets.urlOf(saved));
        assertTrue(dao.saveTrip(trip));
        stashed.clear();
        assertTrue(sheets.prepare(trip, "true"));
        assertEquals(stashed.get("cssItemId"), createdId);
        assertEquals(stashed.get("cssText"), ".b{}");
        assertEquals(sheets.linkedItem(trip).getId(), createdId);
        stashed.clear();
        assertTrue(sheets.openItem("fake-css-cfpw"), "the seeded sample reads as the starter");
        assertEquals(stashed.get("cssText"), sheets.starterCss());
        assertFalse(sheets.openItem("no-such-item"));
        assertFalse(sheets.openItem(null));

        // The toolbar's Styles button: the first open loads the linked sheet; a later open keeps the editor's text.
        stashed.clear();
        assertTrue(sheets.openEditor(trip));
        assertEquals(stashed.get("cssItemId"), createdId, "the sheet the trip links, not a new one");
        assertEquals(stashed.get("cssText"), ".b{}");
        stashed.put("cssText", ".edited{}");
        assertTrue(sheets.openEditor(trip));
        assertEquals(stashed.get("cssText"), ".edited{}", "a reopen keeps the work in progress");
        // The menu: the blank "New stylesheet" entry loads the starter, an id loads that sheet.
        assertTrue(sheets.pick(""));
        assertNull(stashed.get("cssItemId"));
        assertEquals(stashed.get("cssText"), sheets.starterCss());
        assertTrue(sheets.pick(" " + createdId + " "));
        assertEquals(stashed.get("cssText"), ".b{}");
        assertTrue(sheets.pick(null));
        assertFalse(sheets.pick("no-such-item"));
        // A trip linking nothing opens on the starter.
        stashed.clear();
        final Trip bare = Trip.builder().title("Bare").orgId(ORG).build();
        assertTrue(sheets.openEditor(bare));
        assertEquals(stashed.get("cssText"), sheets.starterCss());

        // Off the trip editor's page (no trip in view) a new sheet has no organization to belong to.
        tripInView = null;
        assertFalse(sheets.saveOnly(null, "Nowhere", ".a{}"));

        // The seeded sample has a row and no stored bytes: it reads as the starter, locally and for the servlet.
        final MediaItem sample = dao.getMedia("fake-css-cfpw", Cached.NO).orElseThrow();
        assertEquals(sheets.read(sample.getId()), sheets.starterCss());
        assertTrue(sheets.localGet(sample.getS3Key()).isPresent());
        assertTrue(sheets.localGet("org/" + ORG + "/stylesheets/never-stored.css").isEmpty());
        assertTrue(sheets.localGet("not/a/key").isEmpty());
    }

    @Test
    public void theGateIsTheOrgsAdminsAndMediaAdmins() throws Exception {
        final OrgCommands orgs = Mockito.mock(OrgCommands.class);
        Mockito.when(orgs.canManageOrg("admin-org")).thenReturn(true);
        Mockito.when(orgs.canViewOrgMedia("media-org")).thenReturn(true);
        final TripStylesheets real = new TripStylesheets();
        final Field field = TripStylesheets.class.getDeclaredField("orgs");
        field.setAccessible(true);
        field.set(real, orgs);
        assertTrue(real.canEdit("admin-org"), "an org admin");
        assertTrue(real.canEdit("media-org"), "a media admin");
        assertFalse(real.canEdit("other-org"), "anyone else");
        assertFalse(real.canEdit(" "));
    }

    @Test
    public void theEditorsUrlsFollowTheLibrary() {
        final MediaItem sample = dao.getMedia("fake-css-cfpw", Cached.NO).orElseThrow();
        assertEquals(sheets.urlOfId(sample.getId()), sheets.urlOf(sample));
        assertEquals(sheets.urlOfId("no-such-item"), "");
        assertEquals(sheets.urlOfId(" "), "");
        assertEquals(sheets.urlOfId(null), "");
        assertNull(sheets.urlOf(null));
        assertNull(sheets.linkedItem(null));
        assertTrue(sheets.choices(ORG).stream().anyMatch(item -> item.getId().equals(sample.getId())));
    }

    @Test
    public void aStoreThatRefusesTheBytesChangesNothing() throws Exception {
        final MediaCommands media = Mockito.mock(MediaCommands.class);
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        Mockito.when(media.putObject(ArgumentMatchers.anyString(), ArgumentMatchers.any(byte[].class),
                ArgumentMatchers.anyString(), ArgumentMatchers.anyLong(), ArgumentMatchers.anyBoolean()))
                .thenReturn(false);
        Mockito.when(media.getByKey(ArgumentMatchers.anyString())).thenReturn(null);
        final TripStylesheets broken = withMedia(media);
        assertNull(broken.create(ORG, "Broken " + UUID.randomUUID(), ".a{}"), "no row without the bytes");
        final MediaItem existing = sheets.create(ORG, "Existing " + UUID.randomUUID(), ".a{}");
        assertNotNull(existing);
        assertFalse(broken.save(existing.getId(), ".b{}"), "a failed rewrite leaves the row alone");
        assertEquals(dao.getMedia(existing.getId(), Cached.NO).orElseThrow().getRev(), 0);
        Mockito.verify(media, Mockito.never()).invalidateCdn(ArgumentMatchers.anyList());
    }

    @Test
    public void refusalsChangeNothing() {
        final MediaItem created = sheets.create(ORG, "Refusals " + UUID.randomUUID(), ".a{}");
        assertNotNull(created);
        assertFalse(sheets.save(created.getId(), ".a{}</style><b>no</b>"));
        assertFalse(sheets.save(created.getId(), "x".repeat(TripStylesheets.MAX_BYTES + 1)));
        assertEquals(sheets.read(created.getId()), ".a{}", "a refused save leaves the text alone");
        assertEquals(dao.getMedia(created.getId(), Cached.NO).orElseThrow().getRev(), 0);
        assertNull(sheets.create(ORG, "Bad", "</style>"));

        editor = false;
        assertFalse(sheets.canEdit(ORG));
        assertNull(sheets.create(ORG, "Nope", ".a{}"), "a trip manager who is no admin may not create");
        assertNull(sheets.read(created.getId()));
        assertFalse(sheets.save(created.getId(), ".b{}"));
        assertFalse(sheets.rename(created.getId(), "Nope"));
        assertFalse(sheets.canEdit(null));
        assertNull(sheets.read("no-such-item"));
        assertFalse(sheets.save(null, ".a{}"));
    }

    @Test
    public void aBucketTakesThePutAndOneInvalidationPerSave() throws Exception {
        final MediaCommands media = Mockito.mock(MediaCommands.class);
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        Mockito.when(media.putObject(ArgumentMatchers.anyString(), ArgumentMatchers.any(byte[].class),
                ArgumentMatchers.eq("text/css"), ArgumentMatchers.anyLong(), ArgumentMatchers.eq(false)))
                .thenReturn(true);
        Mockito.when(media.getByKey(ArgumentMatchers.anyString())).thenReturn(null);
        Mockito.when(media.getObject(ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(".x{}".getBytes(StandardCharsets.UTF_8)));
        final TripStylesheets bucketed = withMedia(media);
        final MediaItem created = bucketed.create(ORG, "Bucket " + UUID.randomUUID(), ".x{}");
        assertNotNull(created);
        Mockito.verify(media, Mockito.times(1)).putObject(ArgumentMatchers.eq(created.getS3Key()),
                ArgumentMatchers.any(byte[].class), ArgumentMatchers.eq("text/css"), ArgumentMatchers.anyLong(),
                ArgumentMatchers.eq(false));
        Mockito.verify(media, Mockito.never()).invalidateCdn(ArgumentMatchers.anyList());
        assertTrue(bucketed.save(created.getId(), ".y{}"));
        Mockito.verify(media, Mockito.times(1)).invalidateCdn(List.of("/" + created.getS3Key()));
        assertEquals(bucketed.read(created.getId()), ".x{}", "read through the bucket");
        assertTrue(bucketed.rename(created.getId(), "Renamed"));
        Mockito.verify(media, Mockito.times(1)).invalidateCdn(ArgumentMatchers.anyList());
    }

    @Test
    public void theClassReportNamesWhatThePageReferences() throws IOException {
        final Trip trip = Trip.builder().title("Classes Trip").orgId(ORG).openToPublic(true)
                .startDate(LocalDateTime.now().plusDays(30)).endDate(LocalDateTime.now().plusDays(32)).build();
        trip.setDescription("<p class=\"legacy-note\">old</p>");
        assertTrue(dao.saveTrip(trip));
        final String page = OrgPageBootstrap.tripPageKey(trip.getId());
        final Map<String, String> values = new HashMap<>();
        values.put("body", "<div class=\"pw-callout band-heading ui-button\">hi</div>");
        final ContentInstance section = new ContentInstance(UUID.randomUUID().toString(), page, "t",
                StarterTemplates.TEXT_ONLY_ID, 1, values, null, 0, 0, null, "test");
        assertTrue(dao.saveContent(section, 3));
        // A child of the section (a container's item): its classes count too.
        final Map<String, String> childValues = new HashMap<>();
        childValues.put("body", "<p class=\"pw-child\">x</p>");
        assertTrue(dao.saveContent(new ContentInstance(UUID.randomUUID().toString(), section.getId(), "c",
                StarterTemplates.TEXT_ONLY_ID, 1, childValues, null, 0, 0, null, "test"), 3));

        final List<TripStylesheets.ClassRow> rows = sheets.classReport(trip.getId(),
                ".contentItem .pw-callout { color: red }");
        final List<String> names = new ArrayList<>();
        for (final TripStylesheets.ClassRow row : rows) {
            names.add(row.name() + ":" + row.group());
        }
        assertTrue(names.contains("legacy-note:custom"), names.toString());
        assertTrue(names.contains("pw-child:custom"), "a child row's classes: " + names);
        assertFalse(names.stream().anyMatch(n -> n.startsWith("ui-button")), "framework classes are left out");
        assertTrue(names.contains("pw-callout:styled"), names.toString());
        assertTrue(names.contains("contentItem:styled"),
                "the template's own wrapper class is reported, and the rule's selector styles it: " + names);
        assertTrue(names.contains("contentText:custom"), "a rendered class nothing styles yet: " + names);
        assertEquals(names.get(0).split(":")[1], "custom", "custom classes come first");
        assertTrue(rows.stream().allMatch(row -> row.rule().startsWith(".contentItem ." + row.name())));
        assertTrue(sheets.classReport(null, null).isEmpty());
        assertTrue(sheets.classReport("not-a-uuid", null).isEmpty());

        final jakarta.faces.component.UIComponent source = Mockito.mock(jakarta.faces.component.UIComponent.class);
        assertTrue(sheets.complete(new CompleteEvent(source, ".leg", "", 0, 0)).isEmpty(),
                "off-page, no trip pinned in the view: nothing to complete");
        tripInView = trip.getId();
        assertEquals(sheets.complete(new CompleteEvent(source, ".leg", "", 0, 0)), List.of("legacy-note"));
        assertEquals(sheets.complete(new CompleteEvent(source, "pw", "", 0, 0)), List.of("pw-callout", "pw-child"));
        assertTrue(sheets.complete(new CompleteEvent(source, null, "", 0, 0)).contains("contentItem"),
                "no token: everything");
        assertTrue(sheets.complete(null).size() <= 20);
    }
}
