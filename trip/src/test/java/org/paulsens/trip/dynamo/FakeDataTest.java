package org.paulsens.trip.dynamo;

import java.util.List;
import java.util.Map;
import org.paulsens.trip.model.Family;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.RegistrationOption;
import org.paulsens.trip.model.Trip;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.paulsens.trip.action.ChatCommands;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.Reservation;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.action.LodgingCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.model.chat.ChatChannel;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class FakeDataTest {

    @BeforeClass
    public void init() {
        FakeData.initFakeData();
    }

    @Test
    public void initFakeDataCreatesPeople() {
        final List<Person> people = FakeData.getFakePeople();
        assertNotNull(people);
        assertFalse(people.isEmpty());
    }

    /** The family switcher needs a member with something to show: Lucy travels on the Spring Demo trip. */
    @Test
    public void lucyTravelsOnTheSpringDemoTrip() {
        // Seed first: initFakeData only builds the lists, and another suite may have cleared the store.
        FakeData.addFakeData();
        final Person ken = DAO.getInstance().getPersonByEmail("user2@example.com", Cached.NO);
        assertNotNull(ken, "the seed's family manager");
        final Family family = DAO.getInstance().getFamily(ken.getFamilyId(), Cached.NO).orElseThrow();
        final Person lucy = family.getMemberIds().stream()
                .map(id -> DAO.getInstance().getPerson(id, Cached.NO).orElse(null))
                .filter(p -> p != null && "Lucy".equals(p.getFirst()))
                .findFirst().orElseThrow();
        final Trip trip = DAO.getInstance().getTrip(FakeData.FAKE_TRIP_ID, Cached.NO).orElseThrow();

        assertTrue(trip.getPeople().contains(lucy.getId()), "Lucy holds a roster seat");
        assertTrue(trip.getTripEvent(FakeData.FAKE_TRIP_LODGING_EVENT_ID).getParticipants().contains(lucy.getId()),
                "...and stays at the hotel");
        assertTrue(lucy.getOrgIds().contains(Organization.Id.from(FakeData.CFPW_ORG_ID)),
                "...and is tenanted like every other fake person, so API roster writes keep her");
    }

    @Test
    public void initFakeDataCreatesTrips() {
        final List<Trip> trips = FakeData.getFakeTrips();
        assertNotNull(trips);
        assertFalse(trips.isEmpty());
    }

    @Test
    public void fakePeopleHaveIds() {
        for (final Person person : FakeData.getFakePeople()) {
            assertNotNull(person.getId(), "All fake people should have IDs");
        }
    }

    @Test
    public void fakePeopleHaveNames() {
        for (final Person person : FakeData.getFakePeople()) {
            assertNotNull(person.getFirst(), "All fake people should have first names");
            assertNotNull(person.getLast(), "All fake people should have last names");
        }
    }

    @Test
    public void fakeTripsHaveIds() {
        for (final Trip trip : FakeData.getFakeTrips()) {
            assertNotNull(trip.getId(), "All fake trips should have IDs");
        }
    }

    @Test
    public void fakeTripsHaveTripEvents() {
        for (final Trip trip : FakeData.getFakeTrips()) {
            assertFalse(trip.getTripEvents().isEmpty(),
                    "Trip '" + trip.getTitle() + "' should have trip events");
        }
    }

    @Test
    public void createFakePersistenceReturnsNonNull() {
        assertNotNull(FakeData.createFakePersistence());
    }

    @Test
    public void fakePersistencePutItemReturnsSuccess() {
        final Persistence p = FakeData.createFakePersistence();
        final PutItemResponse resp = p.putItem(b -> b.tableName("test"));
        assertTrue(resp.sdkHttpResponse().isSuccessful());
    }

    @Test
    public void fakePersistenceScanReturnsEmpty() {
        final Persistence p = FakeData.createFakePersistence();
        final ScanResponse resp = p.scan(b -> b.tableName("test"));
        assertTrue(resp.items().isEmpty());
    }

    @Test
    public void fakePersistenceQueryReturnsEmpty() {
        final Persistence p = FakeData.createFakePersistence();
        final QueryResponse resp = p.query(b -> b.tableName("test"));
        assertTrue(resp.items().isEmpty());
    }

    @Test
    public void createFakePersistenceWithQueryMonitorCallsMonitor() {
        final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger(0);
        final Persistence p = FakeData.createFakePersistenceWithQueryMonitor(q -> count.incrementAndGet());
        assertEquals(count.get(), 0);
        p.query(b -> b.tableName("test"));
        assertEquals(count.get(), 1);
        p.query(b -> b.tableName("test2"));
        assertEquals(count.get(), 2);
    }

    @Test
    public void queryMonitorPersistenceStillReturnsEmptyResults() {
        final Persistence p = FakeData.createFakePersistenceWithQueryMonitor(q -> {});
        final QueryResponse resp = p.query(b -> b.tableName("test"));
        assertTrue(resp.items().isEmpty());
    }

    @Test
    public void getDefaultOptionsReturnsNonEmpty() {
        final List<RegistrationOption> options = FakeData.getDefaultOptions();
        assertNotNull(options);
        assertFalse(options.isEmpty());
    }

    @Test
    public void getDefaultOptionsHaveDescriptions() {
        for (final RegistrationOption opt : FakeData.getDefaultOptions()) {
            assertNotNull(opt.getShortDesc(), "Option should have a short description");
            assertNotNull(opt.getLongDesc(), "Option should have a long description");
        }
    }

    @Test
    public void getTestUserCredsForAdminReturnsAdminPriv() {
        final GetItemRequest req = GetItemRequest.builder()
                .tableName(CredentialsDAO.PASS_TABLE)
                .key(Map.of(CredentialsDAO.EMAIL, AttributeValue.builder().s("admin123").build()))
                .build();
        final Map<String, AttributeValue> creds = FakeData.getTestUserCreds(req);
        assertNotNull(creds);
        assertEquals(creds.get(CredentialsDAO.PRIV).s(), "admin");
    }

    @Test
    public void getTestUserCredsForUserReturnsUserPriv() {
        final GetItemRequest req = GetItemRequest.builder()
                .tableName(CredentialsDAO.PASS_TABLE)
                .key(Map.of(CredentialsDAO.EMAIL, AttributeValue.builder().s("userABC").build()))
                .build();
        final Map<String, AttributeValue> creds = FakeData.getTestUserCreds(req);
        assertNotNull(creds);
        assertEquals(creds.get(CredentialsDAO.PRIV).s(), "user");
    }

    @Test
    public void getTestUserCredsForUnknownReturnsNull() {
        final GetItemRequest req = GetItemRequest.builder()
                .tableName(CredentialsDAO.PASS_TABLE)
                .key(Map.of(CredentialsDAO.EMAIL, AttributeValue.builder().s("unknown@test.com").build()))
                .build();
        assertNull(FakeData.getTestUserCreds(req));
    }

    @Test
    public void getTestUserCredsEmailIsCaseInsensitive() {
        final GetItemRequest req = GetItemRequest.builder()
                .tableName(CredentialsDAO.PASS_TABLE)
                .key(Map.of(CredentialsDAO.EMAIL, AttributeValue.builder().s("ADMIN_user").build()))
                .build();
        final Map<String, AttributeValue> creds = FakeData.getTestUserCreds(req);
        assertNotNull(creds);
        assertEquals(creds.get(CredentialsDAO.EMAIL).s(), "admin_user");
    }

    @Test
    public void getTestUserCredsIncludesLastLogin() {
        final GetItemRequest req = GetItemRequest.builder()
                .tableName(CredentialsDAO.PASS_TABLE)
                .key(Map.of(CredentialsDAO.EMAIL, AttributeValue.builder().s("admin").build()))
                .build();
        final Map<String, AttributeValue> creds = FakeData.getTestUserCreds(req);
        assertNotNull(creds);
        assertNotNull(creds.get(CredentialsDAO.LAST_LOGIN));
    }

    /**
     * The seeded demo channels must satisfy the app's OWN settings validator. They are deliberately at the
     * rate-limit ceiling so the suite can never trip the limiter, and the admin chat-settings page re-saves
     * whatever it loads -- so a seed one notch past the ceiling makes every Save on that page fail validation
     * and silently go nowhere, which is exactly how it broke a webtest.
     */
    @Test
    public void seededChatChannelsPassTheSettingsValidator() {
        FakeData.addFakeData();
        final ChatCommands chat = new ChatCommands();
        for (final String tripId : new String[] {FakeData.FAKE_TRIP_ID, FakeData.FAKE2_TRIP_ID}) {
            final ChatChannel channel = chat.ensureChannel(tripId, AuditActor.system());
            assertNotNull(channel, tripId + " should have a chat channel");
            assertNull(ChatCommands.validateSettings(channel.getSettings()),
                    tripId + "'s seeded settings must be savable from the admin page");
            assertTrue(channel.getSettings().getBurstLimit() >= 1000,
                    "the seed exists to keep the suite clear of the limiter: "
                            + channel.getSettings().getBurstLimit());
        }
    }

    /**
     * The demo hotel stay reads the way a real one does: the option that tracks the LODGING event agrees
     * with it to the minute, so only the late arriver's itinerary row is marked as coming from her
     * reservation. The event used to take whatever time of day the seed ran at, so it never matched the
     * option's 3pm check-in and EVERY occupant's row claimed an override -- the hint exists to spot exactly
     * one of them. (How the rows then render is {@code ItineraryLodgingPwIT}'s, on fixtures it owns.)
     */
    @Test
    public void theSeededHotelStayAndItsLodgingOptionAgree() {
        final Trip trip = DAO.getInstance().getTrip(FakeData.FAKE_TRIP_ID, Cached.NO).orElseThrow();
        final org.paulsens.trip.model.TripEvent hotel = trip.getTripEvent(FakeData.FAKE_TRIP_LODGING_EVENT_ID);
        assertNotNull(hotel, "the demo trip has its lodging event");
        final LodgingCommands lodging = new LodgingCommands(FakeDataTest::siteAdmin);
        final ReservationOffer offer = lodging.getOffers(FakeData.FAKE_TRIP_ID).stream()
                .filter(o -> FakeData.FAKE_TRIP_LODGING_EVENT_ID.equals(o.getTripEventId())).findFirst()
                .orElseThrow();
        assertEquals(offer.getDefaultStart(), hotel.getStart(), "the option's stay is the event's, to the minute");
        assertEquals(offer.getDefaultEnd(), hotel.getEnd());

        int withTheGroup = 0;
        int late = 0;
        for (final Reservation res : DAO.getInstance().getReservations(FakeData.FAKE_TRIP_ID, Cached.NO)) {
            if (!res.isActive() || !offer.getId().equals(res.getOfferId())) {
                continue;
            }
            assertFalse(res.getStart().isBefore(hotel.getStart()), "nobody checks in before the stay opens");
            if (res.getStart().equals(hotel.getStart())) {
                withTheGroup++;
            } else {
                late++;
            }
        }
        // Counts, not exact numbers: the suite shares one local store and re-seeds after a cache clear,
        // which mints fresh people and so fresh reservations for the same two personas.
        assertTrue(withTheGroup > 0, "somebody arrives with the group, and their row must not claim an override");
        assertTrue(late > 0, "and somebody arrives late: the case the whole feature exists for");
    }

    /**
     * The Pansion carries one rate plan under a fixed id (the webtests' {@code SeededIds} mirrors it): the
     * hotel's cost over calendar 2027, and deliberately not linked to the seeded offer, whose own $60 x 12
     * nights the browser tests assert. The seeded offer states a COST equal to its price, so the demo trip's
     * Room Invoice renders. The Pansion also records the demo trip as a user of the hotel, which is what puts
     * a trip on the operator's Trips tab before anyone is booked.
     */
    @Test
    public void theSeededPansionHasADatedCostOnlyRatePlanAndKnowsTheDemoTrip() {
        FakeData.addFakeData();
        final org.paulsens.trip.model.RatePlan plan = DAO.getInstance().getRatePlan(FakeData.CFPW_ACCOMMODATION_ID,
                org.paulsens.trip.model.RatePlan.Id.from(FakeData.CFPW_RATE_PLAN_ID), Cached.NO).orElseThrow();
        assertEquals(plan.getName(), "Hotel rate 2027");
        assertEquals(plan.getCost().getNightlyCents(), 5000L);
        assertEquals(plan.getCostExtras().size(), 1);
        assertEquals(plan.getCostExtras().get(0).getLabel(), "City tax");
        assertEquals(plan.getCostExtras().get(0).getAmountCents(), 1200L);
        assertEquals(plan.getValidFrom(), java.time.LocalDate.of(2027, 1, 1), "dated, so a trip can fall outside");
        assertEquals(plan.getValidUntil(), java.time.LocalDate.of(2027, 12, 31));
        assertTrue(plan.getLinkedTripIds().isEmpty(), "NOT linked: the seeded offer keeps its own $60");
        final org.paulsens.trip.model.Accommodation pansion = DAO.getInstance().getAccommodation(
                org.paulsens.trip.model.Accommodation.Id.from(FakeData.CFPW_ACCOMMODATION_ID), Cached.NO).orElseThrow();
        assertEquals(plan.getRoomTypeIds().size(), pansion.getRoomTypes().size(), "every room type");
        assertTrue(pansion.usedByTrip(FakeData.FAKE_TRIP_ID), "the offer save recorded the demo trip on the hotel");
        final LodgingCommands lodging = new LodgingCommands(FakeDataTest::siteAdmin);
        for (final ReservationOffer offer : lodging.getOffers(FakeData.FAKE_TRIP_ID)) {
            assertFalse(offer.isLinked(), "no seeded offer follows the plan");
            assertEquals(offer.getCost().getNightlyCents(), 6000L, "the seeded cost equals the $60 price");
            assertTrue(offer.getCostExtras().isEmpty(), "no extras: the invoice total stays $720");
        }
    }

    private static org.paulsens.trip.action.Caller siteAdmin() {
        return new org.paulsens.trip.action.Caller(Person.Id.from(FakeData.ADMIN_PERSON_ID), true,
                new AuditActor("admin@example.com", FakeData.ADMIN_PERSON_ID),
                new org.paulsens.trip.action.PrivilegeCommands());
    }
}
