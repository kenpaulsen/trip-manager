package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.content.OrgPageBootstrap;
import org.paulsens.trip.content.StarterTemplates;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripKind;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

/**
 * The conference page's look and its placeholders (docs/conference.md, "Look and feel" and "Placeholders start
 * hidden"): the trip's own options over the org's, the stylesheet rule, the editor's summary, and the first save
 * that publishes a seeded placeholder.
 */
public class ConferencePageLookTest {
    private static final Person.Id MANAGER = Person.Id.from("conf-look-manager");
    private static final LocalDateTime NOW = LocalDateTime.of(2027, 2, 1, 12, 0);

    private final PrivilegeCommands priv = new PrivilegeCommands();
    private DAO dao;
    private Trip conference;
    private MediaItem css;

    @BeforeClass
    public void seed() throws IOException {
        dao = DAO.getInstance();
        FakeData.addFakeData();
        conference = conferenceTrip("Look Conference");
        css = new MediaItem("conf-look-css", "org/" + FakeData.CFPW_ORG_ID + "/conferences/look/page.css",
                "Look page stylesheet", null, "text/css", 120L, null, 0, LocalDateTime.now(), "seed", null, null,
                FakeData.CFPW_ORG_ID);
        assertTrue(dao.saveMedia(css));
        assertTrue(dao.saveMedia(new MediaItem("conf-look-img",
                "org/" + FakeData.CFPW_ORG_ID + "/conferences/look/bg.jpg",
                "Look background", null, "image/jpeg", 120L, null, 0, LocalDateTime.now(), "seed", null, null,
                FakeData.CFPW_ORG_ID)));
    }

    // ------------------------------------------------------------------ the look

    @Test
    public void withNoOptionsThePageIsExactlyTheOrgs() {
        final AtomicBoolean orgFullWidth = new AtomicBoolean(false);
        final ConferenceCommands views = new ConferenceCommands(() -> null, () -> NOW, orgFullWidth::get);
        assertNull(views.lookFor(FakeData.FAKE_TRIP_ID), "a pilgrimage has no conference page");
        assertNull(views.lookFor(UUID.randomUUID().toString()), "no such trip");
        assertNull(views.lookFor(" "));
        assertNull(views.lookFor(null));

        ConferenceCommands.PageLook look = views.lookFor(conference.getId());
        assertNotNull(look);
        assertFalse(look.fullWidth(), "follows the org: classic");
        orgFullWidth.set(true);
        look = views.lookFor(conference.getId());
        assertTrue(look.fullWidth(), "follows the org: full width");
        assertNull(look.rootStyle(), "no background of its own: the template keeps brand.rootStyle");
        assertFalse(look.titleShown(), "the hero carries the title");
        assertTrue(look.navShown());
        assertNull(look.stylesheetUrl());
    }

    @Test
    public void theTripsOwnOptionsLayOverTheOrgs() throws IOException {
        final Trip trip = conferenceTrip("Own Look");
        trip.getPage().setLayout(KnownSettings.LAYOUT_CLASSIC);
        trip.getPage().setBackgroundUrl("https://cdn.example/bg(1).jpg");
        trip.getPage().setShowTitle(true);
        trip.getPage().setShowNav(false);
        trip.getPage().setStylesheetUrl("https://cdn.example/page.css");
        assertTrue(dao.saveTrip(trip));
        final ConferenceCommands views = new ConferenceCommands(() -> null, () -> NOW, () -> true);
        ConferenceCommands.PageLook look = views.lookFor(trip.getId());
        assertFalse(look.fullWidth(), "classic of its own while the org is full width");
        assertEquals(look.rootStyle(), "--site-bg:url(https://cdn.example/bg%281%29.jpg);--layout-full-bg:transparent",
                "the org setting's own escaping, plus the full-width ground made transparent");
        assertTrue(look.titleShown());
        assertFalse(look.navShown());
        assertEquals(look.stylesheetUrl(), "https://cdn.example/page.css");

        trip.getPage().setLayout(KnownSettings.LAYOUT_FULL_WIDTH);
        trip.getPage().setBackgroundUrl(null);
        trip.getPage().setBackgroundColor("#336699");
        trip.getPage().setStylesheetUrl("https://cdn.example/\"quoted\".css");
        assertTrue(dao.saveTrip(trip));
        look = new ConferenceCommands(() -> null, () -> NOW, () -> false).lookFor(trip.getId());
        assertTrue(look.fullWidth(), "full width of its own while the org is classic");
        assertEquals(look.rootStyle(), "--site-bg:none;--site-bg-color:#336699");
        assertNull(look.stylesheetUrl(), "a URL that could end the attribute is dropped, never emitted");
    }

    @Test
    public void theLookIsComputedOncePerRequest() {
        final Map<String, Object> request = new HashMap<>();
        final ConferenceCommands views = new ConferenceCommands(() -> request, () -> NOW, () -> false);
        final ConferenceCommands.PageLook first = views.lookFor(conference.getId());
        assertSame(views.lookFor(conference.getId()), first, "the build-time params and the render share it");
        assertTrue(request.containsKey("conferenceLook:" + conference.getId()));
    }

    // ------------------------------------------------------------------ what the editor may save

    @Test
    public void aStylesheetMustBeACssFileInTheTripsOrgLibrary() throws IOException {
        final ConferenceCommands views = new ConferenceCommands(() -> null, () -> NOW, () -> false);
        final Trip trip = conferenceTrip("Styled");
        assertTrue(views.pageProblems(trip).isEmpty(), "no options, nothing to refuse");
        assertEquals(views.pageSummary(trip), "the organization's look");

        trip.getPage().setStylesheetUrl("https://cdn.example/anything.css");
        assertEquals(views.pageProblems(trip), List.of(ConferenceCommands.STYLESHEET_RULE), "not in the library");
        trip.getPage().setStylesheetUrl("/org/" + FakeData.CFPW_ORG_ID + "/conferences/look/bg.jpg");
        assertEquals(views.pageProblems(trip), List.of(ConferenceCommands.STYLESHEET_RULE), "an image, not CSS");
        trip.getPage().setStylesheetUrl("/" + css.getS3Key());
        assertTrue(views.pageProblems(trip).isEmpty(), "the org's own CSS item, by its public URL");
        assertTrue(views.acceptPage(trip));

        trip.setOrgId(FakeData.ACME_ORG_ID);
        assertEquals(views.pageProblems(trip), List.of(ConferenceCommands.STYLESHEET_RULE),
                "another org's item is not this trip's to load");
        trip.setOrgId(FakeData.CFPW_ORG_ID);

        trip.getPage().setLayout("sideways");
        trip.getPage().setBackgroundColor("red");
        final List<String> problems = views.pageProblems(trip);
        assertEquals(problems.size(), 2, String.join(" | ", problems));
        assertFalse(views.acceptPage(trip));
        trip.getPage().setLayout(KnownSettings.LAYOUT_FULL_WIDTH);
        trip.getPage().setBackgroundColor(null);
        trip.getPage().setBackgroundUrl("https://cdn.example/bg.jpg");
        trip.getPage().setShowTitle(true);
        trip.getPage().setShowNav(false);
        assertEquals(views.pageSummary(trip),
                "full width, background image, title shown, section links hidden, own stylesheet");
        trip.getPage().setBackgroundUrl(null);
        trip.getPage().setBackgroundColor("#abc");
        assertEquals(views.pageSummary(trip), "full width, background #abc, title shown, section links hidden, "
                + "own stylesheet");
    }

    @Test
    public void saveTripRefusesBadPageOptions() throws IOException {
        final Trip trip = conferenceTrip("Refused");
        trip.getPage().setStylesheetUrl("https://cdn.example/anything.css");
        assertFalse(new TripCommands().saveTrip(trip), "closing the dialog with its X skips Done; Save still checks");
        trip.getPage().setStylesheetUrl(null);
        assertTrue(new TripCommands().saveTrip(trip));
    }

    // ------------------------------------------------------------------ placeholders publish themselves

    @Test
    public void theFirstSaveOfASeededPlaceholderPublishesItAndItsContainer() throws IOException {
        final Trip trip = conferenceTrip("Published");
        priv.savePrivilege(priv.createPrivilege(PrivilegeCommands.TRIP_MGR, "look mgr", trip.getId(),
                List.of(MANAGER)));
        assertTrue(new ConferenceCommands(() -> null, () -> NOW).ensurePage(trip));
        final String page = OrgPageBootstrap.tripPageKey(trip.getId());
        final ContentInstance speakers = row(page, StarterTemplates.PEOPLE_LIST_ID);
        final ContentInstance speaker = dao.getContentForSection(speakers.getId(), Cached.NO).get(0);
        final ContentInstance venue = row(page, StarterTemplates.BAND_SPLIT_ID);
        final ContentInstance hero = row(page, StarterTemplates.BAND_HERO_ID);
        assertTrue(OrgPageBootstrap.isSeededPlaceholder(speakers));
        assertTrue(OrgPageBootstrap.isSeededPlaceholder(speaker));
        assertTrue(OrgPageBootstrap.isSeededPlaceholder(venue));
        assertFalse(OrgPageBootstrap.isSeededPlaceholder(hero), "the hero shows at once and is no placeholder");
        final ContentCommands content = new ContentCommands();
        content.setCallerSource(() -> TestCallers.person(MANAGER));
        assertTrue(content.isSeededPlaceholder(venue), "the dialog's note");

        // Filling in the sample speaker publishes the card AND the Speakers band around it. The dialog's calendar
        // hands the seed date back without its seconds; that still counts as untouched.
        final ContentInstance edited = dao.getContent(speaker.getId(), Cached.NO).orElseThrow();
        edited.getValues().put("name", "Sr. Real Speaker");
        edited.setEventDate(edited.getEventDate().withSecond(0).withNano(0));
        assertTrue(content.saveContent(edited));
        assertNull(dao.getContent(speaker.getId(), Cached.NO).orElseThrow().getEventDate(), "the card is public");
        final ContentInstance band = dao.getContent(speakers.getId(), Cached.NO).orElseThrow();
        assertNull(band.getEventDate(), "its container is public too");
        assertFalse(OrgPageBootstrap.isSeededPlaceholder(band), "and no longer the seed's");
        assertTrue(band.isVisibleAt(LocalDateTime.now()));

        // An editor who sets a date of their own keeps it: only the seed's exact date is cleared.
        final ContentInstance dated = dao.getContent(venue.getId(), Cached.NO).orElseThrow();
        dated.setEventDate(NOW.plusYears(5));
        assertTrue(content.saveContent(dated));
        assertEquals(dao.getContent(venue.getId(), Cached.NO).orElseThrow().getEventDate(), NOW.plusYears(5));

        // Restoring the seeded version of a published row never hides it again.
        final ContentInstance faq = row(page, StarterTemplates.BAND_FAQ_ID);
        final ContentInstance faqEdit = dao.getContent(faq.getId(), Cached.NO).orElseThrow();
        faqEdit.setTitle("Your questions");
        assertTrue(content.saveContent(faqEdit));
        assertNull(dao.getContent(faq.getId(), Cached.NO).orElseThrow().getEventDate());
        assertTrue(content.restoreContent(faq.getId(), 1));
        final ContentInstance restored = dao.getContent(faq.getId(), Cached.NO).orElseThrow();
        assertEquals(restored.getTitle(), "Questions", "the seeded title is back");
        assertNull(restored.getEventDate(), "but not the seed's hiding date");
        assertFalse(content.isSeededPlaceholder(restored));
    }

    // ------------------------------------------------------------------ the event's own photo album

    @Test
    public void aPhotoAlbumsSectionOnAConferencePageShowsThatEventsAlbumAlone() throws IOException {
        final Trip trip = conferenceTrip("Pictured");
        for (int i = 1; i <= 2; i++) {
            assertTrue(dao.saveMedia(new MediaItem("conf-look-photo-" + i, "chat/" + trip.getId() + "/p" + i + ".jpg",
                    "P" + i, null, "image/jpeg", 100L, "tripChat-" + trip.getId(), 0, LocalDateTime.now(), "seed",
                    null, null)));
        }
        final MediaCommands media = TestCallers.mediaAsSiteAdmin();
        final String page = OrgPageBootstrap.tripPageKey(trip.getId());
        final ContentInstance onPage = new ContentInstance(UUID.randomUUID().toString(), page, "Pictures",
                StarterTemplates.PHOTO_ALBUMS_ID, 1, new HashMap<>(), null, 9, 0, null, "test");
        List<MediaCommands.TripAlbum> albums = media.getHomeAlbumsFor(onPage, null);
        assertEquals(albums.size(), 1, "the page's own event, however young or small its album");
        assertEquals(albums.get(0).trip().getId(), trip.getId());
        assertEquals(albums.get(0).photos().size(), 2);

        final ContentInstance inContainer = new ContentInstance(UUID.randomUUID().toString(), "some-container",
                "Pictures", StarterTemplates.PHOTO_ALBUMS_ID, 1, new HashMap<>(), null, 0, 0, null, "test");
        assertEquals(media.getHomeAlbumsFor(inContainer, trip.getId()).get(0).trip().getId(), trip.getId(),
                "inside a container the host page's trip names the album");
        inContainer.getValues().put(ConferenceCommands.PROP_TRIP_ID, conference.getId());
        assertTrue(media.getHomeAlbumsFor(inContainer, trip.getId()).isEmpty(),
                "an explicit pick wins, and an event with no photos renders nothing");
        assertEquals(media.getHomeAlbumsFor(onPage), media.getHomeAlbumsFor(onPage, null), "the one-arg form");
        final ContentInstance onHome = new ContentInstance(UUID.randomUUID().toString(), FakeData.PAGE_KEY,
                "Pictures", StarterTemplates.PHOTO_ALBUMS_ID, 1, new HashMap<>(), null, 0, 0, null, "test");
        assertTrue(media.getHomeAlbumsFor(onHome, null).stream().noneMatch(a -> a.trip().getId().equals(trip.getId())),
                "the home page's recent-trips listing is unchanged: a future conference is not in it");
    }

    private ContentInstance row(final String page, final String templateId) {
        return dao.getContentForSection(page, Cached.NO).stream()
                .filter(s -> templateId.equals(s.getTemplateId())).findFirst().orElseThrow();
    }

    private Trip conferenceTrip(final String title) throws IOException {
        final Trip trip = Trip.builder().title(title).kind(TripKind.CONFERENCE).orgId(FakeData.CFPW_ORG_ID)
                .openToPublic(true).startDate(NOW.plusDays(50)).endDate(NOW.plusDays(52)).build();
        assertTrue(dao.saveTrip(trip));
        return trip;
    }
}
