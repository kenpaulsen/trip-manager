package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.content.OrgPageBootstrap;
import org.paulsens.trip.content.PageVersions;
import org.paulsens.trip.content.ProgrammaticContentTemplate;
import org.paulsens.trip.content.ProgrammaticTypes;
import org.paulsens.trip.content.StarterTemplates;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripKind;
import org.paulsens.trip.model.TripPageOptions;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * The trip page's top block as two sections (docs/trip-pictures.md, "On the trip page") and the Trip Page's "Copy
 * from another trip" (docs/conference.md, "Copying a page").
 */
public class TripPageCloneTest {
    private static final Person.Id MANAGER = Person.Id.from("clone-page-manager");
    private static final Person.Id NOBODY = Person.Id.from("clone-page-nobody");
    private static final LocalDateTime NOW = LocalDateTime.of(2027, 2, 1, 9, 0);

    private final PrivilegeCommands priv = new PrivilegeCommands();
    private final TripPageCommands pages = new TripPageCommands(() -> null, () -> NOW);
    private DAO dao;

    @BeforeClass
    public void seed() {
        FakeData.initFakeData();
        FakeData.addFakeData();
        dao = DAO.getInstance();
    }

    // ------------------------------------------------------------------ the two section types

    @Test
    public void theTopSectionTypesBelongOnATripsOwnPageOnly() {
        final ProgrammaticContentTemplate pictures = ProgrammaticTypes.byId("trip-pictures").orElseThrow();
        final ProgrammaticContentTemplate facts = ProgrammaticTypes.byId("trip-details").orElseThrow();
        assertTrue(pictures.onlyTripPage() && facts.onlyTripPage());
        assertFalse(ProgrammaticTypes.byId("trip-schedule").orElseThrow().onlyTripPage(), "every other type: no");
        assertEquals(pictures.choicesFor(TripPageCommands.PROP_PICTURES_SHOW).stream()
                .map(ProgrammaticContentTemplate.Choice::value).toList(),
                List.of("", TripPageCommands.PICTURES_COVER_ONLY));
        assertEquals(facts.choicesFor(TripPageCommands.PROP_FACTS_SHOWN).size(), TripPageCommands.FACTS.size());
        assertTrue(facts.choicesFor("other").isEmpty() && pictures.choicesFor("other").isEmpty());
        assertNull(pictures.previewValues(new ProgrammaticContentTemplate.PreviewContext(null, null)),
                "off a trip page: the sample");
        assertNull(facts.previewValues(new ProgrammaticContentTemplate.PreviewContext(null, null)));
        assertNull(facts.previewValues(new ProgrammaticContentTemplate.PreviewContext("no-such-trip", null)));
    }

    @Test
    public void theFactsLineShowsTheDefaultFactsOrThePick() throws IOException {
        final Trip trip = trip("Facts Trip", FakeData.CFPW_ORG_ID);
        trip.setDestination("Rome, Italy");
        trip.setEstimatedPrice("$2,400");
        assertTrue(dao.saveTrip(trip));
        final ContentInstance none = factsRow(trip, null);
        assertTrue(pages.showsFact(none, "destination") && pages.showsFact(null, "price"));
        assertFalse(pages.showsFact(none, "dates"), "the banner carries the dates by default");
        final ContentInstance picked = factsRow(trip, "dates, price");
        assertTrue(pages.showsFact(picked, "dates") && pages.showsFact(picked, "price"));
        assertFalse(pages.showsFact(picked, "destination"));
        assertTrue(pages.hasFact(trip, "dates") && pages.hasFact(trip, "destination"));
        assertFalse(pages.hasFact(trip, "guide") || pages.hasFact(trip, "flyer") || pages.hasFact(trip, "nope"));
        assertFalse(pages.hasFact(null, "dates") || pages.hasFact(trip, null));
        assertTrue(pages.hasFacts(trip, none));
        assertFalse(pages.hasFacts(trip, factsRow(trip, "guide")), "a pick the trip has nothing for");
        assertFalse(pages.hasFact(trip, "director") || pages.hasFact(trip, "coordinators"));
        trip.setDirector("Fr. Example");
        trip.setLocalGuide("Ana");
        trip.setFacilitators("Ken and Trinity");
        assertTrue(pages.hasFact(trip, "director") && pages.hasFact(trip, "guide")
                && pages.hasFact(trip, "coordinators") && pages.hasFact(trip, "price"), "each staff fact, typed");
        assertFalse(pages.hasFacts(null, none));
        assertFalse(pages.factDates(trip).isEmpty());
        assertEquals(pages.factDates(null), "");
        assertFalse(pages.coverOnly(none) || pages.coverOnly(null));
        final ContentInstance cover = factsRow(trip, null);
        cover.getValues().put(TripPageCommands.PROP_PICTURES_SHOW, TripPageCommands.PICTURES_COVER_ONLY);
        assertTrue(pages.coverOnly(cover));
    }

    @Test
    public void theTopRowsComeFirstWithTheTripsOwnIds() {
        final Trip trip = Trip.builder().title("2027 Apr: Top Rows").build();
        final List<ContentInstance> rows = OrgPageBootstrap.tripRows(trip, "CFPW", id -> 2, NOW, true);
        assertEquals(rows.subList(0, 2).stream().map(ContentInstance::getTemplateId).toList(),
                OrgPageBootstrap.tripTopTemplateIds());
        assertEquals(rows.stream().map(ContentInstance::getPosition).toList(), List.of(0, 1, 2, 3, 4));
        assertEquals(rows.get(0).getId(), OrgPageBootstrap.topRows(trip, id -> 2).get(0).getId(), "deterministic");
        assertNull(rows.get(0).getEventDate(), "never a placeholder: the trip's own data shows at once");
        assertEquals(OrgPageBootstrap.tripRows(trip, "CFPW", id -> 2, NOW, false),
                OrgPageBootstrap.tripRows(trip, "CFPW", id -> 2, NOW), "without them, the starters as before");
    }

    // ------------------------------------------------------------------ copying a page

    @Test
    public void aPageIsCopiedFromAnotherTripOfTheSameOrganization() throws IOException {
        final Trip source = trip("2027 May: Source", FakeData.CFPW_ORG_ID);
        source.setKind(TripKind.CONFERENCE);
        assertTrue(dao.saveTrip(source));
        final Trip target = trip("2027 Jun: Target", FakeData.CFPW_ORG_ID);
        final Trip elsewhere = trip("2027 Jun: Elsewhere", FakeData.ACME_ORG_ID);
        final Trip unseeded = trip("2027 Jun: Unseeded", FakeData.CFPW_ORG_ID);
        for (final Trip each : List.of(source, target, elsewhere, unseeded)) {
            manage(each);
        }
        assertTrue(pages.ensurePage(source));
        assertTrue(pages.ensurePage(target));
        assertTrue(pages.ensurePage(elsewhere));
        final String sourceKey = OrgPageBootstrap.tripPageKey(source.getId());
        final String targetKey = OrgPageBootstrap.tripPageKey(target.getId());
        // A Spanish version of the source page, to be copied as a version of its own.
        assertTrue(dao.saveContent(new ContentInstance(UUID.randomUUID().toString(),
                PageVersions.keyFor(sourceKey, Language.Spanish), "Acerca", StarterTemplates.TEXT_ONLY_ID, 1,
                new HashMap<>(Map.of("body", "<p>Hola</p>")), null, 0, 0, null, "test"), 3));
        final List<String> oldTarget = ids(targetKey);

        final List<String> offered = pages.cloneSources(target, MANAGER).stream()
                .map(TripPageCommands.CloneSource::id).toList();
        assertTrue(offered.contains(source.getId()), "the organization's seeded trip");
        assertFalse(offered.contains(target.getId()), "never the trip itself");
        assertFalse(offered.contains(elsewhere.getId()), "never another organization's trip");
        assertFalse(offered.contains(unseeded.getId()), "never a page with nothing to copy");
        assertTrue(pages.cloneSources(target, NOBODY).isEmpty(), "nothing the person may not edit");
        assertTrue(pages.cloneSources(null, MANAGER).isEmpty() && pages.cloneSources(target, null).isEmpty());

        assertFalse(pages.clonePage(target, elsewhere.getId(), false, MANAGER), "another organization's page");
        assertFalse(pages.clonePage(target, source.getId(), false, NOBODY), "not the person's to edit");
        assertFalse(pages.clonePage(target, " ", false, MANAGER), "no trip picked");
        assertFalse(pages.clonePage(target, UUID.randomUUID().toString(), false, MANAGER), "no such trip");
        assertEquals(ids(targetKey), oldTarget, "every refusal leaves the page as it was");

        final TripPageOptions look = new TripPageOptions("full-width", null, null, true, false, null);
        source.setPage(look);
        assertTrue(dao.saveTrip(source));
        final Trip draft = dao.getTrip(target.getId(), Cached.NO).orElseThrow();
        draft.setPageTopSectionsAt(null);
        assertTrue(pages.clonePage(draft, source.getId(), true, MANAGER));

        final List<ContentInstance> copied = dao.getContentForSection(targetKey, Cached.NO);
        assertEquals(copied.stream().map(ContentInstance::getTemplateId).toList(),
                dao.getContentForSection(sourceKey, Cached.NO).stream().map(ContentInstance::getTemplateId).toList(),
                "the source's sections, in order");
        assertTrue(copied.stream().noneMatch(row -> oldTarget.contains(row.getId())), "the old sections are gone");
        final ContentInstance hero = copied.stream()
                .filter(row -> StarterTemplates.BAND_HERO_ID.equals(row.getTemplateId())).findFirst().orElseThrow();
        assertEquals(hero.getValues().get("headline"), target.getShortTitle(), "the seeded headline is this trip's");
        assertEquals(hero.getValues().get("subheadline"), OrgPageBootstrap.dateRange(target));
        assertEquals(hero.getValues().get("primaryUrl"), TripPageCommands.JOIN_URL + target.getId());
        final String schedule = copied.stream()
                .filter(row -> StarterTemplates.TRIP_SCHEDULE_ID.equals(row.getTemplateId())).findFirst()
                .orElseThrow().getId();
        assertEquals(hero.getValues().get("secondaryUrl"), "#" + schedule, "the anchor names the copy's own row");
        final ContentInstance speakers = copied.stream()
                .filter(row -> StarterTemplates.PEOPLE_LIST_ID.equals(row.getTemplateId())).findFirst().orElseThrow();
        assertEquals(dao.getContentForSection(speakers.getId(), Cached.NO).size(), 1, "a group's items come along");
        assertTrue(OrgPageBootstrap.isSeededPlaceholder(dao.getContentForSection(speakers.getId(), Cached.NO)
                .get(0)), "a seeded placeholder's copy still publishes on its first save");
        assertEquals(dao.getContentForSection(PageVersions.keyFor(targetKey, Language.Spanish), Cached.NO).size(), 1,
                "every language version is copied");

        final Trip stored = dao.getTrip(target.getId(), Cached.NO).orElseThrow();
        assertNotNull(stored.getPageSeededAt());
        assertEquals(stored.getPageTopSectionsAt(), NOW, "the source shows its top block as sections, so this does");
        assertEquals(draft.getPageTopSectionsAt(), NOW, "on the working copy too, so its Save keeps it");
        assertEquals(draft.pageOrNull().getLayout(), "full-width", "the look goes into the working copy");
        assertNull(stored.pageOrNull() == null ? null : stored.pageOrNull().getLayout(),
                "and only there, until the editor saves");
    }

    @Test
    public void copiedValuesNameTheNewTrip() {
        final Trip source = Trip.builder().title("2027 Jul: From").startDate(NOW).endDate(NOW.plusDays(3)).build();
        final Trip target = Trip.builder().title("2027 Aug: To").startDate(NOW.plusDays(30))
                .endDate(NOW.plusDays(32)).build();
        final Map<String, String> values = new HashMap<>();
        values.put(TripPageCommands.PROP_TRIP_ID, source.getId());
        values.put("body", "<a href=\"/trip/tripDetails.jsf?trip=" + source.getId() + "\">x</a>"
                + " <a href=\"#old\">y</a>");
        values.put("headline", "Typed by hand");
        values.put("caption", "");
        final ContentInstance row = new ContentInstance("r", "s", "", StarterTemplates.BAND_HERO_ID, 1, values, null,
                0, 0, null, "test");
        final Map<String, String> out = TripPageCommands.rewriteForCopy(row, Map.of("old", "new"), source, target);
        assertEquals(out.get(TripPageCommands.PROP_TRIP_ID), "", "an explicit pick of the source is this page");
        assertTrue(out.get("body").contains("?trip=" + target.getId()) && out.get("body").contains("#new"));
        assertEquals(out.get("headline"), "Typed by hand", "an edited headline is the editor's own");
        assertEquals(out.get("caption"), "", "a blank value is copied as it is");
    }

    /**
     * Copying from a page with no look of its own, asked to copy the look, leaves the working copy with none (the
     * organization's); an already seeded trip keeps its seed date; a trip that was never stored is refused.
     */
    @Test
    public void aCopyOfAPageWithoutALookGivesTheOrganizationsLook() throws IOException {
        final Trip source = trip("2027 Sep: Plain Source", FakeData.CFPW_ORG_ID);
        final Trip target = trip("2027 Oct: Plain Target", FakeData.CFPW_ORG_ID);
        manage(source);
        manage(target);
        assertTrue(pages.ensurePage(source));
        assertTrue(pages.ensurePage(target));
        final LocalDateTime seeded = dao.getTrip(target.getId(), Cached.NO).orElseThrow().getPageSeededAt();
        final Trip draft = dao.getTrip(target.getId(), Cached.NO).orElseThrow();
        draft.setPage(new TripPageOptions("classic", "https://example.com/bg.jpg", null, null, null, null));
        assertNotEquals(pages.pageSummary(draft), pages.pageSummary(null), "a classic layout and a picture differ");

        assertTrue(pages.clonePage(draft, source.getId(), true, MANAGER));
        assertTrue(draft.pageOrNull() == null || draft.pageOrNull().isEmpty(),
                "the source has no look of its own, so neither does the copy");
        assertEquals(dao.getTrip(target.getId(), Cached.NO).orElseThrow().getPageSeededAt(), seeded,
                "a seeded trip keeps its seed date");

        final Trip neverStored = Trip.builder().title("2027 Nov: Never Stored").orgId(FakeData.CFPW_ORG_ID).build();
        assertFalse(pages.clonePage(neverStored, source.getId(), false, MANAGER), "an unknown trip");
        assertFalse(pages.clonePage(draft, source.getId(), false, null), "nobody signed in");
        assertFalse(pages.clonePage(null, source.getId(), false, MANAGER));
        assertFalse(pages.clonePage(Trip.builder().id("not-a-uuid").title("Old").build(), source.getId(), false,
                MANAGER), "a trip whose id cannot carry a page");
    }

    /**
     * COPY FIRST, then delete: a copy that fails part way takes back every row it added, groups with their items,
     * and leaves the page exactly as it was, its stamps untouched.
     */
    @Test
    public void aCopyThatFailsPartWayLeavesThePageAsItWas() throws IOException {
        final Trip source = trip("2028 Jan: Failing Source", FakeData.CFPW_ORG_ID);
        source.setKind(TripKind.CONFERENCE);
        assertTrue(dao.saveTrip(source));
        final Trip target = trip("2028 Feb: Failing Target", FakeData.CFPW_ORG_ID);
        manage(source);
        manage(target);
        assertTrue(pages.ensurePage(source));
        assertTrue(pages.ensurePage(target));
        final String targetKey = OrgPageBootstrap.tripPageKey(target.getId());
        final List<String> before = ids(targetKey);
        final int sourceRows = dao.getContentForSection(OrgPageBootstrap.tripPageKey(source.getId()), Cached.NO)
                .size();
        final List<String> saved = new java.util.ArrayList<>();
        // A store that takes the first few rows (a group among them, with its item) and then refuses one.
        final TripPageCommands failing = new TripPageCommands(() -> null, () -> NOW) {
            @Override
            protected boolean saveCopy(final ContentInstance copy, final int retain) {
                return saved.size() < sourceRows - 2 && saved.add(copy.getId()) && super.saveCopy(copy, retain);
            }
        };
        final Trip draft = dao.getTrip(target.getId(), Cached.NO).orElseThrow();
        final LocalDateTime seeded = draft.getPageSeededAt();

        assertFalse(failing.clonePage(draft, source.getId(), true, MANAGER));
        assertFalse(saved.isEmpty(), "some rows were copied before the store refused one");
        assertEquals(ids(targetKey), before, "the page's own sections are untouched");
        for (final String id : saved) {
            assertTrue(dao.getContent(id, Cached.NO).isEmpty(), "every copied row is taken back: " + id);
        }
        assertEquals(dao.getTrip(target.getId(), Cached.NO).orElseThrow().getPageSeededAt(), seeded);
        assertTrue(draft.pageOrNull() == null || draft.pageOrNull().isEmpty(),
                "nothing reaches the working copy either");
    }

    /** The top-block stamp is the page's own code's: an editor's working copy taken earlier never clears it. */
    @Test
    public void aSaveKeepsTheStoredTopBlockStamp() {
        final Trip stored = Trip.builder().title("Stamped").build();
        stored.setPageTopSectionsAt(NOW);
        final Trip draft = Trip.builder().id(stored.getId()).title("Stamped").build();
        TripCommands.keepStoredPictures(draft, stored);
        assertEquals(draft.getPageTopSectionsAt(), NOW);
        final Trip fresh = Trip.builder().title("New").build();
        TripCommands.keepStoredPictures(fresh, null);
        assertNull(fresh.getPageTopSectionsAt(), "a brand-new trip has nothing stored to keep");
    }

    // ------------------------------------------------------------------ helpers

    private Trip trip(final String title, final String orgId) throws IOException {
        final Trip trip = Trip.builder().title(title).orgId(orgId).openToPublic(true).startDate(NOW.plusDays(40))
                .endDate(NOW.plusDays(45)).build();
        assertTrue(dao.saveTrip(trip));
        return trip;
    }

    private void manage(final Trip trip) {
        priv.savePrivilege(priv.createPrivilege(PrivilegeCommands.TRIP_MGR, "clone mgr " + trip.getTitle(),
                trip.getId(), List.of(MANAGER)));
    }

    private List<String> ids(final String key) {
        return dao.getContentForSection(key, Cached.NO).stream().map(ContentInstance::getId).toList();
    }

    private static ContentInstance factsRow(final Trip trip, final String shown) {
        final Map<String, String> values = new HashMap<>();
        if (shown != null) {
            values.put(TripPageCommands.PROP_FACTS_SHOWN, shown);
        }
        return new ContentInstance(UUID.randomUUID().toString(), OrgPageBootstrap.tripPageKey(trip.getId()), "",
                "trip-details", 1, values, null, 0, 0, null, "test");
    }
}
