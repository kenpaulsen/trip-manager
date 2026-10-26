package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.paulsens.trip.action.LodgingViews.AccommodationForm;
import org.paulsens.trip.action.LodgingViews.OfferForm;
import org.paulsens.trip.action.LodgingViews.RoomTypeForm;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Accommodation;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Reservation;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * The per-trip access object: which hotels are IN PLAY on a trip (named by an option, or by an ACTIVE
 * reservation), which of them the caller operates, and what that adds up to. No FacesContext here, so every
 * {@code forTrip} is a bare compute -- which is also what the memo test relies on.
 */
public class LodgingAccessCommandsTest {
    private static final LocalDateTime CHECK_IN = LocalDateTime.of(2027, 11, 2, 15, 0);
    private static final LocalDateTime CHECK_OUT = LocalDateTime.of(2027, 11, 5, 10, 0);

    private final LodgingCommands admin = new LodgingCommands(TestCallers::siteAdmin);
    private String orgId;

    @BeforeClass
    public void seed() throws IOException {
        FakeData.initFakeData();
        FakeData.addFakeData();
        final Organization org = new OrgCommands(TestCallers::siteAdmin)
                .createOrganization("Access " + RandomData.genAlpha(8), "ACC", null);
        assertNotNull(org);
        orgId = org.getId().getValue();
    }

    @Test
    public void aHotelIsInPlayByAnOptionOrByAnActiveReservationAndLeavesPlayWhenItIsCancelled()
            throws IOException {
        final Person guest = savedPerson("Ava");
        final Trip trip = tripWith("Access trip", guest);
        final String byOffer = hotel();
        final String byStayOnly = hotel();
        final String idle = hotel();
        offerAt(trip, byOffer);
        // A stay with no option at its hotel on this trip: a row written straight to the store, the way a
        // reservation outlives a deleted option or arrives from another path.
        final Reservation stay = Reservation.builder().tripId(trip.getId()).orgId(orgId)
                .accommodationId(Accommodation.Id.from(byStayOnly)).occupants(List.of(guest.getId()))
                .start(CHECK_IN).end(CHECK_OUT).created(LocalDateTime.now()).build();
        assertTrue(DAO.getInstance().saveReservation(stay));

        final Person keeperA = operatorOf(byOffer);
        final Person keeperB = operatorOf(byStayOnly);
        final Person keeperC = operatorOf(idle);
        final LodgingAccessCommands asA = new LodgingAccessCommands(() -> TestCallers.person(keeperA.getId()));
        final LodgingAccessCommands asB = new LodgingAccessCommands(() -> TestCallers.person(keeperB.getId()));
        final LodgingAccessCommands asC = new LodgingAccessCommands(() -> TestCallers.person(keeperC.getId()));

        final LodgingAccess a = asA.forTrip(trip.getId());
        assertEquals(a.getTripId(), trip.getId());
        assertFalse(a.isInternal());
        assertTrue(a.isCanAssign());
        assertTrue(a.isOperatorHere());
        assertFalse(a.isSeesPrices(), "an operator never sees a price");
        assertEquals(a.getOperatorAccIds(), Set.of(byOffer), "in play by the option");
        assertEquals(a.getAllowedAccIds(), Set.of(byOffer), "an operator's reach is their own hotel");
        assertTrue(a.mayOpenBoard(byOffer));
        assertFalse(a.mayOpenBoard(byStayOnly), "in play, but not theirs");
        assertTrue(a.operatorOf(byOffer));
        assertFalse(a.operatorOf(null));
        assertTrue(asA.operatorOfAnyHotelOn(trip.getId()));

        assertEquals(asB.forTrip(trip.getId()).getOperatorAccIds(), Set.of(byStayOnly),
                "in play by the active reservation alone");
        assertFalse(asC.forTrip(trip.getId()).isCanAssign(), "an operator of a hotel the trip does not use");
        assertFalse(asC.operatorOfAnyHotelOn(trip.getId()));

        // The internal caller: every hotel in play, and nothing idle.
        final LodgingAccess all = new LodgingAccessCommands(TestCallers::siteAdmin).forTrip(trip.getId());
        assertTrue(all.isInternal());
        assertTrue(all.isSeesPrices());
        assertTrue(all.isCanAssign());
        assertEquals(all.getAllowedAccIds(), Set.of(byOffer, byStayOnly));
        assertFalse(all.mayOpenBoard(idle));

        // Cancelling the stay takes its hotel out of play; with no FacesContext there is no memo to go stale.
        final Reservation fresh = DAO.getInstance().getReservation(trip.getId(), stay.getId(), Cached.NO)
                .orElseThrow();
        fresh.setStatus(Reservation.Status.CANCELLED);
        assertTrue(DAO.getInstance().saveReservation(fresh));
        assertFalse(asB.forTrip(trip.getId()).isCanAssign(), "a cancelled stay puts nothing in play");
        assertFalse(asB.forTrip(trip.getId()).isOperatorHere());
        assertEquals(new LodgingAccessCommands(TestCallers::siteAdmin).forTrip(trip.getId()).getAllowedAccIds(),
                Set.of(byOffer));
        assertEquals(LodgingAccessCommands.inPlayHotels(trip.getId()), Set.of(byOffer));
    }

    @Test
    public void nobodyAndNothingGetNoAccess() throws IOException {
        final Person guest = savedPerson("Bea");
        final Trip trip = tripWith("Nobody trip", guest);
        offerAt(trip, hotel());
        final LodgingAccessCommands anonymous = new LodgingAccessCommands(
                () -> new Caller(null, false, AuditActor.system(), new PrivilegeCommands()));
        final LodgingAccess none = anonymous.forTrip(trip.getId());
        assertFalse(none.isCanAssign());
        assertFalse(none.isInternal());
        assertFalse(none.isOperatorHere());
        assertTrue(none.getAllowedAccIds().isEmpty());
        assertFalse(anonymous.isGlobalLodgingAdmin());
        assertFalse(anonymous.canCreateAccommodation());
        assertFalse(anonymous.canManageTripLodging(trip.getId()));
        assertFalse(anonymous.canEditAccommodation("x"));

        final LodgingAccessCommands stranger = new LodgingAccessCommands(
                () -> TestCallers.person(Person.Id.newInstance()));
        assertFalse(stranger.forTrip(trip.getId()).isCanAssign(), "signed in, holds nothing");
        assertFalse(stranger.forTrip(null).isCanAssign());
        assertFalse(stranger.forTrip(" ").isCanAssign());
        assertFalse(stranger.canManageTripLodging(" "));
        assertFalse(stranger.canEditAccommodation(" "));
        final LodgingAccess blank = LodgingAccess.none("t");
        assertEquals(blank.getTripId(), "t");
        assertFalse(blank.mayOpenBoard(null));
        assertTrue(blank.toString().contains("t"));
    }

    /** The naming rule the board and the hotel calendar share: internal on the trip, or operator of the hotel. */
    @Test
    public void aTripIsNamedToItsInternalsAndToTheHotelsOperator() throws IOException {
        final Person guest = savedPerson("Cleo");
        final Trip trip = tripWith("Naming trip", guest);
        final String accId = hotel();
        offerAt(trip, accId);
        final Person keeper = operatorOf(accId);
        final Person manager = savedPerson("Dee");
        final PrivilegeCommands priv = new PrivilegeCommands();
        assertTrue(priv.savePrivilege(priv.getOrCreate(PrivilegeCommands.TRIP_MGR, trip.getId(), "mgr")
                .withNewPerson(manager.getId())));
        final String otherTrip = tripWith("Some other trip", guest).getId();

        assertTrue(new LodgingAccessCommands(TestCallers::siteAdmin).mayNameTrip(trip.getId(), accId));
        final LodgingAccessCommands asKeeper = new LodgingAccessCommands(() -> TestCallers.person(keeper.getId()));
        assertTrue(asKeeper.mayNameTrip(trip.getId(), accId), "the hotel's operator");
        assertTrue(asKeeper.mayNameTrip(otherTrip, accId), "...for any trip at THEIR hotel");
        assertFalse(asKeeper.mayNameTrip(otherTrip, hotel()), "...and no trip at another");
        final LodgingAccessCommands asManager = new LodgingAccessCommands(() -> TestCallers.person(manager.getId()));
        assertTrue(asManager.mayNameTrip(trip.getId(), accId), "internal on the trip");
        assertFalse(asManager.mayNameTrip(otherTrip, accId), "...that trip only");
        assertTrue(asManager.forTrip(trip.getId()).isInternal());
        assertFalse(asManager.forTrip(trip.getId()).isOperatorHere());
    }

    // ------------------------------------------------------------------ fixtures

    private String hotel() {
        final AccommodationForm form = new AccommodationForm();
        final String tag = RandomData.genAlpha(8);
        form.setName("Access hotel " + tag);
        form.setEmail(tag.toLowerCase(java.util.Locale.ROOT) + "@access.example");
        // Discovery compares phone DIGITS only, so the random part must be digits or every fixture is a duplicate.
        form.setPhone("+1 503 " + (1_000_000 + ThreadLocalRandom.current().nextInt(9_000_000)));
        form.setStreet(tag + " Way");
        form.setCity("Portland");
        final String accId = admin.saveAccommodation(form, orgId);
        assertTrue(accId != null && !accId.isBlank(), "the hotel fixture was refused as a duplicate");
        final RoomTypeForm type = new RoomTypeForm();
        type.setName("Double");
        type.setMinPeople(1);
        type.setMaxPeople(2);
        assertTrue(admin.saveRoomType(accId, type));
        return accId;
    }

    private void offerAt(final Trip trip, final String accId) {
        final OfferForm offerForm = new OfferForm();
        offerForm.setName("Room " + RandomData.genAlpha(4));
        offerForm.setAccommodationId(accId);
        offerForm.setRoomTypeIds(new ArrayList<>(List.of(admin.roomTypeRows(accId).get(0).getId())));
        offerForm.setTripEventId(OfferForm.NEW_EVENT);
        offerForm.setPricingModel("PER_ROOM");
        offerForm.setNightlyPrice(100.0);
        offerForm.setDefaultStart(CHECK_IN);
        offerForm.setDefaultEnd(CHECK_OUT);
        assertTrue(admin.saveOffer(trip.getId(), offerForm));
    }

    private static Person operatorOf(final String accId) throws IOException {
        final Person keeper = savedPerson("Keeper");
        final PrivilegeCommands priv = new PrivilegeCommands();
        assertTrue(priv.savePrivilege(priv.getOrCreate(PrivilegeCommands.ACCOMMODATION_ADMIN, accId,
                PrivilegeCommands.ACCOMMODATION_ROLE_NAME).withNewPerson(keeper.getId())));
        return keeper;
    }

    private Trip tripWith(final String title, final Person person) throws IOException {
        final Trip trip = Trip.builder().id(java.util.UUID.randomUUID().toString()).title(title)
                .startDate(CHECK_IN).endDate(CHECK_OUT.plusDays(2))
                .people(new ArrayList<>(List.of(person.getId()))).build();
        trip.setOrgId(orgId);
        assertTrue(DAO.getInstance().saveTrip(trip));
        return trip;
    }

    private static Person savedPerson(final String first) throws IOException {
        final Person person = Person.builder().first(first).last(RandomData.genAlpha(8))
                .email(first.toLowerCase(java.util.Locale.ROOT) + "." + RandomData.genAlpha(8)
                        .toLowerCase(java.util.Locale.ROOT) + "@example.com").build();
        assertTrue(DAO.getInstance().savePerson(person));
        return person;
    }
}
