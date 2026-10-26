package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.paulsens.trip.action.LodgingViews.AccommodationForm;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Accommodation;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * The hotel row's record of who uses it, driven the way the offer save and the deletes drive it: no write when
 * the hotel already knows the trip and its organization, the trip alone, the organization alone (whose first
 * use also puts the hotel's contact on its roster), a hotel that is gone, a trip forgotten only once no option
 * of the trip names the hotel, the trip-delete sweep, and a lost race on the row logged rather than thrown.
 */
public class HotelUsageTest {
    private static final LocalDateTime CHECK_IN = LocalDateTime.of(2027, 9, 21, 15, 0);
    private static final LocalDateTime CHECK_OUT = LocalDateTime.of(2027, 9, 26, 10, 0);
    private static final AtomicInteger SEQ = new AtomicInteger();

    private final LodgingCommands admin = new LodgingCommands(TestCallers::siteAdmin);
    private final OrgCommands orgs = new OrgCommands(TestCallers::siteAdmin);
    private String orgId;
    private String otherOrgId;

    @BeforeClass
    public void seed() throws IOException {
        FakeData.initFakeData();
        FakeData.addFakeData();
        orgId = org("Usage", "USG");
        otherOrgId = org("Guest", "GST");
    }

    // ------------------------------------------------------------------ record

    /** The common case after the first save: the hotel knows the trip and its organization, so nothing is written. */
    @Test
    public void recordWritesNothingWhenTheHotelAlreadyKnowsTheTripAndItsOrganization() throws IOException {
        final Accommodation acc = hotel(false);
        final Trip trip = trip(orgId);
        HotelUsage.record(acc, trip, orgs);
        final Accommodation once = fresh(acc);
        assertEquals(once.getTripIds(), List.of(trip.getId()));
        assertEquals(once.getOrgIds(), List.of(Organization.Id.from(orgId)), "the org was known already");
        assertEquals(once.getVersion(), acc.getVersion() + 1, "one write records the trip");

        HotelUsage.record(once, trip, orgs);
        assertEquals(fresh(acc).getVersion(), once.getVersion(), "known trip, known org: no write");
        // A stale copy that has not heard of the trip asks the store, which has: still no write.
        HotelUsage.record(acc, trip, orgs);
        assertEquals(fresh(acc).getVersion(), once.getVersion());
    }

    /** A trip with no organization (or a blank one) is recorded on its own; the org list does not move. */
    @Test
    public void aTripWithoutAnOrganizationIsRecordedOnItsOwn() throws IOException {
        final Accommodation acc = hotel(false);
        final Trip homeless = trip(null);
        final Trip blank = trip("");
        HotelUsage.record(acc, homeless, orgs);
        HotelUsage.record(fresh(acc), blank, orgs);
        final Accommodation after = fresh(acc);
        assertEquals(after.getTripIds(), List.of(homeless.getId(), blank.getId()));
        assertEquals(after.getOrgIds(), List.of(Organization.Id.from(orgId)), "no organization to add");
    }

    /**
     * A trip of a second organization whose id the hotel already carries (an org-only change): the org joins the
     * hotel's list and the hotel's contact joins the org, so the hotel is mailable from there. A hotel without a
     * contact has nobody to add; a second trip of the same org is a trip-only change with no roster write.
     */
    @Test
    public void theFirstUseByAnOrganizationAlsoPutsTheHotelsContactOnItsRoster() throws IOException {
        final Accommodation acc = hotel(true);
        assertNotNull(acc.getContactId(), "the fixture names a contact");
        assertFalse(orgs.isMember(otherOrgId, acc.getContactId()));
        final Trip trip = trip(otherOrgId);
        assertTrue(acc.recordTripUse(trip.getId()), "as if recorded before the org bookkeeping existed");
        assertTrue(DAO.getInstance().saveAccommodation(acc));

        HotelUsage.record(acc, trip, orgs);
        final Accommodation after = fresh(acc);
        assertTrue(after.usedBy(Organization.Id.from(otherOrgId)));
        assertEquals(after.getTripIds(), List.of(trip.getId()), "recorded once");
        assertTrue(orgs.isMember(otherOrgId, acc.getContactId()), "the hotel is mailable from the new org");

        final Trip second = trip(otherOrgId);
        HotelUsage.record(after, second, orgs);
        assertEquals(fresh(acc).getTripIds(), List.of(trip.getId(), second.getId()));
        assertEquals(fresh(acc).getOrgIds().size(), 2, "the org is recorded once");

        final Accommodation contactless = hotel(false);
        HotelUsage.record(contactless, trip, orgs);
        assertTrue(fresh(contactless).usedBy(Organization.Id.from(otherOrgId)));
        assertTrue(fresh(contactless).usedByTrip(trip.getId()));
    }

    /** A hotel that is gone from the store is left alone, on the record and on the forget. */
    @Test
    public void aHotelThatIsGoneIsLeftAlone() throws IOException {
        final Accommodation ghost = Accommodation.builder().name("Ghost hotel").build();
        final Trip trip = trip(orgId);
        HotelUsage.record(ghost, trip, orgs);
        assertTrue(DAO.getInstance().getAccommodation(ghost.getId(), Cached.NO).isEmpty(), "never created");
        HotelUsage.forgetTrip(ghost.getId(), trip.getId(), List.of());
        HotelUsage.forgetTrip(null, trip.getId(), List.of());
        assertTrue(DAO.getInstance().getAccommodation(ghost.getId(), Cached.NO).isEmpty());
    }

    /**
     * The hotel row another writer moved between the read and the put: the loss is logged and swallowed -- the
     * offer save that triggered it must not fail -- nothing is recorded, and the next save records it all.
     */
    @Test
    public void aLostRaceOnTheHotelRowIsLoggedNotThrown() throws IOException {
        final Accommodation acc = hotel(true);
        final Trip trip = trip(otherOrgId);
        final String key = CacheKeys.LODGING_ACC_PREFIX + CacheKeys.LODGING_ACC_PARTITION;
        final String field = acc.getId().getValue();
        final CacheClient cache = DAO.getInstance().getCacheClient();
        final String json = cache.getHashFields(key, List.of(field)).get(field);
        assertNotNull(json, "the hotel is cached");
        // The shared cache's copy claims a version the table never reached: the versioned put must lose.
        final String aged = json.replace("\"version\":" + acc.getVersion(), "\"version\":" + (acc.getVersion() + 1));
        assertNotEquals(aged, json, "the cached copy carries the version");
        assertTrue(cache.putHashField(key, field, aged));

        HotelUsage.record(acc, trip, orgs);
        assertFalse(orgs.isMember(otherOrgId, acc.getContactId()), "the roster write waits for the hotel write");

        assertTrue(cache.putHashField(key, field, json));
        final Accommodation after = fresh(acc);
        assertEquals(after.getVersion(), acc.getVersion(), "nothing was written");
        assertFalse(after.usedByTrip(trip.getId()));
        assertFalse(after.usedBy(Organization.Id.from(otherOrgId)));
        HotelUsage.record(after, trip, orgs);
        assertTrue(fresh(acc).usedByTrip(trip.getId()), "the next offer save records it");
        assertTrue(fresh(acc).usedBy(Organization.Id.from(otherOrgId)));
        assertTrue(orgs.isMember(otherOrgId, acc.getContactId()));
    }

    // ------------------------------------------------------------------ forget

    /** The trip stays on the hotel while any OTHER option of the trip names it; the last one takes it off. */
    @Test
    public void forgetTripWaitsForTheTripsLastOptionAtTheHotel() throws IOException {
        final Accommodation acc = hotel(false);
        final Accommodation elsewhere = hotel(false);
        final Trip trip = trip(orgId);
        HotelUsage.record(acc, trip, orgs);
        final long recorded = fresh(acc).getVersion();
        final ReservationOffer here = offer(trip, acc.getId());
        final ReservationOffer away = offer(trip, elsewhere.getId());

        HotelUsage.forgetTrip(acc.getId(), trip.getId(), List.of(away, here));
        assertTrue(fresh(acc).usedByTrip(trip.getId()), "another option of the trip still names the hotel");
        assertEquals(fresh(acc).getVersion(), recorded, "no write");
        HotelUsage.forgetTrip(acc.getId(), trip.getId(), List.of(away));
        assertFalse(fresh(acc).usedByTrip(trip.getId()));
        assertEquals(fresh(acc).getVersion(), recorded + 1);
        HotelUsage.forgetTrip(acc.getId(), trip.getId(), List.of());
        assertEquals(fresh(acc).getVersion(), recorded + 1, "forgetting a trip the hotel does not know writes nothing");
        assertFalse(fresh(elsewhere).usedByTrip(trip.getId()), "the other hotel never heard of it");
    }

    /** The trip-delete sweep: every hotel the deleted options named forgets the trip, once, and no other does. */
    @Test
    public void theTripDeleteSweepForgetsTheTripAtEveryHotelItsOptionsNamed() throws IOException {
        final Accommodation first = hotel(false);
        final Accommodation second = hotel(false);
        final Accommodation untouched = hotel(false);
        final Trip trip = trip(orgId);
        HotelUsage.record(first, trip, orgs);
        HotelUsage.record(second, trip, orgs);
        HotelUsage.record(untouched, trip, orgs);
        final long secondBefore = fresh(second).getVersion();
        final ReservationOffer unplaced = ReservationOffer.builder().tripId(trip.getId()).name("No hotel yet").build();

        HotelUsage.forgetTripEverywhere(trip.getId(), List.of(offer(trip, first.getId()), unplaced,
                offer(trip, second.getId()), offer(trip, first.getId())));
        assertFalse(fresh(first).usedByTrip(trip.getId()));
        assertFalse(fresh(second).usedByTrip(trip.getId()));
        assertEquals(fresh(second).getVersion(), secondBefore + 1, "one write per hotel");
        assertTrue(fresh(untouched).usedByTrip(trip.getId()), "only the hotels the deleted options named");
    }

    // ------------------------------------------------------------------ fixtures

    private String org(final String name, final String abbreviation) {
        final Organization org = orgs.createOrganization(name + " " + RandomData.genAlpha(8), abbreviation, null);
        assertNotNull(org);
        return org.getId().getValue();
    }

    /** A fresh hotel used by {@code orgId}, with or without a contact (who is created into that org). */
    private Accommodation hotel(final boolean withContact) {
        final AccommodationForm form = new AccommodationForm();
        final String tag = RandomData.genAlpha(8) + SEQ.incrementAndGet();
        form.setName("Usage hotel " + tag);
        form.setEmail(tag.toLowerCase(Locale.ROOT) + "@usage.example");
        // Discovery compares phones by their DIGITS: the number carries the clock so no two fixtures collide.
        form.setPhone("+1 458 " + SEQ.incrementAndGet() + " " + (System.nanoTime() % 1_000_000L));
        form.setStreet("Use " + SEQ.incrementAndGet() + " " + tag + " Ave");
        form.setCity("Eugene");
        if (withContact) {
            form.setContactEmail("desk." + tag.toLowerCase(Locale.ROOT) + "@usage.example");
            form.setContactFirst("Desk");
            form.setContactLast(tag);
        }
        final String accId = admin.saveAccommodation(form, orgId);
        assertTrue(accId != null && !accId.isBlank(), "the hotel fixture was refused as a duplicate");
        final Accommodation acc = admin.findAccommodation(accId);
        assertNotNull(acc);
        return acc;
    }

    private static Accommodation fresh(final Accommodation acc) {
        return DAO.getInstance().getAccommodation(acc.getId(), Cached.NO).orElseThrow();
    }

    /** A saved trip of the organization; {@code null} or blank for a trip that belongs to none. */
    private static Trip trip(final String tripOrgId) throws IOException {
        final Trip trip = Trip.builder().id(UUID.randomUUID().toString()).title("Usage trip " + SEQ.incrementAndGet())
                .startDate(CHECK_IN).endDate(CHECK_OUT).build();
        trip.setOrgId(tripOrgId);
        assertTrue(DAO.getInstance().saveTrip(trip));
        return trip;
    }

    private static ReservationOffer offer(final Trip trip, final Accommodation.Id accId) {
        return ReservationOffer.builder().tripId(trip.getId()).name("Option").accommodationId(accId).build();
    }
}
