package org.paulsens.trip.action;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class TripCommandsTest {
    private final TripCommands tripCommands = new TripCommands();
    private static List<Person> people;
    private static List<Trip> trips;

    @BeforeClass
    void beforeClass() {
        FakeData.initFakeData();
        FakeData.addFakeData();
        people = FakeData.getFakePeople();
        trips = FakeData.getFakeTrips();
    }

    @Test
    public void createTripForGatesOnOrgAuthorityAndStampsTheOrg() throws java.io.IOException {
        final String orgId = java.util.UUID.randomUUID().toString();
        final Trip created = asSiteAdmin().createTripFor(orgId);
        assertEquals(created.getOrgId(), orgId, "The new trip belongs to the requested org");
        assertEquals(created.getOpenToPublic(), Boolean.FALSE,
                "Show on homepage starts OFF for every new trip");
        assertNull(asNobody().createTripFor(orgId), "No org authority, no trip");
        assertNull(asSiteAdmin().createTripFor(null), "A new trip must name its org");
    }

    @Test
    public void getTripsForOrgFiltersByTenantAndGate() throws java.io.IOException {
        final String orgId = java.util.UUID.randomUUID().toString();
        final Trip mine = Trip.builder().title("Org Trip " + orgId).build();
        mine.setOrgId(orgId);
        final Trip other = Trip.builder().title("Other Trip " + orgId).build();
        other.setOrgId(java.util.UUID.randomUUID().toString());
        assertTrue(org.paulsens.trip.dynamo.DAO.getInstance().saveTrip(mine));
        assertTrue(org.paulsens.trip.dynamo.DAO.getInstance().saveTrip(other));

        final List<String> ids = asSiteAdmin().getTripsForOrg(orgId, 100).stream().map(Trip::getId).toList();
        assertTrue(ids.contains(mine.getId()));
        assertTrue(ids.stream().noneMatch(id -> id.equals(other.getId())),
                "Another tenant's trip never appears");
        assertEquals(asNobody().getTripsForOrg(orgId, 100), List.of(), "View-gated like the page");
        assertEquals(asSiteAdmin().getTripCountForOrg(orgId), 1);
    }

    /**
     * Issue 154: pictures are written only by TripPictureCommands, so a save from a copy taken before an upload
     * (the editor's working copy, a REST body) keeps the STORED list; the place fields are normalized.
     */
    @Test
    public void aSaveKeepsTheStoredPicturesAndNormalizesThePlace() throws java.io.IOException {
        final Trip trip = Trip.builder().title("Pictures " + java.util.UUID.randomUUID()).build();
        assertTrue(asSiteAdmin().saveTrip(trip));
        final Trip staleCopy = org.paulsens.trip.dynamo.DAO.getInstance()
                .getTrip(trip.getId(), org.paulsens.trip.cache.Cached.NO).orElseThrow();

        final Trip uploaded = org.paulsens.trip.dynamo.DAO.getInstance()
                .getTrip(trip.getId(), org.paulsens.trip.cache.Cached.NO).orElseThrow();
        uploaded.getPictures().add(new org.paulsens.trip.model.TripPicture("tripPictures/x/1", null, 960, 640,
                "#102030"));
        assertTrue(org.paulsens.trip.dynamo.DAO.getInstance().saveTrip(uploaded));

        staleCopy.setDestination("  Assisi, Italy  ");
        staleCopy.setCountry("it");
        staleCopy.setTags(List.of("Youth", "made-up", "retreat"));
        assertTrue(asSiteAdmin().saveTrip(staleCopy));
        final Trip reread = org.paulsens.trip.dynamo.DAO.getInstance()
                .getTrip(trip.getId(), org.paulsens.trip.cache.Cached.NO).orElseThrow();
        assertEquals(reread.getPictures().size(), 1, "The stale copy did not erase the uploaded picture");
        assertEquals(reread.getDestination(), "Assisi, Italy");
        assertEquals(reread.getCountry(), "IT");
        assertEquals(reread.getTags(), List.of("retreat", "youth"), "Offered tags only, in the list's order");
        assertTrue(tripCommands.getCountries().size() > 240);
        assertEquals(tripCommands.getTagChoices().size(), 9);

        staleCopy.setDestination("   ");
        staleCopy.setCountry("Narnia");
        TripCommands.normalizePlace(staleCopy, TripTags.current());
        assertNull(staleCopy.getDestination(), "Blank is none");
        assertNull(staleCopy.getCountry(), "Not a country is none");
    }

    private static TripCommands asSiteAdmin() {
        return new TripCommands(() -> new OrgCommands(() -> new Caller(Person.Id.from("admin"), true,
                org.paulsens.trip.audit.AuditActor.system(), new PrivilegeCommands())));
    }

    private static TripCommands asNobody() {
        return new TripCommands(() -> new OrgCommands(() -> new Caller(null, false,
                org.paulsens.trip.audit.AuditActor.system(), new PrivilegeCommands())));
    }

    /** The frozen-id anchors behind the itinerary table's per-request, cell-editable rows. */
    @Test
    public void frozenEventIdsResolveTheTripsCurrentCopiesInFrozenOrder() {
        final TripEvent one = new TripEvent();
        final TripEvent two = new TripEvent();
        final Trip trip = Trip.builder().id("frozen-ev").title("Frozen")
                .tripEvents(new java.util.ArrayList<>(List.of(one, two))).build();

        final List<String> frozen = tripCommands.eventIdsOf(List.of(two, one));    // reversed on purpose
        org.testng.Assert.assertEquals(frozen, List.of(two.getId(), one.getId()));

        final List<TripEvent> rows = tripCommands.eventsForFrozenIds(trip, frozen);
        org.testng.Assert.assertEquals(rows.get(0).getId(), two.getId(), "Frozen order wins");
        org.testng.Assert.assertEquals(rows.get(1).getId(), one.getId());

        frozen.add("vanished-event");
        org.testng.Assert.assertEquals(tripCommands.eventsForFrozenIds(trip, frozen).size(), 2,
                "A vanished id is skipped, never a null row");
        org.testng.Assert.assertEquals(tripCommands.eventsForFrozenIds(null, frozen), List.of());
        org.testng.Assert.assertEquals(tripCommands.eventsForFrozenIds(trip, null), List.of());
        org.testng.Assert.assertEquals(tripCommands.eventIdsOf(null), List.of());
    }

    @Test
    public void testLateArriver() {
        final Trip trip = trips.get(0);
        final TripEvent lodging = trip.getTripEvents().stream()
                .filter(te -> te.getType() == TripEvent.Type.LODGING)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No Lodging Event in this trip!"));

        final LocalDateTime p2Arrival = tripCommands.getLodgingArrivalDate(
                trip.getTripEventsForUser(people.get(2).getId()), lodging);
        final LocalDateTime p3Arrival = tripCommands.getLodgingArrivalDate(
                trip.getTripEventsForUser(people.get(3).getId()), lodging);

        assertTrue(p2Arrival.isAfter(lodging.getStart()));
        assertTrue(p3Arrival.isBefore(p2Arrival));
        assertTrue(p3Arrival.isAfter(lodging.getStart()),
                String.format("\np3Arrival = %s\n  lodging = %s", p3Arrival, lodging.getStart()));
        assertNotEquals(p2Arrival.getDayOfMonth(), p3Arrival.getDayOfMonth());
        assertTrue(Math.abs(Duration.between(p3Arrival, lodging.getStart()).toHours()) < 4L,
                String.format("p3 lands near check-in: p3Arrival=%s lodging=%s", p3Arrival, lodging.getStart()));
    }

    @Test
    public void testEarlyLeaver() {
        final Trip trip = trips.get(0);
        final TripEvent lodging = trip.getTripEvents().stream()
                .filter(te -> te.getType() == TripEvent.Type.LODGING)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No Lodging Event in this trip!"));
        final LocalDateTime p2Depart = tripCommands.getLodgingDepartureDate(
                trip.getTripEventsForUser(people.get(2).getId()), lodging);
        assertTrue(p2Depart.isBefore(lodging.getEnd()));
        final LocalDateTime p3Depart = tripCommands.getLodgingDepartureDate(
                trip.getTripEventsForUser(people.get(3).getId()), lodging);
        assertTrue(p3Depart.isAfter(p2Depart));
        assertEquals(p3Depart, lodging.getEnd());
        assertNotEquals(p2Depart.getDayOfMonth(), p3Depart.getDayOfMonth());
        assertEquals(p3Depart.getDayOfMonth(), lodging.getEnd().getDayOfMonth());
    }

    /**
     * The "Trip Listings" content instance: its admin-typed properties drive the listing, and a blank
     * or nonsense property must fall back to "everything" rather than error -- this renders on the PUBLIC
     * landing page, where a typo in an admin field must never take the page down.
     */
    @Test
    public void aListingInstanceAppliesItsPropertiesAndForgivesBadOnes() {
        assertEquals(tripCommands.getPublicTripsFor(null), List.of(), "No instance, nothing to list");

        final int all = tripCommands.getPublicTripsFor(listingInstance(null, null)).size();
        assertEquals(tripCommands.getPublicTripsFor(listingInstance("", null)).size(), all,
                "A blank max count lists everything");
        assertEquals(tripCommands.getPublicTripsFor(listingInstance("not-a-number", null)).size(), all,
                "An unparsable max count falls back to everything");
        assertEquals(tripCommands.getPublicTripsFor(listingInstance("0", null)).size(), all,
                "Zero means unset, not 'show nothing'");
        assertEquals(tripCommands.getPublicTripsFor(listingInstance("-3", null)).size(), all,
                "A negative max count is nonsense; fall back");
        if (all > 1) {
            assertEquals(tripCommands.getPublicTripsFor(listingInstance("1", null)).size(), 1,
                    "A real max count caps the list");
        }

        // cfpwOnly is a plain Boolean.parseBoolean: anything that is not "true" means "all providers".
        final List<Trip> cfpwOnly = tripCommands.getPublicTripsFor(listingInstance(null, "true"));
        assertTrue(cfpwOnly.stream().allMatch(Trip::isCfpw), "cfpwOnly restricts to CFPW-hosted trips");
        assertEquals(tripCommands.getPublicTripsFor(listingInstance(null, "banana")).size(), all,
                "A non-boolean reads as false: every provider");
    }

    /**
     * "Any" lists every language: exactly the English listing plus the Spanish one. A language-less section
     * still reads as English, so an existing section's meaning never changes.
     */
    @Test
    public void anAnyLanguageListingShowsEveryLanguage() {
        final List<Trip> english = tripCommands.getPublicTripsFor(languageInstance("English"));
        final List<Trip> spanish = tripCommands.getPublicTripsFor(languageInstance("Spanish"));
        final List<Trip> any = tripCommands.getPublicTripsFor(languageInstance(TripCommands.ANY_LANGUAGE));
        assertFalse(spanish.isEmpty(), "the seeds carry a public Spanish trip");
        assertEquals(new HashSet<>(any), union(english, spanish), "Any = every language's trips");
        assertEquals(any.size(), english.size() + spanish.size(), "and each trip once");
        assertEquals(tripCommands.getPublicTripsFor(languageInstance(" Any ")).size(), any.size(),
                "a stray space is forgiven, like every other listing property");
        assertEquals(tripCommands.getPublicTripsFor(languageInstance(null)), english,
                "no language still means English");
    }

    private static Set<Trip> union(final List<Trip> first, final List<Trip> second) {
        final Set<Trip> all = new HashSet<>(first);
        all.addAll(second);
        return all;
    }

    private ContentInstance languageInstance(final String language) {
        final Map<String, String> values = new HashMap<>();
        if (language != null) {
            values.put("language", language);
        }
        return new ContentInstance("ci-1", "page:trip-index", "Pilgrimages", "tpl-1", 1, values,
                null, 0, 1, null, null);
    }

    private ContentInstance listingInstance(final String maxCount, final String cfpwOnly) {
        final Map<String, String> values = new HashMap<>();
        if (maxCount != null) {
            values.put("maxCount", maxCount);
        }
        if (cfpwOnly != null) {
            values.put("cfpwOnly", cfpwOnly);
        }
        return new ContentInstance("ci-1", "page:trip-index", "Pilgrimages", "tpl-1", 1, values,
                null, 0, 1, null, null);
    }

    @Test
    public void testLodgingDays() {
        assertEquals(tripCommands.getLodgingDays(
                LocalDateTime.of(2025, 5, 23, 20, 0, 0),
                LocalDateTime.of(2025, 5, 24, 12, 0, 0)), 1);
        assertEquals(tripCommands.getLodgingDays(
                LocalDateTime.of(2025, 5, 24,  3, 0, 0),
                LocalDateTime.of(2025, 5, 24, 12, 0, 0)), 1);
        assertEquals(tripCommands.getLodgingDays(
                LocalDateTime.of(2025, 5, 23,  8, 0, 0),
                LocalDateTime.of(2025, 5, 23, 23, 50, 0)), 0);
        assertEquals(tripCommands.getLodgingDays(
                LocalDateTime.of(2025, 5, 23,  5, 0, 0),
                LocalDateTime.of(2025, 5, 24, 12, 0, 0)), 1);
        assertEquals(tripCommands.getLodgingDays(
                LocalDateTime.of(2025, 5, 23,  5, 0, 0),
                LocalDateTime.of(2025, 5, 26, 12, 0, 0)), 3);
        assertEquals(tripCommands.getLodgingDays(
                LocalDateTime.of(2025, 5, 23,  5, 0, 0),
                LocalDateTime.of(2025, 5, 27,  2, 0, 0)), 4);
    }

    // ------------------------------------------------------------------ bulk participation (lodging)

    @Test
    public void updateEventParticipantsJoinsAndLeavesOnTheTripsOwnEventAndSavesOnce() {
        final Trip trip = Trip.builder().id("participants-" + System.nanoTime()).title("Participants").build();
        trip.setOrgId(FakeData.CFPW_ORG_ID);
        final Person.Id ada = people.get(0).getId();
        final Person.Id bob = people.get(1).getId();
        trip.setPeople(new ArrayList<>(List.of(ada, bob)));
        final String lodgingId = trip.addTripEvent(TripEvent.Type.LODGING, "Pansion", "",
                LocalDateTime.of(2026, 9, 20, 15, 0), LocalDateTime.of(2026, 9, 30, 10, 0));
        assertTrue(tripCommands.saveTrip(trip));

        assertFalse(tripCommands.updateEventParticipants(trip, "no-such-event", List.of(ada), null),
                "An event that is not on the trip is refused");
        assertFalse(tripCommands.updateEventParticipants(null, lodgingId, List.of(ada), null));
        assertTrue(tripCommands.updateEventParticipants(trip, lodgingId, null, null), "Nothing to do");
        assertTrue(tripCommands.updateEventParticipants(trip, lodgingId, List.of(ada, bob), null));
        final Trip fresh = org.paulsens.trip.dynamo.DAO.getInstance()
                .getTrip(trip.getId(), org.paulsens.trip.cache.Cached.NO).orElseThrow();
        assertEquals(fresh.getTripEvent(lodgingId).getParticipants(), List.of(ada, bob),
                "Persisted on the trip's OWN event instance");
        assertTrue(tripCommands.updateEventParticipants(fresh, lodgingId, List.of(ada), List.of(bob)),
                "Joining someone already in and leaving someone out is one save");
        final Trip again = org.paulsens.trip.dynamo.DAO.getInstance()
                .getTrip(trip.getId(), org.paulsens.trip.cache.Cached.NO).orElseThrow();
        assertEquals(again.getTripEvent(lodgingId).getParticipants(), List.of(ada));
    }

    // ------------------------------------------------------------------ provider <- org sync

    @Test
    public void saveTripSyncsTheProviderStringFromTheOwningOrg() {
        final Trip trip = Trip.builder().id("org-sync-" + System.nanoTime()).title("Org sync").build();
        trip.setOrgId(FakeData.CFPW_ORG_ID);
        trip.setProvider("Stale Display String");
        assertTrue(tripCommands.saveTrip(trip));
        assertEquals(trip.getProvider(), "CFPW",
                "The provider display string derives from the org's name on every save");
        assertTrue(trip.isCfpw(), "isCfpw keys on the org, so the sync cannot break it");
    }

    @Test
    public void isCfpwSurvivesAFullNamedOrgAndAProviderResync() throws java.io.IOException {
        // The production shape: the CFPW org's NAME is the full "Center for Peace West"; only its
        // abbreviation is "CFPW". The provider sync writes the full name, which is exactly what broke
        // recognition when isCfpw compared the provider string (trip 4a9f058e, 2026-08).
        final String orgId = java.util.UUID.randomUUID().toString();
        assertTrue(org.paulsens.trip.dynamo.DAO.getInstance().saveOrganization(
                org.paulsens.trip.model.Organization.builder()
                        .id(org.paulsens.trip.model.Organization.Id.from(orgId))
                        .name("Center for Peace West")
                        .abbreviation("CFPW")
                        .createdBy(people.get(0).getId())
                        .created(LocalDateTime.now())
                        .build()));
        final Trip trip = Trip.builder().id("full-name-" + System.nanoTime()).title("Fall Medjugorje").build();
        trip.setOrgId(orgId);
        trip.setProvider("CFPW");
        assertTrue(tripCommands.saveTrip(trip));
        assertEquals(trip.getProvider(), "Center for Peace West", "The sync writes the org's full name...");
        assertTrue(trip.isCfpw(), "...and the trip must STILL be recognized as CFPW (org short name)");

        final String partnerId = java.util.UUID.randomUUID().toString();
        assertTrue(org.paulsens.trip.dynamo.DAO.getInstance().saveOrganization(
                org.paulsens.trip.model.Organization.builder()
                        .id(org.paulsens.trip.model.Organization.Id.from(partnerId))
                        .name("Schaefer Travel")
                        .createdBy(people.get(0).getId())
                        .created(LocalDateTime.now())
                        .build()));
        final Trip partner = Trip.builder().id("partner-" + System.nanoTime()).title("Partner trip").build();
        partner.setOrgId(partnerId);
        assertTrue(tripCommands.saveTrip(partner));
        assertFalse(partner.isCfpw(), "A partner org's trip never reads as CFPW");
    }

    @Test
    public void saveTripLeavesTheProviderAloneWithoutAnOrg() {
        final Trip trip = Trip.builder().id("org-sync2-" + System.nanoTime()).title("Legacy").build();
        trip.setProvider("Hand Entered Provider");
        assertTrue(tripCommands.saveTrip(trip));
        assertEquals(trip.getProvider(), "Hand Entered Provider", "Legacy trips keep their string");

        final Trip unknownOrg = Trip.builder().id("org-sync3-" + System.nanoTime()).title("Ghost").build();
        unknownOrg.setOrgId("no-such-org");
        unknownOrg.setProvider("Kept");
        assertTrue(tripCommands.saveTrip(unknownOrg));
        assertEquals(unknownOrg.getProvider(), "Kept", "An unknown org warns and leaves provider as-is");
    }

    @Test
    public void theOrganizationPropertyResolvesAndAssigns() {
        final Trip trip = Trip.builder().id("org-prop-" + System.nanoTime()).build();
        assertNull(trip.getOrganization(), "No org id, no organization");
        trip.setOrgId(FakeData.CFPW_ORG_ID);
        assertEquals(trip.getOrganization().getName(), "CFPW");
        trip.setOrganization(null);
        assertNull(trip.getOrgId(), "Clearing the picker clears the id");
    }

    /**
     * A typed link that is no web address is refused at the save, so the editor and REST agree (medjugorje
     * de420d055f12 F1).
     */
    @Test
    public void aSaveRefusesAnExternalOrFlyerUrlThatIsNoWebAddress() {
        final Trip trip = Trip.builder().title("Links " + java.util.UUID.randomUUID()).build();
        trip.setNonHostedTripUrl("javascript:alert(1)");
        assertFalse(asSiteAdmin().saveTrip(trip), "A javascript: external URL is never stored");
        trip.setNonHostedTripUrl("https://example.org/register");
        trip.setFlyerUrl("javascript:alert(2)");
        assertFalse(asSiteAdmin().saveTrip(trip), "Nor a javascript: flyer");
        assertTrue(org.paulsens.trip.dynamo.DAO.getInstance()
                .getTrip(trip.getId(), org.paulsens.trip.cache.Cached.NO).isEmpty(), "Nothing was written");
        trip.setFlyerUrl("https://example.org/flyer.pdf");
        assertTrue(asSiteAdmin().saveTrip(trip));
    }

    /**
     * Only a CHANGED link is refused. Both fields were free text before the rule, and every save of a trip passes
     * the gate (a paid checkout's roster write, an approval, an itinerary note): one stored under the older rule
     * must keep accepting those, or a payer ends up CONFIRMED and off the roster.
     */
    @Test
    public void aTripStoredWithALegacyLinkStillSavesEverythingElse() throws java.io.IOException {
        final Trip legacy = Trip.builder().title("Legacy links " + java.util.UUID.randomUUID()).build();
        legacy.setFlyerUrl("www.example.org/2026 Holy Angels.pdf");
        legacy.setNonHostedTripUrl("example.org/register");
        assertTrue(org.paulsens.trip.dynamo.DAO.getInstance().saveTrip(legacy), "Stored as the old editor allowed");

        final Trip copy = org.paulsens.trip.dynamo.DAO.getInstance()
                .getTrip(legacy.getId(), org.paulsens.trip.cache.Cached.NO).orElseThrow();
        copy.getPeople().add(people.get(0).getId());
        assertTrue(asSiteAdmin().saveTrip(copy), "A roster change does not touch the links");
        assertEquals(org.paulsens.trip.dynamo.DAO.getInstance()
                .getTrip(legacy.getId(), org.paulsens.trip.cache.Cached.NO).orElseThrow().getPeople().size(), 1);

        copy.setFlyerUrl("www.example.org/another.pdf");
        assertFalse(asSiteAdmin().saveTrip(copy), "A changed link is judged by the rule");
        copy.setFlyerUrl("https://example.org/another.pdf");
        assertTrue(asSiteAdmin().saveTrip(copy), "And a good one replaces the legacy value");
    }

    /**
     * The pre-read is advisory: when it FAILS, the stored links cannot be told from the ones being saved, so none
     * is judged and the save goes on. Judging them as new would refuse a paid checkout's roster write on a trip
     * with a legacy link whenever the read hiccups.
     */
    @Test
    public void aFailedPreReadJudgesNoLinkAndTheSaveGoesOn() throws java.io.IOException {
        final Trip legacy = Trip.builder().title("Unreadable " + java.util.UUID.randomUUID()).build();
        legacy.setFlyerUrl("www.example.org/legacy.pdf");
        assertTrue(org.paulsens.trip.dynamo.DAO.getInstance().saveTrip(legacy));
        final TripCommands blind = new TripCommands(() -> new OrgCommands(() -> new Caller(Person.Id.from("admin"),
                true, org.paulsens.trip.audit.AuditActor.system(), new PrivilegeCommands()))) {
            @Override
            PreRead preRead(final String tripId) {
                return new PreRead(null, false);
            }
        };
        legacy.getPeople().add(people.get(0).getId());
        assertTrue(blind.saveTrip(legacy), "An unreadable store judges no link");
        assertNull(asSiteAdmin().preRead("no-such-trip-" + System.nanoTime()).stored(), "Absent is not unreadable");
        assertTrue(asSiteAdmin().preRead("no-such-trip-" + System.nanoTime()).readable());
        assertTrue(asSiteAdmin().preRead(null).readable(), "No id yet: a new trip, all links judged");
    }
}
