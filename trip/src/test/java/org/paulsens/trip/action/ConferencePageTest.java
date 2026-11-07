package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.content.OrgPageBootstrap;
import org.paulsens.trip.content.StarterTemplates;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.OptionChoice;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.RegistrationOption;
import org.paulsens.trip.model.TemplateKind;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import org.paulsens.trip.model.TripKind;
import org.paulsens.trip.site.SiteContext;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

/**
 * The conference page (docs/conference.md): the trip-keyed page key, who may edit it (the trip's managers and its
 * org's content admins), which section types it offers, which site shows it, the schedule and registration views,
 * and seeding its starter sections exactly once.
 */
public class ConferencePageTest {
    private static final Person.Id MANAGER = Person.Id.from("conf-page-manager");
    private static final Person.Id CFPW_EDITOR = Person.Id.from("conf-page-cfpw-editor");
    private static final Person.Id NOBODY = Person.Id.from("conf-page-nobody");
    private static final LocalDateTime NOW = LocalDateTime.of(2027, 1, 10, 12, 0);
    private static final SiteContext ACME_SITE = SiteContext.org(Organization.Id.from(FakeData.ACME_ORG_ID),
            "acme", "acme.localhost");

    private final PrivilegeCommands priv = new PrivilegeCommands();
    private DAO dao;
    private Trip conference;
    private Trip other;

    @BeforeClass
    public void seed() throws IOException {
        FakeData.initFakeData();
        FakeData.addFakeData();
        dao = DAO.getInstance();
        conference = conferenceTrip("Page Conference");
        other = conferenceTrip("Other Conference");
        priv.savePrivilege(priv.createPrivilege(PrivilegeCommands.TRIP_MGR, "conference mgr", conference.getId(),
                List.of(MANAGER)));
        priv.savePrivilege(priv.createPrivilege(PrivilegeCommands.CONTENT_ADMIN, "cfpw content",
                FakeData.CFPW_ORG_ID, List.of(CFPW_EDITOR)));
    }

    // ------------------------------------------------------------------ the page key

    @Test
    public void aTripPageKeyNamesItsTripAndNothingElse() {
        final String id = UUID.randomUUID().toString();
        assertEquals(OrgPageBootstrap.tripPageKey(id), "page:trip:" + id + ":home");
        assertEquals(OrgPageBootstrap.tripOf(OrgPageBootstrap.tripPageKey(id)), id);
        assertEquals(OrgPageBootstrap.tripOf(OrgPageBootstrap.tripPageKey(id, "faq")), id, "sub-pages are reserved");
        assertNull(OrgPageBootstrap.tripOf("page:trip:not-a-uuid:home"));
        assertNull(OrgPageBootstrap.tripOf("page:trip:" + id + ":Bad Name"));
        assertNull(OrgPageBootstrap.tripOf("page:trip:" + id));
        assertNull(OrgPageBootstrap.tripOf(FakeData.PAGE_KEY));
        assertNull(OrgPageBootstrap.tripOf(null));
        assertNull(OrgPageBootstrap.orgOf(OrgPageBootstrap.tripPageKey(id)), "a trip page is no org page");
        assertThrows(IllegalArgumentException.class, () -> OrgPageBootstrap.tripPageKey("trip-1"));
        assertThrows(IllegalArgumentException.class, () -> OrgPageBootstrap.tripPageKey(null));
    }

    @Test
    public void everyReachableTripHasAPageKeyHere() throws Exception {
        final SiteCommands site = new SiteCommands();
        assertEquals(site.tripPageKey(conference.getId()), OrgPageBootstrap.tripPageKey(conference.getId()),
                "a CFPW conference on the shared site");
        assertEquals(site.tripPageKey(FakeData.FAKE_TRIP_ID), OrgPageBootstrap.tripPageKey(FakeData.FAKE_TRIP_ID),
                "every trip has a page, whatever its kind (2026-11)");
        assertNull(site.tripPageKey(UUID.randomUUID().toString()), "no such trip");
        assertNull(site.tripPageKey(" "));
        assertNull(site.tripPageKey(null));
        assertNull(onSite(ACME_SITE, () -> site.tripPageKey(conference.getId())),
                "another tenant's site never shows it");
    }

    // ------------------------------------------------------------------ who edits it

    @Test
    public void theTripsManagersAndItsOrgsContentAdminsEditItsPage() {
        final String page = OrgPageBootstrap.tripPageKey(conference.getId());
        final ContentCommands manager = contentAs(MANAGER);
        assertTrue(manager.canEdit(page, MANAGER), "the trip's Editor Admin edits its page");
        assertTrue(manager.canEditPage(page, MANAGER));
        assertFalse(manager.canEdit(OrgPageBootstrap.tripPageKey(other.getId()), MANAGER), "never another trip's");
        assertFalse(manager.canEdit(FakeData.PAGE_KEY, MANAGER), "never the shared home page");
        assertTrue(manager.saveContent(section(page)), "saves into its own trip's page");
        assertFalse(manager.saveContent(section(OrgPageBootstrap.tripPageKey(other.getId()))));

        // A container on the page: its children (section = the container id) belong to the page too.
        final ContentInstance holder = new ContentInstance(UUID.randomUUID().toString(), page, "Speakers",
                StarterTemplates.PEOPLE_LIST_ID, 1, new HashMap<>(), null, 9, 0, null, "test");
        assertTrue(manager.saveContent(holder));
        assertTrue(manager.canEdit(holder.getId(), MANAGER), "the container's children are the manager's too");

        assertTrue(contentAs(CFPW_EDITOR).canEdit(page, CFPW_EDITOR), "the trip's org's content admin");
        assertFalse(contentAs(NOBODY).canEdit(page, NOBODY));
        assertFalse(contentAs(NOBODY).saveContent(section(page)));
    }

    @Test
    public void theSpeakerPickerOffersOnlyTheTripsOrgMembers() {
        final String page = OrgPageBootstrap.tripPageKey(conference.getId());
        final List<Person> offered = contentAs(MANAGER).completeForContent(page, "");
        assertFalse(offered.isEmpty(), "the CFPW members are offered");
        // org_members is the source of truth (Person.orgIds is only its derived edge).
        final List<Person.Id> members = dao.getOrgMembers(Organization.Id.from(FakeData.CFPW_ORG_ID), Cached.NO)
                .stream().map(org.paulsens.trip.model.OrgMember::getPersonId).toList();
        for (final Person person : offered) {
            assertTrue(members.contains(person.getId()), person.getId() + " is not a CFPW member");
        }
        assertTrue(contentAs(NOBODY).completeForContent(page, "").isEmpty(), "nothing for a non-editor");
    }

    @Test
    public void aTripPageNeverOffersTheTypesThatListOtherTrips() {
        final ContentCommands content = contentAs(MANAGER);
        final List<String> offered = content.getTemplateChoicesFor(OrgPageBootstrap.tripPageKey(conference.getId()))
                .stream().map(ContentTemplate::getId).toList();
        assertTrue(offered.contains(StarterTemplates.TRIP_SCHEDULE_ID));
        assertTrue(offered.contains(StarterTemplates.TRIP_REGISTRATION_ID));
        assertTrue(offered.contains(StarterTemplates.BAND_HERO_ID));
        assertFalse(offered.contains(StarterTemplates.PILGRIMAGES_ID), "a trip listing is about other trips");
        assertTrue(offered.contains(StarterTemplates.PHOTO_ALBUMS_ID), "an event's own album belongs on its page");
        assertTrue(content.getTemplateChoicesFor(FakeData.PAGE_KEY).stream()
                        .anyMatch(t -> t.getKind() == TemplateKind.PROGRAMMATIC
                                && StarterTemplates.PILGRIMAGES_ID.equals(t.getId())),
                "the home page still offers it");
    }

    // ------------------------------------------------------------------ which trip a section shows

    @Test
    public void aSectionShowsItsPickedTripElseItsPagesElseTheHosts() throws Exception {
        final ConferenceCommands views = new ConferenceCommands(() -> null, () -> NOW);
        final ContentInstance onPage = section(OrgPageBootstrap.tripPageKey(conference.getId()));
        assertEquals(views.tripFor(onPage, null).getId(), conference.getId(), "from the page key");
        final ContentInstance picked = section(FakeData.PAGE_KEY);
        picked.getValues().put(ConferenceCommands.PROP_TRIP_ID, other.getId());
        assertEquals(views.tripFor(picked, conference.getId()).getId(), other.getId(), "an explicit pick wins");
        final ContentInstance child = section("some-container-id");
        assertEquals(views.tripFor(child, conference.getId()).getId(), conference.getId(), "else the host's trip");
        assertNull(views.tripFor(child, " "));
        assertNull(views.tripFor(null, null));
        assertNull(onSite(ACME_SITE, () -> views.tripFor(onPage, null)), "never a trip this site does not reach");
    }

    // ------------------------------------------------------------------ the public agenda

    @Test
    public void theAgendaIsTheEventsByDayWithoutLogisticsOrHiddenSessions() {
        final LocalDateTime day1 = LocalDateTime.of(2027, 3, 5, 0, 0);
        final TripEvent hidden = event("Volunteer briefing", day1.withHour(7), null);
        hidden.getDetails().put(TripEvent.Detail.AGENDA_HIDDEN.key(), "true");
        final TripEvent flight = new TripEvent(UUID.randomUUID().toString(), TripEvent.Type.FLIGHT, "SEA -> MCO",
                "", day1.withHour(6), null, null, null);
        final Trip trip = Trip.builder().title("Agenda").tripEvents(List.of(
                event("Keynote", day1.withHour(11), "Main Hall"), event("Opening", day1.withHour(9), "Main Hall"),
                hidden, flight, event("Closing", day1.plusDays(1).withHour(10), null))).build();

        final List<ConferenceCommands.AgendaDay> days = new ConferenceCommands(() -> null, () -> NOW)
                .publicAgenda(trip);
        assertEquals(days.size(), 2);
        assertEquals(days.get(0).label(), "Friday, March 5");
        assertEquals(days.get(0).items().stream().map(ConferenceCommands.AgendaItem::title).toList(),
                List.of("Opening", "Keynote"), "in start order; the flight and the hidden session are not program");
        final ConferenceCommands.AgendaItem opening = days.get(0).items().get(0);
        assertTrue(opening.time().contains("9:00") && opening.time().contains("10:00"), opening.time());
        assertEquals(opening.presenter(), "Presenter of Opening");
        assertEquals(opening.location(), "Main Hall");
        assertNull(opening.notes(), "blank notes show nothing");
        assertNull(days.get(1).items().get(0).location());
        assertEquals(new ConferenceCommands(() -> null, () -> NOW).publicAgenda(null), List.of());
    }

    @Test
    public void viewsAreComputedOncePerRequest() {
        final Map<String, Object> request = new HashMap<>();
        final ConferenceCommands views = new ConferenceCommands(() -> request, () -> NOW);
        final Trip trip = Trip.builder().title("Memo").build();
        final List<ConferenceCommands.AgendaDay> first = views.publicAgenda(trip);
        assertSame(views.publicAgenda(trip), first, "the request map holds it");
        assertTrue(request.containsKey("conferenceAgenda:" + trip.getId()));
    }

    // ------------------------------------------------------------------ the registration block

    @Test
    public void theRegistrationBlockCountsPlacesAndListsPrices() throws IOException {
        final Trip trip = conferenceTrip("Block Conference");
        trip.setRegLimit(3);
        trip.setRegOptions(List.of(new RegistrationOption(1, "Admission", "", true,
                RegistrationOption.QuestionType.CHOICE, true, List.of(
                        new OptionChoice("full", "Full weekend", 15000L, null, null, null, null),
                        new OptionChoice("kids", "Children", 0L, null, null, null, null),
                        new OptionChoice("gone", "Withdrawn", 999L, null, null, null, Boolean.FALSE))),
                new RegistrationOption(2, "Hidden", "", false, RegistrationOption.QuestionType.CHOICE, false,
                        List.of(new OptionChoice("x", "Secret", 100L, null, null, null, null)))));
        assertTrue(dao.saveTrip(trip));
        register(trip, Registration.Status.CONFIRMED);
        register(trip, Registration.Status.PENDING);
        register(trip, Registration.Status.NOT_REGISTERED);

        final ConferenceCommands views = new ConferenceCommands(() -> null, () -> NOW);
        final ConferenceCommands.RegistrationInfo info = views.registrationInfo(trip);
        assertEquals(info.phase(), ConferenceCommands.Phase.OPEN);
        assertEquals(info.taken(), 2, "confirmed and pending hold places; not-registered rows do not");
        assertEquals(info.seatsLeft(), Integer.valueOf(1));
        assertEquals(info.prices().size(), 1, "a hidden question is not advertised");
        assertEquals(info.prices().get(0).choices(), List.of("Full weekend: $150.00", "Children: free"));
        assertEquals(info.registerUrl(), "/trip/joinTrip.jsf?trip=" + trip.getId());
        assertEquals(info.registerLabel(), "Register");
        assertEquals(info.startDateIso(), trip.getStartDateIso());

        trip.setRegLimit(2);
        assertEquals(views.registrationInfo(trip).phase(), ConferenceCommands.Phase.FULL);
        trip.setRegLimit(null);
        assertNull(views.registrationInfo(trip).seatsLeft(), "no limit, no count");
        assertEquals(new ConferenceCommands(() -> null, () -> trip.getStartDate().plusHours(1))
                .registrationInfo(trip).phase(), ConferenceCommands.Phase.CLOSED, "under way");
        assertNull(views.registrationInfo(null));
    }

    @Test
    public void anUnpricedTripFallsBackToItsEstimatedPrice() {
        final Trip trip = Trip.builder().title("Plain").estimatedPrice("$95").build();
        final ConferenceCommands.RegistrationInfo info =
                new ConferenceCommands(() -> null, () -> NOW).registrationInfo(trip);
        assertTrue(info.prices().isEmpty());
        assertEquals(info.fallbackPrice(), "$95");
        assertEquals(info.registerLabel(), "Join Pilgrimage");
        final Trip external = Trip.builder().nonHostedTripUrl("https://example.com/reg").build();
        assertEquals(new ConferenceCommands().registerUrl(external), "https://example.com/reg");
        assertEquals(new ConferenceCommands().registerUrl(null), "");
    }

    // ------------------------------------------------------------------ seeding

    @Test
    public void savingAConferenceSeedsItsPageOnceAndNeverAgain() throws IOException {
        final Trip trip = Trip.builder().title("2027 Jul: Seeded Conference").kind(TripKind.CONFERENCE)
                .orgId(FakeData.CFPW_ORG_ID).startDate(LocalDateTime.now().plusDays(60))
                .endDate(LocalDateTime.now().plusDays(62)).build();
        // Making a conference takes Create Conferences (docs/conference.md, "Access"): a site admin here.
        final OrgCommands siteAdmin = new OrgCommands(() -> new Caller(Person.Id.newInstance(), true,
                org.paulsens.trip.audit.AuditActor.system(), new PrivilegeCommands()));
        assertTrue(new TripCommands().saveTrip(trip, siteAdmin), "every save path seeds through saveTrip");
        assertNotNull(trip.getPageSeededAt(), "stamped on the caller's own object");
        final String page = OrgPageBootstrap.tripPageKey(trip.getId());
        final List<ContentInstance> sections = dao.getContentForSection(page, Cached.NO);
        assertEquals(sections.size(), 8, "hero, about, speakers, schedule, registration, venue, questions, cta");
        final List<String> templates = sections.stream().map(ContentInstance::getTemplateId).toList();
        assertTrue(templates.containsAll(List.of(StarterTemplates.BAND_HERO_ID, StarterTemplates.TRIP_SCHEDULE_ID,
                StarterTemplates.TRIP_REGISTRATION_ID, StarterTemplates.PEOPLE_LIST_ID)));
        final ContentInstance speakers = sections.stream()
                .filter(s -> StarterTemplates.PEOPLE_LIST_ID.equals(s.getTemplateId())).findFirst().orElseThrow();
        assertEquals(dao.getContentForSection(speakers.getId(), Cached.NO).size(), 1, "one placeholder speaker");

        // Emptied by its editors and saved again: stays empty, because the trip remembers it was seeded.
        for (final ContentInstance section : sections) {
            dao.deleteContent(section.getId());
        }
        assertTrue(new ConferenceCommands().ensurePage(trip));
        assertTrue(dao.getContentForSection(page, Cached.NO).isEmpty());
    }

    @Test
    public void aPageAuthoredFirstIsLeftAloneAndEveryKindSeeds() throws IOException {
        final Trip trip = conferenceTrip("Authored Conference");
        final String page = OrgPageBootstrap.tripPageKey(trip.getId());
        final ContentInstance own = section(page);
        assertTrue(dao.saveContent(own, 3));
        assertTrue(new ConferenceCommands(() -> null, () -> NOW).ensurePage(trip));
        assertEquals(trip.getPageSeededAt(), NOW);
        assertEquals(dao.getContentForSection(page, Cached.NO).stream().map(ContentInstance::getId).toList(),
                List.of(own.getId()), "only the authored section");

        // Any other kind seeds too (2026-11): a hero, About and a video, the trip's own starters.
        final Trip plain = pilgrimageTrip("Seeded Trip", null);
        assertTrue(new ConferenceCommands(() -> null, () -> NOW).ensurePage(plain));
        assertEquals(plain.getPageSeededAt(), NOW);
        final List<ContentInstance> rows = dao.getContentForSection(OrgPageBootstrap.tripPageKey(plain.getId()),
                Cached.NO);
        assertEquals(rows.stream().map(ContentInstance::getTemplateId).toList(),
                List.of(StarterTemplates.BAND_HERO_ID, StarterTemplates.BAND_TEXT_ID,
                        StarterTemplates.YOUTUBE_VIDEO_ID));
        assertFalse(new ConferenceCommands().ensurePage(null));
    }

    // ------------------------------------------------------------------ legacy description pages

    @Test
    public void savingATripSeedsItsPageUnlessItStillCarriesADescription() throws IOException {
        final OrgCommands siteAdmin = new OrgCommands(() -> new Caller(Person.Id.newInstance(), true,
                org.paulsens.trip.audit.AuditActor.system(), new PrivilegeCommands()));
        final Trip fresh = Trip.builder().title("2027 Sep: Fresh Trip").orgId(FakeData.CFPW_ORG_ID)
                .startDate(LocalDateTime.now().plusDays(60)).endDate(LocalDateTime.now().plusDays(70)).build();
        assertTrue(new TripCommands().saveTrip(fresh, siteAdmin));
        assertNotNull(fresh.getPageSeededAt(), "a new trip gets its page at creation");
        assertEquals(dao.getContentForSection(OrgPageBootstrap.tripPageKey(fresh.getId()), Cached.NO).size(), 3);

        // A NEW trip created with a description seeds too: the description becomes its About section.
        final Trip described = Trip.builder().title("2027 Sep: Described Trip").orgId(FakeData.CFPW_ORG_ID)
                .startDate(LocalDateTime.now().plusDays(60)).endDate(LocalDateTime.now().plusDays(70)).build();
        described.setDescription("<p>Nine days in Rome.</p>");
        assertTrue(new TripCommands().saveTrip(described, siteAdmin));
        assertNotNull(described.getPageSeededAt());
        final ContentInstance about = dao.getContentForSection(OrgPageBootstrap.tripPageKey(described.getId()),
                Cached.NO).get(1);
        assertEquals(about.getValues().get("body"), "<p>Nine days in Rome.</p>");
        assertNull(about.getEventDate(), "shown at once");

        // A trip that EXISTED before every trip had a page, still carrying its description: legacy, not seeded.
        final Trip legacy = pilgrimageTrip("2027 Sep: Legacy Trip", "<p>The old way.</p>");
        legacy.setTitle("2027 Sep: Legacy Trip (edited)");
        assertTrue(new TripCommands().saveTrip(legacy, siteAdmin));
        assertNull(legacy.getPageSeededAt(), "an existing trip with a description stays a legacy page");
        assertTrue(dao.getContentForSection(OrgPageBootstrap.tripPageKey(legacy.getId()), Cached.NO).isEmpty());
    }

    @Test
    public void aLegacyPageRendersTheDescriptionUntilAnEditorConvertsIt() throws IOException {
        final ConferenceCommands views = new ConferenceCommands(() -> null, () -> NOW);
        final Trip legacy = pilgrimageTrip("Legacy Page", "<p>Come <b>along</b>.</p>");
        priv.savePrivilege(priv.createPrivilege(PrivilegeCommands.TRIP_MGR, "legacy mgr", legacy.getId(),
                List.of(MANAGER)));
        assertTrue(views.hasLegacyDescription(legacy));
        assertTrue(views.isLegacyPage(legacy.getId()));
        assertFalse(views.hasLegacyDescription(conference), "no description, nothing legacy about it");
        assertFalse(views.isLegacyPage(FakeData.CONF_TRIP_ID), "a trip with its page seeded is never legacy");
        assertFalse(views.isLegacyPage(UUID.randomUUID().toString()));
        assertFalse(views.isLegacyPage(" "));
        assertFalse(views.hasLegacyDescription(null));
        assertTrue(views.isLegacyPage(FakeData.PUB_EN_1_TRIP_ID),
                "a seeded trip with no description and no page still renders the old page");
        assertFalse(views.hasLegacyDescription(dao.getTrip(FakeData.PUB_EN_1_TRIP_ID, Cached.NO).orElseThrow()));

        // A stranger may not convert it; the trip is untouched.
        assertFalse(views.convertLegacyPage(legacy.getId(), NOBODY));
        assertNull(dao.getTrip(legacy.getId(), Cached.NO).orElseThrow().getPageSeededAt());
        assertFalse(views.convertLegacyPage(null, MANAGER));
        assertFalse(views.convertLegacyPage(legacy.getId(), null));
        assertFalse(views.convertLegacyPage(UUID.randomUUID().toString(), MANAGER), "no such trip");

        // The trip's manager converts: ONE text-only section, the description verbatim, no title, not a placeholder.
        assertTrue(views.convertLegacyPage(legacy.getId(), MANAGER));
        final Trip stamped = dao.getTrip(legacy.getId(), Cached.NO).orElseThrow();
        assertEquals(stamped.getPageSeededAt(), NOW);
        assertEquals(stamped.getDescription(), "<p>Come <b>along</b>.</p>", "the field itself is kept, read-only");
        final List<ContentInstance> rows = dao.getContentForSection(OrgPageBootstrap.tripPageKey(legacy.getId()),
                Cached.NO);
        assertEquals(rows.size(), 1);
        assertEquals(rows.get(0).getTemplateId(), StarterTemplates.TEXT_ONLY_ID);
        assertEquals(rows.get(0).getTitle(), "");
        assertEquals(rows.get(0).getValues().get("body"), "<p>Come <b>along</b>.</p>");
        assertNull(rows.get(0).getEventDate());
        assertFalse(OrgPageBootstrap.isSeededPlaceholder(rows.get(0)));
        assertFalse(new ConferenceCommands(() -> null, () -> NOW).isLegacyPage(legacy.getId()),
                "the page is sections from now on");

        // Idempotent: a second click (or a stamped trip) is simply true, and nothing doubles.
        assertTrue(views.convertLegacyPage(legacy.getId(), MANAGER));
        assertEquals(dao.getContentForSection(OrgPageBootstrap.tripPageKey(legacy.getId()), Cached.NO).size(), 1);

        // A page authored by hand before any conversion wins over the description: not legacy, nothing seeded.
        final Trip authored = pilgrimageTrip("Authored Legacy", "<p>ignored</p>");
        assertTrue(dao.saveContent(section(OrgPageBootstrap.tripPageKey(authored.getId())), 3));
        assertFalse(views.isLegacyPage(authored.getId()));
        final ConferenceCommands fresh = new ConferenceCommands(() -> null, () -> NOW);
        assertTrue(fresh.hasLegacyDescription(authored), "still unstamped");
        // Converting a trip whose id is no UUID is refused, never thrown.
        assertFalse(fresh.convertLegacyPage("legacy-trip", MANAGER));

        // A legacy trip WITHOUT a description gets its kind's starters instead of a description section.
        final Trip blank = pilgrimageTrip("Blank Legacy", null);
        priv.savePrivilege(priv.createPrivilege(PrivilegeCommands.TRIP_MGR, "blank mgr", blank.getId(),
                List.of(MANAGER)));
        final ConferenceCommands later = new ConferenceCommands(() -> null, () -> NOW);
        assertTrue(later.isLegacyPage(blank.getId()), "unstamped and empty: the old page");
        assertTrue(later.convertLegacyPage(blank.getId(), MANAGER));
        assertEquals(dao.getContentForSection(OrgPageBootstrap.tripPageKey(blank.getId()), Cached.NO).stream()
                .map(ContentInstance::getTemplateId).toList(),
                List.of(StarterTemplates.BAND_HERO_ID, StarterTemplates.BAND_TEXT_ID,
                        StarterTemplates.YOUTUBE_VIDEO_ID));
        assertEquals(dao.getTrip(blank.getId(), Cached.NO).orElseThrow().getPageSeededAt(), NOW);
    }

    @Test
    public void theLegacyRowIsTheDescriptionAndNothingElse() {
        final Trip trip = Trip.builder().title("Legacy Rows").build();
        trip.setDescription("<h2>Hi</h2><p>There</p>");
        final List<ContentInstance> rows = OrgPageBootstrap.legacyRows(trip, id -> 4);
        assertEquals(rows.size(), 1);
        assertEquals(rows.get(0).getId(), OrgPageBootstrap.legacyRows(trip, id -> 4).get(0).getId(),
                "deterministic");
        assertEquals(rows.get(0).getSection(), OrgPageBootstrap.tripPageKey(trip.getId()));
        assertEquals(rows.get(0).getValues(), Map.of("body", "<h2>Hi</h2><p>There</p>"));
        assertEquals(rows.get(0).getTemplateVersion(), 4);
        assertEquals(rows.get(0).getPosition(), 0);
        assertEquals(OrgPageBootstrap.legacyRowTemplateIds(), List.of(StarterTemplates.TEXT_ONLY_ID));
        assertTrue(OrgPageBootstrap.tripRowTemplateIds().contains(StarterTemplates.YOUTUBE_VIDEO_ID),
                "the union of every kind's starters");
    }

    @Test
    public void aTripsStarterRowsAreAHeroAboutAndAVideo() {
        final Trip trip = Trip.builder().title("2027 May: Rome").startDate(LocalDateTime.of(2027, 5, 1, 8, 0))
                .endDate(LocalDateTime.of(2027, 5, 9, 17, 0)).build();
        final List<ContentInstance> rows = OrgPageBootstrap.tripRows(trip, "CFPW", id -> 2, NOW);
        assertEquals(rows.stream().map(ContentInstance::getTemplateId).toList(),
                List.of(StarterTemplates.BAND_HERO_ID, StarterTemplates.BAND_TEXT_ID,
                        StarterTemplates.YOUTUBE_VIDEO_ID));
        assertEquals(rows, OrgPageBootstrap.pilgrimageRows(trip, "CFPW", id -> 2, NOW), "the kind dispatches");
        final ContentInstance hero = rows.get(0);
        assertEquals(hero.getValues().get("headline"), "Rome");
        assertEquals(hero.getValues().get("subheadline"), "May 1 - 9, 2027");
        assertEquals(hero.getValues().get("eyebrow"), "CFPW");
        assertEquals(hero.getValues().get("primaryText"), TripKind.PILGRIMAGE.getRegisterLabel());
        assertEquals(hero.getValues().get("primaryUrl"), "/trip/joinTrip.jsf?trip=" + trip.getId());
        assertEquals(hero.getValues().get("secondaryUrl"), "#" + rows.get(1).getId(), "About jumps to the section");
        assertNull(hero.getEventDate(), "the hero shows at once");
        assertEquals(rows.get(1).getEventDate(), NOW, "About is a hidden placeholder without a description");
        assertEquals(rows.get(2).getEventDate(), NOW, "so is the video until a URL is set");
        assertEquals(rows.get(2).getValues(), Map.of("videoUrl", "", "caption", ""));
        trip.setDescription("<p>Nine days.</p>");
        final ContentInstance about = OrgPageBootstrap.tripRows(trip, "CFPW", id -> 2, NOW).get(1);
        assertNull(about.getEventDate(), "a described trip's About shows");
        assertEquals(about.getValues().get("body"), "<p>Nine days.</p>");
    }

    @Test
    public void theEventPickerOffersTripsOfEveryKind() throws IOException {
        final Trip plain = pilgrimageTrip("2027 Mar: Picker Trip", null);
        final List<String> picks = org.paulsens.trip.content.ProgrammaticTypes
                .byId(StarterTemplates.TRIP_REGISTRATION_ID).orElseThrow()
                .choicesFor(ConferenceCommands.PROP_TRIP_ID).stream()
                .map(org.paulsens.trip.content.ProgrammaticContentTemplate.Choice::value)
                .filter(value -> !value.isEmpty())
                .toList();
        // Exactly the public listing, whatever each trip's kind (the old list kept conferences only).
        final List<String> expected = new TripCommands().getPublicTrips().stream().map(Trip::getId).toList();
        assertEquals(picks, expected);
        // The listing itself depends on the request's site and the suite's other fixtures; what this proves is
        // that the picker no longer filters it by kind (the plain public trip above is in it whenever it is listed).
        assertEquals(picks.contains(plain.getId()), expected.contains(plain.getId()));
        assertFalse(picks.contains(FakeData.FAKE_TRIP_ID), "never a trip that is not public");
    }

    @Test
    public void seedingWaitsForEveryStarterAndNeverThrows() {
        assertEquals(ConferenceCommands.missingStarter(id -> !StarterTemplates.TRIP_SCHEDULE_ID.equals(id)),
                StarterTemplates.TRIP_SCHEDULE_ID, "a missing starter is named, and nothing is seeded");
        assertNull(ConferenceCommands.missingStarter(id -> true));
        // A conference whose id is no UUID cannot have a page key: refused, logged, never thrown.
        final Trip legacy = Trip.builder().id("legacy-conference").title("Legacy").kind(TripKind.CONFERENCE).build();
        assertFalse(new ConferenceCommands().ensurePage(legacy));
        assertNull(legacy.getPageSeededAt(), "not stamped, so a later save retries");
        // Off-request (no FacesContext) the views still compute, just without the per-request memo.
        assertTrue(new ConferenceCommands().publicAgenda(Trip.builder().title("x").build()).isEmpty());
    }

    @Test
    public void theStarterRowsAreDeterministicAndAnchorTheSchedule() {
        final Trip trip = Trip.builder().title("2027 Mar: Rows").kind(TripKind.CONFERENCE)
                .startDate(LocalDateTime.of(2027, 3, 5, 8, 0)).endDate(LocalDateTime.of(2027, 3, 7, 17, 0)).build();
        final List<ContentInstance> rows = OrgPageBootstrap.tripRows(trip, "CFPW", id -> 2, NOW);
        assertEquals(rows.stream().map(ContentInstance::getId).toList(),
                OrgPageBootstrap.tripRows(trip, "CFPW", id -> 2, NOW).stream().map(ContentInstance::getId).toList(),
                "the same trip always seeds the same row ids");
        final ContentInstance hero = rows.get(0);
        final ContentInstance schedule = rows.stream()
                .filter(r -> StarterTemplates.TRIP_SCHEDULE_ID.equals(r.getTemplateId())).findFirst().orElseThrow();
        assertEquals(hero.getValues().get("secondaryUrl"), "#" + schedule.getId(), "Schedule jumps to the section");
        assertEquals(hero.getValues().get("headline"), "Rows");
        assertEquals(hero.getValues().get("subheadline"), "March 5 - 7, 2027");
        assertEquals(hero.getValues().get("primaryUrl"), "/trip/joinTrip.jsf?trip=" + trip.getId());
        assertTrue(rows.stream().allMatch(r -> r.getTemplateVersion() == 2), "pinned to the current version");
        assertTrue(OrgPageBootstrap.tripRowTemplateIds().containsAll(
                rows.stream().map(ContentInstance::getTemplateId).toList()));
    }

    @Test
    public void onlyThePlaceholderRowsAreSeededHiddenFromVisitors() {
        final Trip trip = Trip.builder().title("2027 Mar: Hidden").kind(TripKind.CONFERENCE).build();
        final Map<String, LocalDateTime> showUntil = new HashMap<>();
        for (final ContentInstance row : OrgPageBootstrap.tripRows(trip, "CFPW", id -> 2, NOW)) {
            showUntil.put(row.getTitle() + "/" + row.getTemplateId(), row.getEventDate());
            assertEquals(row.isVisibleAt(NOW.plusSeconds(1)), row.getEventDate() == null, row.getTitle());
        }
        // The sample speaker, the venue, the sample question and both containers: past "Show until", so hidden.
        for (final String placeholder : List.of("Speakers/" + StarterTemplates.PEOPLE_LIST_ID,
                "/" + StarterTemplates.PERSON_ID, "Venue/" + StarterTemplates.BAND_SPLIT_ID,
                "Questions/" + StarterTemplates.BAND_FAQ_ID, "How do I register?/" + StarterTemplates.TEXT_ONLY_ID,
                "About/" + StarterTemplates.BAND_TEXT_ID)) {
            assertEquals(showUntil.get(placeholder), NOW, placeholder);
        }
        // The trip's own data shows at once.
        for (final String real : List.of("/" + StarterTemplates.BAND_HERO_ID,
                "Schedule/" + StarterTemplates.TRIP_SCHEDULE_ID,
                "Registration/" + StarterTemplates.TRIP_REGISTRATION_ID, "/" + StarterTemplates.BAND_CTA_ID)) {
            assertTrue(showUntil.containsKey(real) && showUntil.get(real) == null, real);
        }
        // About is a placeholder only while the trip has no description; a described trip's About shows.
        trip.setDescription("<p>Our third conference.</p>");
        final ContentInstance about = OrgPageBootstrap.tripRows(trip, "CFPW", id -> 2, NOW).stream()
                .filter(r -> "About".equals(r.getTitle())).findFirst().orElseThrow();
        assertNull(about.getEventDate());
        assertEquals(about.getValues().get("body"), "<p>Our third conference.</p>");
    }

    // ------------------------------------------------------------------ helpers

    private Trip pilgrimageTrip(final String title, final String description) throws IOException {
        final Trip trip = Trip.builder().title(title).orgId(FakeData.CFPW_ORG_ID).openToPublic(true)
                .startDate(NOW.plusDays(50)).endDate(NOW.plusDays(58)).build();
        trip.setDescription(description);
        assertTrue(dao.saveTrip(trip));
        return trip;
    }

    private Trip conferenceTrip(final String title) throws IOException {
        final Trip trip = Trip.builder().title(title).kind(TripKind.CONFERENCE).orgId(FakeData.CFPW_ORG_ID)
                .openToPublic(true).startDate(NOW.plusDays(50)).endDate(NOW.plusDays(52)).build();
        assertTrue(dao.saveTrip(trip));
        return trip;
    }

    private void register(final Trip trip, final Registration.Status status) throws IOException {
        assertTrue(dao.saveRegistration(new Registration(trip.getId(), Person.Id.newInstance()).withStatus(status)));
    }

    private static TripEvent event(final String title, final LocalDateTime start, final String location) {
        final TripEvent event = new TripEvent(UUID.randomUUID().toString(), TripEvent.Type.EVENT, title, " ", start,
                start.plusHours(1), null, null);
        final Map<String, String> details = new HashMap<>();
        details.put(TripEvent.Detail.PRESENTER.key(), "Presenter of " + title);
        if (location != null) {
            details.put(TripEvent.Detail.LOCATION.key(), location);
        }
        event.setDetails(details);
        return event;
    }

    private static ContentInstance section(final String key) {
        return new ContentInstance(UUID.randomUUID().toString(), key, "t", StarterTemplates.TEXT_ONLY_ID, 1,
                new HashMap<>(Map.of("body", "<p>x</p>")), null, 0, 0, null, "test");
    }

    private static ContentCommands contentAs(final Person.Id who) {
        final ContentCommands content = new ContentCommands();
        content.setCallerSource(() -> TestCallers.person(who));
        return content;
    }

    private static <T> T onSite(final SiteContext site, final ScopedValue.CallableOp<T, Exception> body)
            throws Exception {
        return ScopedValue.where(RequestContext.SCOPE, RequestContext.of(null, null, site)).call(body);
    }
}
