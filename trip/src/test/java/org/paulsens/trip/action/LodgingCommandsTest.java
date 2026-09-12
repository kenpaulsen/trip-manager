package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.paulsens.trip.action.LodgingViews.AccommodationForm;
import org.paulsens.trip.action.LodgingViews.AssignOutcome;
import org.paulsens.trip.action.LodgingViews.CancelPreview;
import org.paulsens.trip.action.LodgingViews.ItineraryRow;
import org.paulsens.trip.action.LodgingViews.OfferForm;
import org.paulsens.trip.action.LodgingViews.PersonCard;
import org.paulsens.trip.action.LodgingViews.PlacementForm;
import org.paulsens.trip.action.LodgingViews.ReservationForm;
import org.paulsens.trip.action.LodgingViews.RoomBoard;
import org.paulsens.trip.action.LodgingViews.RoomCell;
import org.paulsens.trip.action.LodgingViews.RoomDetail;
import org.paulsens.trip.action.LodgingViews.RoomForm;
import org.paulsens.trip.action.LodgingViews.RoomRow;
import org.paulsens.trip.action.LodgingViews.RoomTypeForm;
import org.paulsens.trip.action.LodgingViews.RoomingRow;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Accommodation;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.RegistrationOption;
import org.paulsens.trip.model.Reservation;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import org.paulsens.trip.pay.LodgingBiller;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * The lodging bean end to end against the in-memory store: the authorization matrix, hotel editing, offers
 * (and the LODGING event they create), reservations with their automatic bills, the assignment board, the
 * rooming list, the legacy room label, and the itinerary override.
 */
public class LodgingCommandsTest {
    private static final LocalDateTime CHECK_IN = LocalDateTime.of(2027, 9, 21, 15, 0);
    private static final LocalDateTime CHECK_OUT = LocalDateTime.of(2027, 9, 24, 10, 0);

    private final LodgingCommands admin = new LodgingCommands(TestCallers::siteAdmin);
    private String orgId;
    private Trip trip;
    private Person ada;
    private Person bob;
    private Person cy;

    @BeforeClass
    public void seed() throws IOException {
        FakeData.initFakeData();
        FakeData.addFakeData();
        final OrgCommands orgs = new OrgCommands(TestCallers::siteAdmin);
        final Organization org = orgs.createOrganization("Lodging " + RandomData.genAlpha(8), "LDG", null);
        assertNotNull(org);
        orgId = org.getId().getValue();
        ada = savedPerson("Ada");
        bob = savedPerson("Bob");
        cy = savedPerson("Cy");
        // A canonical UUID: trip-scoped privilege rows refuse any other shape of scope id.
        trip = Trip.builder().id(java.util.UUID.randomUUID().toString()).title("Lodging trip")
                .startDate(CHECK_IN).endDate(CHECK_OUT.plusDays(2))
                .people(new ArrayList<>(List.of(ada.getId(), bob.getId(), cy.getId()))).build();
        trip.setOrgId(orgId);
        assertTrue(DAO.getInstance().saveTrip(trip));
    }

    // ------------------------------------------------------------------ gates

    @Test
    public void gatesFollowTheThreePrivilegesAndTheRoster() throws IOException {
        final LodgingCommands nobody = new LodgingCommands(
                () -> new Caller(null, false, org.paulsens.trip.audit.AuditActor.system(), new PrivilegeCommands()));
        assertFalse(nobody.canCreateAccommodation());
        assertFalse(nobody.canManageTripLodging(trip.getId()));
        assertFalse(nobody.canEditAccommodation("x"));
        assertFalse(nobody.canOpenLodgingAdmin());
        assertTrue(nobody.managedAccommodations().isEmpty());
        assertFalse(admin.canManageTripLodging(null));
        assertFalse(admin.canEditAccommodation(""));
        assertTrue(admin.isGlobalLodgingAdmin());
        assertTrue(admin.canOpenLodgingAdmin());

        final Person orgAdmin = savedPerson("Org");
        final OrgCommands orgs = new OrgCommands(TestCallers::siteAdmin);
        assertTrue(orgs.addMember(orgId, orgAdmin.getId()));
        assertTrue(orgs.grantOrgPrivilege(orgId, orgAdmin.getId(), PrivilegeCommands.LODGING_ADMIN));
        final LodgingCommands scoped = new LodgingCommands(() -> TestCallers.person(orgAdmin.getId()));
        assertTrue(scoped.canCreateAccommodation(), "A lodging admin of ANY org may create hotels");
        assertTrue(scoped.canManageTripLodging(trip.getId()), "...and manage lodging on that org's trips");
        assertFalse(scoped.isGlobalLodgingAdmin());
        final Trip elsewhere = Trip.builder().title("Elsewhere").build();
        elsewhere.setOrgId(Organization.Id.newInstance().getValue());
        assertTrue(DAO.getInstance().saveTrip(elsewhere));
        assertFalse(scoped.canManageTripLodging(elsewhere.getId()), "...but not another org's trip");

        final Person manager = savedPerson("Mgr");
        final PrivilegeCommands priv = new PrivilegeCommands();
        assertTrue(priv.savePrivilege(priv.getOrCreate(PrivilegeCommands.TRIP_MGR, trip.getId(), "mgr")
                .withNewPerson(manager.getId())));
        final LodgingCommands tripMgr = new LodgingCommands(() -> TestCallers.person(manager.getId()));
        assertTrue(tripMgr.canManageTripLodging(trip.getId()), "A trip manager manages its lodging");
        assertFalse(tripMgr.canCreateAccommodation(), "...but creates no hotels");
        assertFalse(tripMgr.canOpenLodgingAdmin());
    }

    // ------------------------------------------------------------------ accommodations

    @Test
    public void createFindDuplicatesContactGrantsAndEditRights() throws IOException {
        final AccommodationForm form = accommodationForm("Pansion " + RandomData.genAlpha(6));
        form.setContactEmail("ivan." + RandomData.genAlpha(6).toLowerCase() + "@example.com");
        form.setContactFirst("Ivan");
        form.setContactLast("Dragićević");
        final String accId = admin.saveAccommodation(form, orgId);
        assertFalse(accId.isEmpty(), "Created");
        final Accommodation acc = admin.findAccommodation(accId);
        assertEquals(acc.getName(), form.getName());
        assertEquals(acc.getAddress().getCountry(), "Bosnia and Herzegovina");
        assertEquals(acc.getEmail(), form.getEmail().toLowerCase());
        assertTrue(acc.usedBy(Organization.Id.from(orgId)), "The creating org uses it");
        assertNotNull(acc.getContactId());
        final Person contact = DAO.getInstance().getPerson(acc.getContactId(), Cached.NO).orElseThrow();
        assertEquals(contact.getFirst(), "Ivan");
        assertEquals(contact.getEmail(), form.getContactEmail());
        assertTrue(new OrgCommands(TestCallers::siteAdmin).isMember(orgId, contact.getId()),
                "The contact joined the creating org");
        final LodgingCommands asContact = new LodgingCommands(() -> TestCallers.person(contact.getId()));
        assertTrue(asContact.canEditAccommodation(accId), "The contact got accommodationAdmin for the hotel");
        assertTrue(asContact.canOpenLodgingAdmin());
        assertEquals(asContact.managedAccommodationRows().size(), 1);
        assertTrue(asContact.managedAccommodationRows().get(0).isEditable());
        assertFalse(asContact.canCreateAccommodation(), "...and nothing more");
        assertTrue(admin.managers(accId).stream().anyMatch(p -> p.getId().equals(contact.getId())));

        final ContactHitCheck check = new ContactHitCheck(admin.findContact(contact.getEmail()));
        assertTrue(check.hit.isFound());
        assertEquals(check.hit.getPersonId(), contact.getId().getValue());
        assertFalse(admin.findContact("nobody@nowhere.example").isFound());
        assertFalse(admin.findContact("garbage").isFound());

        final AccommodationForm again = accommodationForm("Different name");
        again.setEmail(form.getEmail());
        assertEquals(admin.findDuplicates(again).get(0).getId(), accId, "Same email: a likely duplicate");
        assertEquals(admin.saveAccommodation(again, orgId), "", "...so the create is held");
        again.setForce(true);
        assertFalse(admin.saveAccommodation(again, orgId).isEmpty(), "...until the admin says create anyway");
        assertTrue(admin.findDuplicates(null).isEmpty());

        final LodgingCommands stranger = new LodgingCommands(() -> TestCallers.person(ada.getId()));
        final AccommodationForm edit = admin.accommodationFormFor(accId);
        edit.setDescription("Edited");
        assertEquals(stranger.saveAccommodation(edit, orgId), "", "No accommodationAdmin, no edit");
        assertFalse(admin.saveAccommodation(edit, orgId).isEmpty());
        assertEquals(admin.findAccommodation(accId).getDescription(), "Edited");
        assertEquals(admin.saveAccommodation(new AccommodationForm(), orgId), "", "A name is required");
        final AccommodationForm badMail = accommodationForm("Bad mail");
        badMail.setEmail("not-an-email");
        assertEquals(admin.saveAccommodation(badMail, orgId), "");
        assertEquals(admin.getAccommodation("no-such").getName(), null, "Never-null bean convention");
        assertNull(admin.findAccommodation(" "));

        assertTrue(admin.addManager(accId, bob.getId()));
        assertTrue(new LodgingCommands(() -> TestCallers.person(bob.getId())).canEditAccommodation(accId));
        assertTrue(admin.removeManager(accId, bob.getId()));
        assertFalse(new LodgingCommands(() -> TestCallers.person(bob.getId())).canEditAccommodation(accId));
        assertFalse(stranger.addManager(accId, cy.getId()));
        assertFalse(admin.addManager("nope", cy.getId()));

        assertTrue(admin.retireAccommodation(accId, true));
        assertTrue(admin.findAccommodation(accId).isRetired());
        assertTrue(admin.getAccommodations().stream().noneMatch(a -> a.getId().getValue().equals(accId)));
        assertTrue(admin.retireAccommodation(accId, false));
        assertFalse(stranger.retireAccommodation(accId, true));
        assertFalse(admin.retireAccommodation("nope", true));
    }

    /** Small holder so the assertion lines above stay readable. */
    private record ContactHitCheck(LodgingViews.ContactHit hit) {
    }

    @Test
    public void contactCreationRefusesBadInputAndTheUnprivileged() {
        final LodgingCommands stranger = new LodgingCommands(() -> TestCallers.person(ada.getId()));
        assertNull(stranger.findOrCreateContact("x@example.com", "X Y", orgId));
        assertNull(admin.findOrCreateContact("garbage", "X Y", orgId));
        assertNull(admin.findOrCreateContact("new." + RandomData.genAlpha(5) + "@example.com", " ", orgId),
                "A new person needs a name");
        assertEquals(LodgingCommands.firstOf("Ivan"), "Ivan");
        assertEquals(LodgingCommands.lastOf("Ivan"), "");
        assertEquals(LodgingCommands.firstOf("Ivan Van Dyke"), "Ivan Van");
        assertEquals(LodgingCommands.lastOf("Ivan Van Dyke"), "Dyke");
        final Person.Id found = admin.findOrCreateContact(ada.getEmail(), null, orgId);
        assertEquals(found, ada.getId(), "An existing person is found, never modified");
        assertNull(admin.findOrCreateContact("no.org." + RandomData.genAlpha(5) + "@example.com", "No Org", ""),
                "The site-admin caller is no real person, so with no context there is no org to join");
        assertEquals(DAO.getInstance().getPerson(ada.getId(), Cached.NO).orElseThrow().getFirst(), "Ada");
    }

    @Test
    public void roomTypesRoomsBulkAddFloorsAndRegions() throws IOException {
        final String accId = admin.saveAccommodation(accommodationForm("Rooms " + RandomData.genAlpha(6)), orgId);
        final LodgingCommands stranger = new LodgingCommands(() -> TestCallers.person(ada.getId()));

        final RoomTypeForm dbl = new RoomTypeForm();
        dbl.setName(" Double ");
        dbl.setMinPeople(1);
        dbl.setMaxPeople(2);
        assertFalse(stranger.saveRoomType(accId, dbl));
        assertTrue(admin.saveRoomType(accId, dbl));
        assertFalse(admin.saveRoomType(accId, new RoomTypeForm()), "A name is required");
        final RoomTypeForm inverted = new RoomTypeForm();
        inverted.setName("Bad");
        inverted.setMinPeople(3);
        inverted.setMaxPeople(2);
        assertFalse(admin.saveRoomType(accId, inverted));
        final String typeId = admin.roomTypeRows(accId).get(0).getId();
        assertEquals(admin.roomTypeRows(accId).get(0).getName(), "Double");
        assertEquals(admin.roomTypeFormFor(accId, typeId).getMaxPeople(), 2);
        assertEquals(admin.roomTypeChoices(accId).get(typeId), "Double (1-2)");

        final RoomForm r114 = new RoomForm();
        r114.setRoomNumber(" 114 ");
        r114.setFloor("1");
        r114.setRoomTypeId(typeId);
        r114.setNotes("Mountain view");
        assertTrue(admin.saveRoom(accId, r114));
        assertFalse(admin.saveRoom(accId, r114), "Duplicate number (a NEW room with the same number)");
        assertFalse(admin.saveRoom(accId, new RoomForm()), "A number is required");
        final RoomForm noType = new RoomForm();
        noType.setRoomNumber("9");
        assertFalse(admin.saveRoom(accId, noType), "A type is required");
        assertEquals(admin.bulkAddRooms(accId, "", 101, 103, "1", typeId), 3);
        assertEquals(admin.bulkAddRooms(accId, "", 103, 104, "1", typeId), 1, "103 already existed");
        assertEquals(admin.bulkAddRooms(accId, "", 104, 104, "1", typeId), 0);
        assertEquals(admin.bulkAddRooms(accId, "", 5, 1, "1", typeId), 0, "Inverted range");
        assertEquals(admin.bulkAddRooms(accId, "", 1, 1, "1", "no-type"), 0);
        assertEquals(stranger.bulkAddRooms(accId, "", 1, 1, "1", typeId), 0);
        assertEquals(admin.bulkAddRooms(accId, "B", 1, 2, "2", typeId), 2);
        final List<RoomRow> rows = admin.roomRows(accId);
        assertEquals(rows.stream().map(RoomRow::getRoomNumber).toList(),
                List.of("101", "102", "103", "104", "114", "B1", "B2"), "Floor, then natural number order");
        assertEquals(rows.get(4).getTypeName(), "Double");
        assertEquals(admin.floorsOf(accId), List.of("1", "2"));
        assertEquals(admin.roomsOnFloor(accId, "2").size(), 2);
        assertEquals(LodgingCommands.naturalCompare("9", "114") < 0, true);
        assertEquals(LodgingCommands.naturalCompare(null, "1") > 0, true);
        assertEquals(LodgingCommands.naturalCompare("1", " ") < 0, true);
        assertEquals(LodgingCommands.naturalCompare(null, null), 0);
        assertEquals(LodgingCommands.naturalCompare("1A", "1B") < 0, true);

        final String roomId = rows.get(4).getId();
        final RoomForm edit = admin.roomFormFor(accId, roomId);
        assertEquals(edit.getNotes(), "Mountain view");
        edit.setAdminNotes("Creaky");
        assertTrue(admin.saveRoom(accId, edit));
        assertEquals(admin.roomFormFor(accId, roomId).getAdminNotes(), "Creaky");
        assertNull(admin.roomFormFor(accId, "nope").getId());

        assertFalse(admin.deleteRoomType(accId, typeId), "Rooms still use it");
        assertFalse(admin.deleteRoomType(accId, "nope"));
        assertFalse(stranger.deleteRoom(accId, roomId));
        assertTrue(admin.deleteRoom(accId, admin.roomRows(accId).get(6).getId()));
        assertFalse(admin.deleteRoom(accId, "nope"));

        // Regions: two mapped, one deleted by omission, an invalid box refused, a room off the floor refused.
        final String r101 = rows.get(0).getId();
        final String r102 = rows.get(1).getId();
        final String json = "[{\"roomId\":\"" + r101 + "\",\"kind\":\"rect\",\"x\":10,\"y\":10,\"w\":5,\"h\":5},"
                + "{\"roomId\":\"" + r102 + "\",\"x\":20.123,\"y\":10,\"w\":5,\"h\":5},{\"roomId\":\"\"}]";
        assertFalse(stranger.saveFloorRegions(accId, "1", json));
        assertTrue(admin.saveFloorRegions(accId, "1", json));
        assertEquals(admin.mappedRoomsOnFloor(accId, "1").size(), 2);
        assertEquals(admin.mappedRoomsOnFloor(accId, "1").get(1).getX(), 20.12, "Two decimals");
        final String only101 = "[{\"roomId\":\"" + r101 + "\",\"x\":1,\"y\":1,\"w\":2,\"h\":2}]";
        assertTrue(admin.saveFloorRegions(accId, "1", only101));
        assertEquals(admin.mappedRoomsOnFloor(accId, "1").size(), 1, "102's box was deleted by omission");
        final String outside = "[{\"roomId\":\"" + r101 + "\",\"x\":99,\"y\":1,\"w\":5,\"h\":2}]";
        assertFalse(admin.saveFloorRegions(accId, "1", outside), "Outside the image");
        assertFalse(admin.saveFloorRegions(accId, "2", only101), "101 is not on floor 2");
        assertFalse(admin.saveFloorRegions(accId, "1", "not json"));
        assertFalse(admin.saveFloorRegions(accId, "1", "[{\"roomId\":\"" + r101 + "\",\"x\":\"a\"}]"));
        assertFalse(admin.saveFloorRegions(accId, "1", "[{\"roomId\":\"" + r101 + "\",\"x\":1,\"y\":1,\"w\":2,\"h\":2},"
                + "{\"roomId\":\"" + r101 + "\",\"x\":5,\"y\":5,\"w\":2,\"h\":2}]"), "Mapped twice");
        assertTrue(admin.saveFloorRegions(accId, "1", ""), "Nothing mapped is a valid floor");
        assertTrue(admin.mappedRoomsOnFloor(accId, "1").isEmpty());
        assertEquals(admin.floorMapMediaId(accId, "1"), "");
        assertTrue(admin.setFloorMap(accId, "1", "media-1"));
        assertTrue(admin.setFloorMap(accId, "1", "media-2"), "Replacing keeps one entry per floor");
        assertEquals(admin.floorMapMediaId(accId, "1"), "media-2");
        assertFalse(admin.setFloorMap(accId, " ", "media-2"));
        assertTrue(admin.setFloorMap(accId, "9", "media-9"), "A plan may land on a floor with no rooms yet");
        assertTrue(admin.floorsOf(accId).contains("9"), "...but it then shows up as a floor of its own");

        // The repair for a plan that ended up on a floor its rooms are not on: move it, keeping the image.
        assertFalse(admin.moveTargets(accId, "9").contains("9"), "Not to the floor it is already on");
        assertFalse(admin.moveTargets(accId, "9").contains("1"), "Floor 1 has a plan of its own");
        assertFalse(admin.moveFloorMap(accId, "9", "1"), "That would silently replace floor 1's plan");
        assertTrue(admin.saveFloorRegions(accId, "1", "[{\"roomId\":\"" + r101 + "\",\"x\":1,\"y\":1,"
                + "\"w\":2,\"h\":2}]"));
        assertTrue(admin.removeFloorMap(accId, "1"));
        assertTrue(admin.moveTargets(accId, "9").contains("1"), "Now floor 1 is free to receive it");
        assertFalse(admin.moveFloorMap(accId, "9", "9"), "Already there");
        assertFalse(admin.moveFloorMap(accId, "8", "1"), "Floor 8 has no plan to move");
        assertFalse(admin.moveFloorMap(accId, "9", " "));
        assertTrue(admin.moveFloorMap(accId, "9", "1"));
        assertEquals(admin.floorMapMediaId(accId, "1"), "media-9", "The image moved with the floor");
        assertFalse(admin.floorsOf(accId).contains("9"), "...and the floor that only the plan named is gone");
        assertTrue(admin.mappedRoomsOnFloor(accId, "1").isEmpty(), "Boxes were drawn on the OTHER plan");

        assertFalse(admin.removeFloorMap(accId, "8"), "Floor 8 never had a plan");
        assertFalse(admin.removeFloorMap(accId, " "));
        assertTrue(admin.removeFloorMap(accId, "1"));
        assertEquals(admin.floorMapMediaId(accId, "1"), "");
        assertEquals(admin.mediaUrl("no-such-media"), "");
        assertTrue(admin.countrySuggestions("bosn").contains("Bosnia and Herzegovina"));
        assertEquals(admin.countrySuggestions(null).size(), LodgingCommands.COUNTRIES.size());

        assertTrue(admin.addPhoto(accId, null, "m-1"));
        assertTrue(admin.addPhoto(accId, typeId, "m-2"));
        assertFalse(admin.addPhoto(accId, "no-type", "m-3"));
        assertFalse(admin.addPhoto(accId, null, " "));
        assertTrue(admin.galleryOf(accId).isEmpty(), "The ids do not resolve in the media table, so nothing shows");
        assertFalse(admin.removePhoto(accId, typeId, "m-2"), "A dangling id was pruned by the save itself");
        assertTrue(admin.findAccommodation(accId).getPhotoIds().isEmpty());
        assertFalse(admin.movePhoto(accId, null, "m-1", -1));
    }

    // ------------------------------------------------------------------ offers

    /** A hotel with one Double type and rooms 101/102, plus (optionally) a PER_ROOM offer on the trip. */
    private record Stay(String accId, String typeId, String r101, String r102, ReservationOffer offer,
            String eventId) {
    }

    /** Saves a registration, turning the DAO's checked IOException into a test failure. */
    private boolean saveRegistration(final Registration reg) {
        try {
            return DAO.getInstance().saveRegistration(reg);
        } catch (final IOException e) {
            throw new IllegalStateException("Could not seed a registration", e);
        }
    }

    private Stay stay(final boolean withOffer) {
        return stay(withOffer, trip);
    }

    private Stay stay(final boolean withOffer, final Trip onTrip) {
        final String accId = admin.saveAccommodation(accommodationForm("Stay " + RandomData.genAlpha(6)), orgId);
        final RoomTypeForm dbl = new RoomTypeForm();
        dbl.setName("Double");
        dbl.setMinPeople(1);
        dbl.setMaxPeople(2);
        assertTrue(admin.saveRoomType(accId, dbl));
        final String typeId = admin.roomTypeRows(accId).get(0).getId();
        assertEquals(admin.bulkAddRooms(accId, "", 101, 102, "1", typeId), 2);
        final String r101 = admin.roomRows(accId).get(0).getId();
        final String r102 = admin.roomRows(accId).get(1).getId();
        if (!withOffer) {
            return new Stay(accId, typeId, r101, r102, null, null);
        }
        final OfferForm offerForm = new OfferForm();
        offerForm.setName("Double room " + RandomData.genAlpha(4));
        offerForm.setAccommodationId(accId);
        offerForm.setRoomTypeIds(new ArrayList<>(List.of(typeId)));
        offerForm.setTripEventId(OfferForm.NEW_EVENT);
        offerForm.setPricingModel("PER_ROOM");
        offerForm.setNightlyPrice(120.0);
        offerForm.setDefaultStart(CHECK_IN);
        offerForm.setDefaultEnd(CHECK_OUT);
        offerForm.setCancelFeeKind("PERCENT");
        offerForm.setCancelFeeAmount(10.0);
        offerForm.setPolicyHtml("<p>No refunds after Sep 1</p>");
        assertTrue(admin.saveOffer(onTrip.getId(), offerForm));
        final ReservationOffer offer = admin.getOffers(onTrip.getId()).stream()
                .filter(o -> o.getName().equals(offerForm.getName())).findFirst().orElseThrow();
        return new Stay(accId, typeId, r101, r102, offer, offer.getTripEventId());
    }

    @Test
    public void offersCreateTheirLodgingEventAndValidate() {
        final Stay stay = stay(false);
        final OfferForm blank = admin.offerFormFor(trip.getId(), null);
        assertEquals(blank.getDefaultStart(), trip.getStartDate(), "Defaults from the trip");
        final OfferForm offerForm = new OfferForm();
        offerForm.setName("Double room");
        offerForm.setAccommodationId(stay.accId());
        offerForm.setRoomTypeIds(new ArrayList<>(List.of(stay.typeId())));
        offerForm.setTripEventId(OfferForm.NEW_EVENT);
        offerForm.setNightlyPrice(120.0);
        offerForm.setDefaultStart(CHECK_IN);
        offerForm.setDefaultEnd(CHECK_OUT);
        offerForm.setCancelFeeKind("PERCENT");
        offerForm.setCancelFeeAmount(10.0);
        assertFalse(new LodgingCommands(() -> TestCallers.person(ada.getId())).saveOffer(trip.getId(), offerForm));
        assertTrue(admin.saveOffer(trip.getId(), offerForm));
        final ReservationOffer offer = admin.getOffers(trip.getId()).stream()
                .filter(o -> stay.accId().equals(o.getAccommodationId().getValue())).findFirst().orElseThrow();
        assertEquals(offer.getCancelFeeBps(), Integer.valueOf(1000));
        assertEquals(offer.getNightlyPriceCents(), 12000L);
        final Trip fresh = DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow();
        final TripEvent event = fresh.getTripEvent(offer.getTripEventId());
        assertNotNull(event, "The offer created a LODGING event on the trip");
        assertEquals(event.getType(), TripEvent.Type.LODGING);
        assertEquals(event.getTitle(), admin.findAccommodation(stay.accId()).getName());
        assertEquals(admin.lodgingEventChoices(trip.getId()).get(event.getId()), event.getTitle());
        assertTrue(admin.offerRows(trip.getId()).stream()
                .anyMatch(row -> row.getPricing().equals("$120.00/night per room")));
        assertFalse(admin.defaultOfferId(trip.getId()).isEmpty());
        assertEquals(admin.roomChoices(stay.accId()).get(stay.r101()), "101 (Double, floor 1)");

        final OfferForm edit = admin.offerFormFor(trip.getId(), offer.getId().getValue());
        assertEquals(edit.getCancelFeeKind(), "PERCENT");
        assertEquals(edit.getCancelFeeAmount(), 10.0);
        edit.setTripEventId(event.getId());
        edit.setPerNightPricing(true);
        edit.getPerNight().put("2027-09-22", "$150.00");
        assertEquals(admin.perNightDates(edit), List.of("2027-09-21", "2027-09-22", "2027-09-23"));
        assertTrue(admin.saveOffer(trip.getId(), edit));
        assertEquals(admin.findOffer(trip.getId(), offer.getId().getValue()).nightlyPriceCents(
                java.time.LocalDate.of(2027, 9, 22)), 15000L);
        assertTrue(admin.offerRows(trip.getId()).stream()
                .anyMatch(row -> row.getPricing().equals("varies by night per room")));
        edit.setPerNightPricing(false);
        edit.setCancelFeeKind("FLAT");
        edit.setCancelFeeAmount(15.0);
        assertTrue(admin.saveOffer(trip.getId(), edit));
        final ReservationOffer flat = admin.findOffer(trip.getId(), offer.getId().getValue());
        assertEquals(flat.getNightlyPriceOverrides().size(), 0);
        assertEquals(flat.getCancelFeeFixedCents(), Long.valueOf(1500L));
        assertEquals(LodgingCommands.feeRule(flat), "$15.00");
        assertEquals(admin.offerFormFor(trip.getId(), offer.getId().getValue()).getCancelFeeAmount(), 15.0);

        final OfferForm bad = admin.offerFormFor(trip.getId(), offer.getId().getValue());
        bad.setDefaultEnd(bad.getDefaultStart());
        assertFalse(admin.saveOffer(trip.getId(), bad), "Check-out before check-in");
        bad.setDefaultEnd(CHECK_OUT);
        bad.setNightlyPrice(-1.0);
        assertFalse(admin.saveOffer(trip.getId(), bad));
        bad.setNightlyPrice(1.0);
        bad.setTripEventId("no-such-event");
        assertFalse(admin.saveOffer(trip.getId(), bad));
        bad.setTripEventId(event.getId());
        bad.setAccommodationId("nope");
        assertFalse(admin.saveOffer(trip.getId(), bad));
        assertFalse(admin.saveOffer(trip.getId(), new OfferForm()), "A name is required");
        assertFalse(admin.saveOffer("no-such-trip", offerForm));
        assertFalse(admin.deleteOffer(trip.getId(), "nope"));
        assertTrue(admin.deleteOffer(trip.getId(), offer.getId().getValue()), "No reservations: it may go");

        final OfferForm single = admin.offerFormFor(trip.getId(), null);
        single.setName("Single room");
        single.setAccommodationId(stay.accId());
        single.setRoomTypeIds(new ArrayList<>(List.of(stay.typeId())));
        single.setTripEventId(event.getId());
        single.setPricingModel("PER_PERSON");
        single.setNightlyPrice(55.0);
        single.setSingleSupplement(20.0);
        single.setDefaultStart(CHECK_IN);
        single.setDefaultEnd(CHECK_OUT);
        assertTrue(admin.saveOffer(trip.getId(), single));
        final ReservationOffer perPerson = admin.getOffers(trip.getId()).stream()
                .filter(o -> o.getName().equals("Single room")).findFirst().orElseThrow();
        assertEquals(LodgingCommands.pricingLabel(perPerson), "$55.00/night per person, +$20.00 single");
        assertEquals(LodgingCommands.feeRule(perPerson), "no cancellation fee");
        assertTrue(admin.deleteOffer(trip.getId(), perPerson.getId().getValue()));
        assertTrue(admin.autoRecompute(orgId), "The setting defaults on");
    }

    // ------------------------------------------------------------------ reservations and the board

    @Test
    public void reservationsJoinTheEventBillAndTheBoardAssignsWithACapacityWarning() {
        final Stay stay = stay(true);
        final String offerId = stay.offer().getId().getValue();
        final ReservationForm create = admin.reservationFormFor(trip.getId(), null);
        create.setOfferId(offerId);
        assertEquals(create.getStart(), CHECK_IN, "Dates default from the offer");
        create.setPersonIds(new ArrayList<>(List.of(ada.getId().getValue(), bob.getId().getValue())));
        assertEquals(admin.createReservations(trip.getId(), create), 2);
        final Trip joined = DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow();
        assertTrue(joined.getTripEvent(stay.eventId()).getParticipants()
                .containsAll(List.of(ada.getId(), bob.getId())), "Occupants joined the offer's event");
        final Reservation adaRes = reservationOf(ada, offerId);
        final Reservation bobRes = reservationOf(bob, offerId);
        final LodgingBiller biller = new LodgingBiller(new TransactionsCommands());
        assertEquals(biller.billedByPerson(adaRes), Map.of(ada.getId(), 3 * 12000L), "Alone: the whole room");
        final Transaction bill = DAO.getInstance().getTransaction(ada.getId(),
                LodgingBiller.billTxId(adaRes.getId(), ada.getId()), Cached.NO).orElseThrow();
        assertEquals(bill.getAmount(), -360.0f, "Bills are negative");

        final ReservationForm outsider = admin.reservationFormFor(trip.getId(), null);
        outsider.setOfferId(offerId);
        outsider.setPersonIds(List.of(Person.Id.newInstance().getValue()));
        assertEquals(admin.createReservations(trip.getId(), outsider), 0, "Not on the roster");
        final ReservationForm tooShort = admin.reservationFormFor(trip.getId(), null);
        tooShort.setOfferId(offerId);
        tooShort.setPersonIds(List.of(cy.getId().getValue()));
        tooShort.setEnd(tooShort.getStart().plusHours(2));
        assertEquals(admin.createReservations(trip.getId(), tooShort), 0, "Under the minimum stay");
        assertEquals(admin.createReservations(trip.getId(), new ReservationForm()), 0);
        assertEquals(new LodgingCommands(() -> TestCallers.person(ada.getId()))
                .createReservations(trip.getId(), create), 0);

        AssignOutcome outcome = admin.assignRoom(trip.getId(), adaRes.getId().getValue(), stay.r101(), false,
                null, null);
        assertTrue(outcome.isAssigned(), outcome.getMessage());
        outcome = admin.assignRoom(trip.getId(), bobRes.getId().getValue(), stay.r101(), false, null, null);
        assertTrue(outcome.isAssigned());
        assertEquals(biller.billedByPerson(admin.findReservation(trip.getId(), adaRes.getId().getValue())),
                Map.of(ada.getId(), 3 * 6000L), "Two in the room: Ada's bill halved by the recompute");
        // Cy holds no reservation: placing him asks which lodging option pays, and for the stay.
        final PlacementForm place = admin.placementFormFor(trip.getId(), stay.accId(), cy.getId().getValue(),
                stay.r101());
        assertEquals(place.getOfferId(), offerId, "one option at this hotel: preselected");
        assertEquals(place.getPersonName(), "Cy " + cy.getLast());
        assertTrue(place.getRoomLabel().contains("101"));
        assertEquals(place.getRange(), List.of(CHECK_IN.toLocalDate(), CHECK_OUT.toLocalDate()),
                "the stay defaults to the option's");
        outcome = admin.place(trip.getId(), place);
        assertFalse(outcome.isAssigned());
        assertTrue(outcome.isOverCapacity(), "101 sleeps 2");
        assertTrue(outcome.getMessage().contains("sleeps 2"), outcome.getMessage());
        assertEquals(place.getProblem(), outcome.getMessage(), "the dialog shows why, in place");
        assertTrue(admin.activeReservationsFor(trip.getId(), cy.getId()).stream()
                .noneMatch(res -> res.getOfferId().getValue().equals(offerId)), "Nothing minted on a refusal");
        place.setForce(true);
        outcome = admin.place(trip.getId(), place);
        assertTrue(outcome.isAssigned());
        assertTrue(outcome.isOverCapacity());
        final Reservation cyRes = reservationOf(cy, offerId);
        assertEquals(cyRes.getRoomId(), stay.r101());
        RoomBoard board = admin.roomBoard(trip.getId(), stay.accId(), null, null);
        final RoomCell cell101 = board.getRooms().get(0);
        assertEquals(cell101.getCount(), 3);
        assertEquals(cell101.getState(), "rs-over");
        assertEquals(cell101.getOccupants().size(), 3);
        assertEquals(board.getRooms().get(1).getState(), "rs-empty");
        assertTrue(board.getUnassigned().isEmpty());
        assertTrue(board.getNoReservation().isEmpty());
        assertEquals(LodgingCommands.stateOf(1, 2), "rs-partial");
        assertEquals(LodgingCommands.stateOf(2, 2), "rs-full");
        assertEquals(LodgingCommands.stateOf(1, 0), "rs-partial");
        assertEquals(LodgingCommands.ageAt(java.time.LocalDate.of(2000, 1, 1), CHECK_IN), "27");
        assertEquals(LodgingCommands.ageAt(null, CHECK_IN), "");
        assertTrue(admin.roomBoard(trip.getId(), "nope", null, null).getRooms().isEmpty(), "unknown hotel");
        assertTrue(new LodgingCommands(() -> TestCallers.person(ada.getId()))
                .roomBoard(trip.getId(), stay.accId(), null, null).getRooms().isEmpty());

        assertTrue(admin.unassignRoom(trip.getId(), cyRes.getId().getValue()));
        board = admin.roomBoard(trip.getId(), stay.accId(), null, null);
        assertEquals(board.getRooms().get(0).getState(), "rs-full");
        assertEquals(board.getUnassigned().size(), 1, "Cy waits for a room again");
        assertEquals(board.getUnassigned().get(0).getName(), "Cy " + cy.getLast());
        assertEquals(board.getUnassigned().get(0).getAge(), "37");
        assertFalse(admin.unassignRoom(trip.getId(), "nope"));
        assertFalse(admin.assignRoom(trip.getId(), cyRes.getId().getValue(), "no-room", false, null, null)
                .isAssigned());
        assertFalse(admin.assignRoom(trip.getId(), "nope", stay.r102(), false, null, null).isAssigned(),
                "No such reservation");
        assertFalse(new LodgingCommands(() -> TestCallers.person(ada.getId()))
                .assignRoom(trip.getId(), cyRes.getId().getValue(), stay.r102(), false, null, null).isAssigned(),
                "Not a manager");
        final PlacementForm nobody = admin.placementFormFor(trip.getId(), stay.accId(), "", stay.r102());
        assertFalse(admin.place(trip.getId(), nobody).isAssigned(), "Nobody named");
        final PlacementForm noOption = admin.placementFormFor(trip.getId(), stay.accId(), cy.getId().getValue(),
                stay.r102());
        noOption.setOfferId("");
        assertFalse(admin.place(trip.getId(), noOption).isAssigned(), "No option chosen");
        assertEquals(noOption.getProblem(), "Choose a lodging option.");
        assertFalse(new LodgingCommands(() -> TestCallers.person(ada.getId()))
                .place(trip.getId(), admin.placementFormFor(trip.getId(), stay.accId(), cy.getId().getValue(),
                        stay.r102())).isAssigned(), "Not a manager");
    }

    @Test
    public void editRoomingListItineraryAndCancelWithCredit() {
        final Stay stay = stay(true);
        final String offerId = stay.offer().getId().getValue();
        final ReservationForm create = admin.reservationFormFor(trip.getId(), null);
        create.setOfferId(offerId);
        create.setPersonIds(new ArrayList<>(List.of(ada.getId().getValue(), bob.getId().getValue(),
                cy.getId().getValue())));
        assertEquals(admin.createReservations(trip.getId(), create), 3);
        final Reservation adaRes = reservationOf(ada, offerId);
        final Reservation bobRes = reservationOf(bob, offerId);
        final Reservation cyRes = reservationOf(cy, offerId);
        for (final Reservation res : List.of(adaRes, bobRes)) {
            assertTrue(admin.assignRoom(trip.getId(), res.getId().getValue(), stay.r101(), false, null, null)
                    .isAssigned());
        }
        final LodgingBiller biller = new LodgingBiller(new TransactionsCommands());

        final ReservationForm cyEdit = admin.reservationFormFor(trip.getId(), cyRes.getId().getValue());
        assertEquals(cyEdit.getPersonIds(), List.of(cy.getId().getValue()));
        cyEdit.setRoomId(stay.r102());
        cyEdit.setNotes("Late flight");
        cyEdit.setStart(CHECK_IN.plusDays(1));
        assertTrue(admin.updateReservation(trip.getId(), cyEdit));
        assertEquals(admin.roomLabelFor(trip.getId(), cy.getId()), "102");
        assertEquals(admin.roomLabel(trip.getId(), Person.Id.newInstance()), "");
        assertNull(admin.roomLabelFor(null, cy.getId()));
        final List<RoomingRow> rooming = admin.roomingList(trip.getId());
        assertEquals(rooming.size(), 3);
        assertEquals(rooming.get(0).getRoom(), "101");
        assertEquals(rooming.get(2).getRoom(), "102");
        assertEquals(rooming.get(2).getNotes(), "Late flight");
        assertTrue(rooming.get(2).isReserved());
        assertTrue(admin.roomingList("no-such-trip").isEmpty());
        final List<ItineraryRow> rows = admin.itineraryRowsFor(
                DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow(), cy.getId());
        final ItineraryRow row = rows.stream().filter(r -> r.getId().equals(stay.eventId())).findFirst()
                .orElseThrow();
        assertTrue(row.isLodging());
        assertTrue(row.isOverridden(), "Cy's reservation dates replace the event's");
        assertEquals(row.getEffectiveStart(), CHECK_IN.plusDays(1));
        assertEquals(row.getStart(), CHECK_IN, "The event's own dates are kept beside them");
        assertEquals(row.getRoomLabel(), "102");
        assertEquals(row.getRoomTypeName(), "Double");
        assertEquals(row.getReservationNotes(), "Late flight");
        assertEquals(row.getNights(), 2);
        assertTrue(admin.itineraryRows(null, List.of(), cy.getId()).isEmpty());
        assertTrue(admin.reservationRows(trip.getId(), false).size() >= 3);
        assertTrue(new LodgingCommands(() -> TestCallers.person(ada.getId())).reservationRows(trip.getId(), true)
                .isEmpty());
        final ReservationForm badEdit = admin.reservationFormFor(trip.getId(), cyRes.getId().getValue());
        badEdit.setPersonIds(new ArrayList<>());
        assertFalse(admin.updateReservation(trip.getId(), badEdit));
        badEdit.setPersonIds(List.of(cy.getId().getValue()));
        badEdit.setRoomId("nope");
        assertFalse(admin.updateReservation(trip.getId(), badEdit));
        assertFalse(admin.updateReservation(trip.getId(), new ReservationForm()));

        final CancelPreview preview = admin.cancelPreview(trip.getId(), bobRes.getId().getValue());
        assertEquals(preview.getBilledCents(), 3 * 6000L, "Half of three nights at $120");
        assertEquals(preview.getFeeCents(), 1800L);
        assertEquals(preview.getCreditCents(), 16200L);
        assertEquals(preview.getFeeRule(), "10.0% of the bill");
        assertNull(admin.cancelPreview(trip.getId(), "nope").getReservationId());
        assertFalse(new LodgingCommands(() -> TestCallers.person(ada.getId())).cancelReservation(trip.getId(),
                bobRes.getId().getValue(), true, null, null));
        assertTrue(admin.cancelReservation(trip.getId(), bobRes.getId().getValue(), true, null, "Changed plans"));
        final Reservation cancelled = admin.findReservation(trip.getId(), bobRes.getId().getValue());
        assertEquals(cancelled.getStatus(), Reservation.Status.CANCELLED);
        assertEquals(cancelled.getCancelFeeCents(), Long.valueOf(1800L));
        assertEquals(cancelled.getCancelReason(), "Changed plans");
        final Transaction credit = DAO.getInstance().getTransaction(bob.getId(),
                LodgingBiller.cancelTxId(bobRes.getId(), bob.getId()), Cached.NO).orElseThrow();
        assertEquals(credit.getAmount(), 162.0f, "Credited: billed minus the fee, positive");
        assertNotNull(DAO.getInstance().getTransaction(bob.getId(),
                LodgingBiller.billTxId(bobRes.getId(), bob.getId()), Cached.NO).orElse(null), "Original kept");
        final Trip after = DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow();
        assertFalse(after.getTripEvent(stay.eventId()).getParticipants().contains(bob.getId()),
                "Bob left the event (no other reservation of his tracks it)");
        assertTrue(after.getTripEvent(stay.eventId()).getParticipants().contains(ada.getId()));
        assertEquals(biller.billedByPerson(admin.findReservation(trip.getId(), adaRes.getId().getValue())),
                Map.of(ada.getId(), 3 * 12000L), "Ada is alone in 101 again: the whole room");
        assertFalse(admin.cancelReservation(trip.getId(), bobRes.getId().getValue(), true, null, null),
                "Already cancelled");
        assertTrue(admin.recomputeBills(trip.getId(), null));
        assertTrue(admin.recomputeBills(trip.getId(), stay.r101()));
        assertTrue(admin.accommodationExists(stay.accId()));
        assertFalse(admin.accommodationExists("nope"));
        assertTrue(admin.cancelReservationWithFee(trip.getId(), adaRes.getId().getValue(), true, 1.0, null));
        assertEquals(admin.findReservation(trip.getId(), adaRes.getId().getValue()).getCancelFeeCents(),
                Long.valueOf(100L), "The dialog's dollar fee, in cents");
        assertFalse(new LodgingCommands(() -> TestCallers.person(ada.getId())).recomputeBills(trip.getId(), null));
        assertFalse(admin.deleteOffer(trip.getId(), offerId), "Reservations reference it");
    }

    // ------------------------------------------------------------------ refusals and edges

    @Test
    public void everyEditRefusesStrangersAndVanishedHotels() throws IOException {
        final Stay stay = stay(false);
        final LodgingCommands stranger = new LodgingCommands(() -> TestCallers.person(ada.getId()));
        final String gone = Accommodation.Id.newInstance().getValue();
        assertFalse(stranger.saveRoom(stay.accId(), new RoomForm()));
        assertFalse(stranger.deleteRoomType(stay.accId(), stay.typeId()));
        assertFalse(stranger.setFloorMap(stay.accId(), "1", "m"));
        assertFalse(stranger.removeFloorMap(stay.accId(), "1"));
        assertFalse(stranger.moveFloorMap(stay.accId(), "1", "2"));
        assertFalse(stranger.saveFloorRegions(stay.accId(), "1", ""));
        assertFalse(stranger.addPhoto(stay.accId(), null, "m"));
        assertFalse(stranger.movePhoto(stay.accId(), null, "m", 1));
        assertFalse(stranger.removePhoto(stay.accId(), null, "m"));
        assertFalse(stranger.removeManager(stay.accId(), ada.getId()));
        assertTrue(stranger.managers(null).isEmpty());
        assertTrue(stranger.roomTypeRows(gone).isEmpty());
        assertTrue(stranger.roomRows(gone).isEmpty());
        assertTrue(stranger.floorsOf(gone).isEmpty());
        assertTrue(stranger.galleryOf(gone).isEmpty());
        assertTrue(stranger.roomTypeGallery(stay.accId(), "nope").isEmpty());
        assertTrue(stranger.roomTypeChoices(gone).isEmpty());
        assertTrue(stranger.roomChoices(gone).isEmpty());
        assertEquals(stranger.floorMapMediaId(gone, "1"), "");
        assertNull(stranger.roomTypeFormFor(gone, "x").getId());
        assertNull(stranger.accommodationFormFor(gone).getId());
        assertTrue(admin.perNightDates(null).isEmpty());
        assertTrue(admin.accommodationRows().stream().anyMatch(row -> row.getId().equals(stay.accId())));
        final RoomTypeForm type = new RoomTypeForm();
        type.setName("Ghost");
        assertFalse(admin.saveRoomType(gone, type));
        assertFalse(admin.deleteRoomType(gone, "x"));
        final RoomForm room = new RoomForm();
        room.setRoomNumber("1");
        room.setRoomTypeId(stay.typeId());
        assertFalse(admin.saveRoom(gone, room));
        assertFalse(admin.deleteRoom(gone, "x"));
        assertEquals(admin.bulkAddRooms(gone, "", 1, 2, "1", stay.typeId()), 0);
        assertFalse(admin.setFloorMap(gone, "1", "m"));
        assertFalse(admin.removeFloorMap(gone, "1"));
        assertFalse(admin.moveFloorMap(gone, "1", "2"));
        assertTrue(admin.moveTargets(gone, "1").isEmpty());
        assertFalse(admin.saveFloorRegions(gone, "1", ""));
        assertFalse(admin.addPhoto(gone, null, "m"));
        assertFalse(admin.movePhoto(gone, null, "m", 1));
        assertFalse(admin.removePhoto(gone, null, "m"));
        final AccommodationForm badContact = accommodationForm("Contact " + RandomData.genAlpha(5));
        badContact.setContactEmail("not-an-email");
        assertEquals(admin.saveAccommodation(badContact, orgId), "");
        final AccommodationForm badSite = accommodationForm("Site " + RandomData.genAlpha(5));
        badSite.setWebsite("has a space");
        assertEquals(admin.saveAccommodation(badSite, orgId), "");
        final AccommodationForm ghostEdit = accommodationForm("Ghost " + RandomData.genAlpha(5));
        ghostEdit.setId(gone);
        assertEquals(admin.saveAccommodation(ghostEdit, orgId), "", "No such hotel to edit (and no rights)");
        final LodgingCommands nobody = new LodgingCommands(
                () -> new Caller(null, false, org.paulsens.trip.audit.AuditActor.system(), new PrivilegeCommands()));
        assertEquals(nobody.saveAccommodation(accommodationForm("Anon " + RandomData.genAlpha(5)), orgId), "");

        // A room type with no rooms can go; a room with an active reservation cannot.
        final RoomTypeForm spare = new RoomTypeForm();
        spare.setName("Spare");
        assertTrue(admin.saveRoomType(stay.accId(), spare));
        final String spareId = admin.roomTypeRows(stay.accId()).stream()
                .filter(row -> row.getName().equals("Spare")).findFirst().orElseThrow().getId();
        assertTrue(admin.deleteRoomType(stay.accId(), spareId));

        // Real media rows: the gallery order is editable.
        final org.paulsens.trip.model.MediaItem m1 = mediaRow(stay.accId());
        final org.paulsens.trip.model.MediaItem m2 = mediaRow(stay.accId());
        assertTrue(admin.addPhoto(stay.accId(), null, m1.getId()));
        assertTrue(admin.addPhoto(stay.accId(), null, m2.getId()));
        assertTrue(admin.addPhoto(stay.accId(), null, m2.getId()), "Adding twice is a no-op");
        assertEquals(admin.galleryOf(stay.accId()).size(), 2);
        assertTrue(admin.movePhoto(stay.accId(), null, m2.getId(), -1));
        assertEquals(admin.galleryOf(stay.accId()).get(0).getId(), m2.getId());
        assertFalse(admin.movePhoto(stay.accId(), null, m2.getId(), -1), "Already first");
        assertFalse(admin.mediaUrl(m1.getId()).isEmpty());
        assertTrue(admin.removePhoto(stay.accId(), null, m1.getId()));
        assertEquals(admin.galleryOf(stay.accId()).size(), 1);
    }

    @Test
    public void offerValidationBranchesAndASecondOrgUsingTheHotelGetsTheContact() throws IOException {
        final Stay stay = stay(true);
        final OfferForm form = admin.offerFormFor(trip.getId(), stay.offer().getId().getValue());
        form.setSingleSupplement(-1.0);
        assertFalse(admin.saveOffer(trip.getId(), form));
        form.setSingleSupplement(0.0);
        form.setCancelFeeAmount(-1.0);
        assertFalse(admin.saveOffer(trip.getId(), form));
        form.setCancelFeeKind("PERCENT");
        form.setCancelFeeAmount(150.0);
        assertFalse(admin.saveOffer(trip.getId(), form), "Over 100%");
        form.setCancelFeeAmount(10.0);
        form.setPerNightPricing(true);
        form.getPerNight().put("2027-09-21", "-5");
        assertFalse(admin.saveOffer(trip.getId(), form));
        form.getPerNight().put("2027-09-21", "abc");
        assertFalse(admin.saveOffer(trip.getId(), form), "Not a price");
        form.getPerNight().put("2027-09-21", "5");
        form.setRoomTypeIds(new ArrayList<>(List.of("nope")));
        assertFalse(admin.saveOffer(trip.getId(), form), "Unknown room type");
        form.setRoomTypeIds(new ArrayList<>(List.of(stay.typeId())));
        form.setId(ReservationOffer.Id.newInstance().getValue());
        assertFalse(admin.saveOffer(trip.getId(), form), "Editing an offer that no longer exists");

        // An offer may point at a non-LODGING event (warned, allowed).
        final Trip edit = DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow();
        final String busId = edit.addTripEvent(TripEvent.Type.GROUND, "Bus", "", CHECK_IN, CHECK_IN.plusHours(2));
        assertTrue(DAO.getInstance().saveTrip(edit));
        final OfferForm onBus = admin.offerFormFor(trip.getId(), null);
        onBus.setName("On the bus");
        onBus.setAccommodationId(stay.accId());
        onBus.setRoomTypeIds(new ArrayList<>(List.of(stay.typeId())));
        onBus.setTripEventId(busId);
        onBus.setDefaultStart(CHECK_IN);
        onBus.setDefaultEnd(CHECK_OUT);
        assertTrue(admin.saveOffer(trip.getId(), onBus));

        // A second org's trip uses the hotel: the org is recorded on it and the contact joins that org.
        final AccommodationForm withContact = accommodationForm("Shared " + RandomData.genAlpha(5));
        withContact.setContactEmail("shared." + RandomData.genAlpha(6).toLowerCase() + "@example.com");
        withContact.setContactFirst("Marija");
        final String sharedId = admin.saveAccommodation(withContact, orgId);
        final RoomTypeForm type = new RoomTypeForm();
        type.setName("Double");
        assertTrue(admin.saveRoomType(sharedId, type));
        final OrgCommands orgs = new OrgCommands(TestCallers::siteAdmin);
        final Organization other = orgs.createOrganization("Other " + RandomData.genAlpha(6), "OTH", null);
        final Trip theirs = Trip.builder().id(java.util.UUID.randomUUID().toString()).title("Theirs")
                .startDate(CHECK_IN).endDate(CHECK_OUT).people(new ArrayList<>(List.of(bob.getId()))).build();
        theirs.setOrgId(other.getId().getValue());
        assertTrue(DAO.getInstance().saveTrip(theirs));
        final OfferForm theirOffer = admin.offerFormFor(theirs.getId(), null);
        theirOffer.setName("Their double");
        theirOffer.setAccommodationId(sharedId);
        theirOffer.setRoomTypeIds(new ArrayList<>(List.of(admin.roomTypeRows(sharedId).get(0).getId())));
        theirOffer.setDefaultStart(CHECK_IN);
        theirOffer.setDefaultEnd(CHECK_OUT);
        assertTrue(admin.saveOffer(theirs.getId(), theirOffer));
        final Accommodation shared = admin.findAccommodation(sharedId);
        assertTrue(shared.usedBy(other.getId()), "The second org is recorded on the hotel");
        assertTrue(orgs.isMember(other.getId().getValue(), shared.getContactId()), "...and its contact joined");
        assertTrue(admin.saveOffer(theirs.getId(), theirOffer), "Saving again records nothing new");
        assertEquals(admin.findAccommodation(sharedId).getOrgIds().size(), 2);

        // Deleting a room someone actively occupies is refused (the scan spans every trip using the hotel).
        final ReservationForm res = admin.reservationFormFor(theirs.getId(), null);
        res.setPersonIds(List.of(bob.getId().getValue()));
        assertEquals(admin.bulkAddRooms(sharedId, "", 1, 1, "1", theirOffer.getRoomTypeIds().get(0)), 1);
        final String room1 = admin.roomRows(sharedId).get(0).getId();
        res.setRoomId(room1);
        assertEquals(admin.createReservations(theirs.getId(), res), 1);
        assertFalse(admin.deleteRoom(sharedId, room1), "Occupied");
        assertTrue(admin.deleteRoom(stay.accId(), stay.r102()), "An empty room goes");
    }

    private static org.paulsens.trip.model.MediaItem mediaRow(final String accId) {
        final org.paulsens.trip.model.MediaItem item = new org.paulsens.trip.model.MediaItem(
                java.util.UUID.randomUUID().toString(), "lodging/" + accId + "/" + RandomData.genAlpha(6) + ".jpg",
                "Photo", null, "image/jpeg", 10L, LodgingUploadCommands.slotFor(accId), 1, LocalDateTime.now(),
                "test");
        assertTrue(DAO.getInstance().saveMedia(item));
        return item;
    }

    private Reservation reservationOf(final Person person, final String offerId) {
        return admin.activeReservationsFor(trip.getId(), person.getId()).stream()
                .filter(res -> res.getOfferId().getValue().equals(offerId)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ helpers

    /** Counts fixtures so each hotel's discoverable details differ; nanoTime modulo collided across a run. */
    private static final AtomicInteger FIXTURE_SEQ = new AtomicInteger();

    private static AccommodationForm accommodationForm(final String name) {
        final AccommodationForm form = new AccommodationForm();
        final int seq = FIXTURE_SEQ.incrementAndGet();
        form.setName(name);
        form.setEmail(name.replace(' ', '.').toLowerCase() + "@example.com");
        // Unique phone, street and website per hotel: discovery would otherwise flag a fixture as a
        // duplicate of an earlier one and refuse the create, failing whichever test happened to collide.
        form.setPhone("+387 36 " + seq + RandomData.genAlpha(4).toLowerCase().hashCode());
        form.setWebsite("https://" + name.replace(' ', '-').toLowerCase() + "-" + seq + ".example");
        form.setStreet("Podbrdo " + seq + " " + RandomData.genAlpha(5));
        form.setCity("Medjugorje");
        form.setZip("88266");
        form.setCountry("Bosnia and Herzegovina");
        return form;
    }

    private static Person savedPerson(final String first) throws IOException {
        final Person person = Person.builder().first(first).last(RandomData.genAlpha(8))
                .email(first.toLowerCase() + "." + RandomData.genAlpha(8).toLowerCase() + "@example.com")
                .birthdate(java.time.LocalDate.of(1990, 5, 5)).build();
        assertTrue(DAO.getInstance().savePerson(person));
        return person;
    }

    // ------------------------------------------------------------------ 2026-09-07 round: options and the board

    /** An option sells several room types, names itself after them, and keeps its default stay inside its dates. */
    @Test
    public void optionsSellSeveralRoomTypesNameThemselvesAndKeepTheDefaultStayInsideTheirDates() {
        final Stay stay = stay(false);
        final RoomTypeForm triple = new RoomTypeForm();
        triple.setName("Triple");
        triple.setMinPeople(1);
        triple.setMaxPeople(3);
        assertTrue(admin.saveRoomType(stay.accId(), triple));
        final String tripleId = admin.roomTypeRows(stay.accId()).get(1).getId();

        final OfferForm form = admin.offerFormFor(trip.getId(), null);
        assertEquals(form.getValidFrom(), trip.getStartDate().toLocalDate().atStartOfDay(),
                "a new option covers the trip's dates");
        assertEquals(form.getDefaultRange().size(), 2, "and the pickers are pre-filled");
        form.setAccommodationId(stay.accId());
        form.setRoomTypeIds(new ArrayList<>(List.of(stay.typeId(), tripleId)));
        admin.suggestOfferName(form);
        assertEquals(form.getName(), "Double / Triple room", "the name follows the room types while blank");
        form.setName("Keep me");
        admin.suggestOfferName(form);
        assertEquals(form.getName(), "Keep me", "a typed name is never overwritten");
        form.setName("");
        form.setNightlyPrice(80.0);
        // The default stay outside the option's dates is the bug of 2026-09-07: refused now.
        form.setDefaultStart(trip.getStartDate().minusDays(3));
        form.setDefaultEnd(trip.getStartDate().plusDays(1));
        assertFalse(admin.saveOffer(trip.getId(), form), "a default stay outside the option's dates is refused");
        form.setDefaultStart(CHECK_IN);
        form.setDefaultEnd(CHECK_OUT);
        assertTrue(admin.saveOffer(trip.getId(), form));
        final ReservationOffer offer = admin.getOffers(trip.getId()).stream()
                .filter(o -> o.getName().equals("Double / Triple room")).findFirst().orElseThrow();
        assertEquals(offer.getRoomTypeIds(), List.of(stay.typeId(), tripleId));
        assertTrue(offer.covers(tripleId));
        assertEquals(offer.firstRoomTypeId(), stay.typeId());
        assertEquals(admin.offerRows(trip.getId()).stream().filter(r -> r.getId().equals(offer.getId().getValue()))
                .findFirst().orElseThrow().getRoomTypeName(), "Double / Triple");
        // The pickers read back what the date-times say, and the dialog's shape writes the date-times.
        final OfferForm edit = admin.offerFormFor(trip.getId(), offer.getId().getValue());
        assertEquals(edit.getDefaultRange(), List.of(CHECK_IN.toLocalDate(), CHECK_OUT.toLocalDate()));
        assertEquals(edit.getArrivalTime(), CHECK_IN.toLocalTime());
        edit.setDefaultRange(List.of(CHECK_IN.toLocalDate().plusDays(1), CHECK_OUT.toLocalDate()));
        edit.setArrivalTime(java.time.LocalTime.of(16, 30));
        assertEquals(edit.getDefaultStart(), CHECK_IN.toLocalDate().plusDays(1).atTime(16, 30));
        assertTrue(admin.saveOffer(trip.getId(), edit));
        assertEquals(admin.findOffer(trip.getId(), offer.getId().getValue()).getDefaultStart(),
                CHECK_IN.toLocalDate().plusDays(1).atTime(16, 30));
        // Back to the shared fixture's dates: the trip's first option is what other tests' forms default from.
        edit.setDefaultStart(CHECK_IN);
        edit.setDefaultEnd(CHECK_OUT);
        assertTrue(admin.saveOffer(trip.getId(), edit));

        // A stay outside the option's dates is refused too, with the dates in the message.
        final ReservationForm res = admin.reservationFormFor(trip.getId(), null);
        res.setOfferId(offer.getId().getValue());
        res.setPersonIds(List.of(ada.getId().getValue()));
        res.setStart(trip.getStartDate().minusDays(2));
        res.setEnd(CHECK_OUT);
        assertEquals(admin.createReservations(trip.getId(), res), 0, "outside the option's dates");
        res.setRange(List.of(CHECK_IN.toLocalDate(), CHECK_OUT.toLocalDate()));
        res.setArrivalTime(java.time.LocalTime.of(14, 0));
        assertEquals(res.getStart(), CHECK_IN.toLocalDate().atTime(14, 0), "the range picker sets the dates");
        assertEquals(admin.createReservations(trip.getId(), res), 1);
    }

    /** Editing an option's price recomputes the bills of its reservations (no Recompute press needed). */
    @Test
    public void editingAnOptionRecomputesItsReservationsBills() {
        final Stay stay = stay(true);
        final ReservationForm res = admin.reservationFormFor(trip.getId(), null);
        res.setOfferId(stay.offer().getId().getValue());
        res.setPersonIds(List.of(cy.getId().getValue()));
        res.setStart(CHECK_IN);
        res.setEnd(CHECK_OUT);
        assertEquals(admin.createReservations(trip.getId(), res), 1);
        final Reservation cyRes = admin.activeReservationsFor(trip.getId(), cy.getId()).stream()
                .filter(r -> r.getOfferId().equals(stay.offer().getId())).findFirst().orElseThrow();
        final LodgingBiller biller = new LodgingBiller(new TransactionsCommands());
        assertEquals(biller.billedByPerson(cyRes), Map.of(cy.getId(), 3 * 12000L));

        final OfferForm edit = admin.offerFormFor(trip.getId(), stay.offer().getId().getValue());
        edit.setNightlyPrice(100.0);
        assertTrue(admin.saveOffer(trip.getId(), edit));
        assertEquals(biller.billedByPerson(cyRes), Map.of(cy.getId(), 3 * 10000L),
                "the bill follows the option's new price without an explicit recompute");
    }

    /**
     * One person, two reservations on disjoint dates (a valid shape): both stand on the board as their own
     * card, each is placed on its own, and the board counts both rooms because its window is the option's
     * whole date range, not the default stay.
     */
    @Test
    public void twoStaysForOnePersonAreTwoCardsPlacedSeparately() {
        final Stay stay = stay(true);
        final OfferForm widen = admin.offerFormFor(trip.getId(), stay.offer().getId().getValue());
        widen.setValidFrom(CHECK_IN.minusDays(10));
        widen.setValidUntil(CHECK_OUT.plusDays(10));
        widen.setMinNights(1);
        assertTrue(admin.saveOffer(trip.getId(), widen));
        final ReservationForm first = admin.reservationFormFor(trip.getId(), null);
        first.setOfferId(stay.offer().getId().getValue());
        first.setPersonIds(List.of(bob.getId().getValue()));
        first.setStart(CHECK_IN);
        first.setEnd(CHECK_OUT);
        assertEquals(admin.createReservations(trip.getId(), first), 1);
        final ReservationForm second = admin.reservationFormFor(trip.getId(), null);
        second.setOfferId(stay.offer().getId().getValue());
        second.setPersonIds(List.of(bob.getId().getValue()));
        second.setStart(CHECK_OUT.plusDays(3));
        second.setEnd(CHECK_OUT.plusDays(5));
        assertEquals(admin.createReservations(trip.getId(), second), 1);

        RoomBoard board = admin.roomBoard(trip.getId(), stay.accId(), null, null);
        final List<String> bobCards = board.getUnassigned().stream()
                .filter(c -> c.getPersonId().equals(bob.getId().getValue())).map(c -> c.getReservationId()).toList();
        assertEquals(bobCards.size(), 2, "two stays, two cards");
        assertNotEquals(bobCards.get(0), bobCards.get(1), "each card carries its own reservation");

        assertTrue(admin.assignRoom(trip.getId(), bobCards.get(0), stay.r101(), false, null, null).isAssigned());
        assertTrue(admin.assignRoom(trip.getId(), bobCards.get(1), stay.r102(), false, null, null).isAssigned());
        board = admin.roomBoard(trip.getId(), stay.accId(), null, null);
        assertEquals(board.getUnassigned().stream().filter(c -> c.getPersonId().equals(bob.getId().getValue()))
                .count(), 0L, "both placed");
        final RoomCell r101 = board.getRooms().stream().filter(c -> c.getRoomId().equals(stay.r101())).findFirst()
                .orElseThrow();
        final RoomCell r102 = board.getRooms().stream().filter(c -> c.getRoomId().equals(stay.r102())).findFirst()
                .orElseThrow();
        assertEquals(r101.getCount(), 1, "the first stay shows in 101");
        assertEquals(r102.getCount(), 1, "the second stay, on later dates, shows in 102 as well");
        assertEquals(admin.findReservation(trip.getId(), bobCards.get(1)).getRoomId(), stay.r102());
    }

    /** Toggling the single-supplement waiver on a solo reservation changes the bill, placed or not. */
    @Test
    public void waivingTheSupplementChangesTheBillEitherWay() {
        final Stay stay = stay(false);
        final OfferForm single = admin.offerFormFor(trip.getId(), null);
        single.setName("Solo " + RandomData.genAlpha(4));
        single.setAccommodationId(stay.accId());
        single.setRoomTypeIds(new ArrayList<>(List.of(stay.typeId())));
        single.setPricingModel("PER_PERSON");
        single.setNightlyPrice(70.0);
        single.setSingleSupplement(20.0);
        single.setDefaultStart(CHECK_IN);
        single.setDefaultEnd(CHECK_OUT);
        assertTrue(admin.saveOffer(trip.getId(), single));
        final ReservationOffer offer = admin.getOffers(trip.getId()).stream()
                .filter(o -> o.getName().equals(single.getName())).findFirst().orElseThrow();
        final ReservationForm res = admin.reservationFormFor(trip.getId(), null);
        res.setOfferId(offer.getId().getValue());
        res.setPersonIds(List.of(ada.getId().getValue()));
        res.setStart(CHECK_IN);
        res.setEnd(CHECK_OUT);
        assertEquals(admin.createReservations(trip.getId(), res), 1);
        final Reservation adaRes = admin.activeReservationsFor(trip.getId(), ada.getId()).stream()
                .filter(r -> r.getOfferId().equals(offer.getId())).findFirst().orElseThrow();
        final LodgingBiller biller = new LodgingBiller(new TransactionsCommands());
        assertEquals(biller.billedByPerson(adaRes), Map.of(ada.getId(), 3 * 9000L), "alone, unplaced: $70 + $20");

        final ReservationForm edit = admin.reservationFormFor(trip.getId(), adaRes.getId().getValue());
        edit.setWaiveSingleSupplement(true);
        assertTrue(admin.updateReservation(trip.getId(), edit));
        assertEquals(biller.billedByPerson(adaRes), Map.of(ada.getId(), 3 * 7000L), "waived: $70");
        final ReservationForm back = admin.reservationFormFor(trip.getId(), adaRes.getId().getValue());
        assertTrue(back.isWaiveSingleSupplement(), "the waiver is stored");
        back.setWaiveSingleSupplement(false);
        assertTrue(admin.updateReservation(trip.getId(), back));
        assertEquals(biller.billedByPerson(adaRes), Map.of(ada.getId(), 3 * 9000L), "reinstated: $90 again");
        // Placed alone in a room: still alone, still the supplement; placed with someone: gone.
        assertTrue(admin.assignRoom(trip.getId(), adaRes.getId().getValue(), stay.r101(), false, null, null)
                .isAssigned());
        assertEquals(biller.billedByPerson(adaRes), Map.of(ada.getId(), 3 * 9000L));
    }

    /**
     * A trip-scoped {@code lodgingManager} is the hotel's own staff: they room the trip's people and nothing
     * else. The board and both of its assignment commands answer to them; every command that touches an
     * option, a reservation or money refuses, and so does the same board on any OTHER trip.
     */
    @Test
    public void aHotelManagerRoomsPeopleAndNothingElse() throws IOException {
        // Its own trip: this test houses somebody, and the shared trip's rooming list is asserted elsewhere.
        final Person guest = savedPerson("Guest");
        final Person mate = savedPerson("Mate");
        final Trip hotelTrip = Trip.builder().id(java.util.UUID.randomUUID().toString()).title("Hotel mgr trip")
                .startDate(CHECK_IN).endDate(CHECK_OUT.plusDays(2))
                .people(new ArrayList<>(List.of(guest.getId(), mate.getId()))).build();
        hotelTrip.setOrgId(orgId);
        assertTrue(DAO.getInstance().saveTrip(hotelTrip));
        final Stay stay = stay(true, hotelTrip);
        final Person hotelier = savedPerson("Hotelier");
        final PrivilegeCommands priv = new PrivilegeCommands();
        assertTrue(priv.savePrivilege(priv.getOrCreate(PrivilegeCommands.LODGING_MANAGER, hotelTrip.getId(),
                "Rooms this trip").withNewPerson(hotelier.getId())));
        final LodgingCommands hotel = new LodgingCommands(() -> TestCallers.person(hotelier.getId()));

        assertTrue(hotel.canAssignRooms(hotelTrip.getId()));
        assertFalse(hotel.canManageTripLodging(hotelTrip.getId()), "rooming is not managing");
        assertFalse(hotel.canAssignRooms(null));
        assertFalse(hotel.canAssignRooms(" "));
        final Trip other = Trip.builder().id(java.util.UUID.randomUUID().toString()).title("Not theirs")
                .startDate(CHECK_IN).endDate(CHECK_OUT).build();
        other.setOrgId(orgId);
        assertTrue(DAO.getInstance().saveTrip(other));
        assertFalse(hotel.canAssignRooms(other.getId()), "the grant is one trip's");

        // The board is theirs, minus the column whose clicks would all be refused.
        final RoomBoard board = hotel.roomBoard(hotelTrip.getId(), stay.accId(), null, null);
        assertEquals(board.getAccommodationId(), stay.accId());
        assertFalse(board.getRooms().isEmpty());
        assertTrue(board.getNoReservation().isEmpty(),
                "placing somebody with no reservation picks the option that prices their stay");
        assertFalse(admin.roomBoard(hotelTrip.getId(), stay.accId(), null, null).getNoReservation().isEmpty(),
                "...which the trip's own managers still do");

        // Rooming: a reservation the trip made can be placed, moved and taken out again.
        final ReservationForm form = admin.reservationFormFor(hotelTrip.getId(), null);
        form.setOfferId(stay.offer().getId().getValue());
        form.setPersonIds(List.of(guest.getId().getValue()));
        form.setStart(CHECK_IN);
        form.setEnd(CHECK_OUT);
        assertEquals(admin.createReservations(hotelTrip.getId(), form), 1);
        final Reservation res = admin.activeReservationsFor(hotelTrip.getId(), guest.getId()).stream()
                .filter(r -> r.getRoomId() == null).findFirst().orElseThrow();
        final String resId = res.getId().getValue();
        assertTrue(hotel.assignRoom(hotelTrip.getId(), resId, stay.r101(), false, null, null).isAssigned());
        assertEquals(hotel.roomDetail(hotelTrip.getId(), stay.accId(), stay.r101(), null, null).getOccupants()
                .size(), 1, "and they can see who they just put in there");
        assertTrue(hotel.unassignRoom(hotelTrip.getId(), resId));

        // Everything with a price on it refuses.
        final PlacementForm placement = admin.placementFormFor(hotelTrip.getId(), stay.accId(),
                mate.getId().getValue(), stay.r102());
        placement.setOfferId(stay.offer().getId().getValue());
        assertFalse(hotel.place(hotelTrip.getId(), placement).isAssigned(), "creating a reservation is a price");
        assertFalse(hotel.updateReservation(hotelTrip.getId(), admin.reservationFormFor(hotelTrip.getId(), resId)));
        assertFalse(hotel.saveOffer(hotelTrip.getId(), admin.offerFormFor(hotelTrip.getId(),
                stay.offer().getId().getValue())));
        assertTrue(hotel.reservationRows(hotelTrip.getId(), false).isEmpty(), "no reservation list");
        assertNull(hotel.cancelPreview(hotelTrip.getId(), resId).getReservationId());
        assertFalse(hotel.cancelReservation(hotelTrip.getId(), resId, true, null, "no"));
        assertTrue(hotel.roomBoard(other.getId(), stay.accId(), null, null).getRooms().isEmpty());
        assertNull(hotel.roomDetail(other.getId(), stay.accId(), stay.r101(), null, null).getRoomId());
    }

    /**
     * The itinerary is ordered by the date each row SHOWS. A late arriver's hotel row carries her own
     * check-in, days after the group's, so ordering by the event put the hotel above the flight that brought
     * her to it -- the dates read right and the sequence read wrong (reported 2026-09-07).
     */
    @Test
    public void aLateArriversItineraryIsOrderedByTheDatesItShows() throws IOException {
        final Person rey = savedPerson("Rey");
        final Trip own = Trip.builder().id(java.util.UUID.randomUUID().toString()).title("Late arriver trip")
                .startDate(CHECK_IN).endDate(CHECK_OUT.plusDays(2))
                .people(new ArrayList<>(List.of(rey.getId()))).build();
        own.setOrgId(orgId);
        assertTrue(DAO.getInstance().saveTrip(own));
        final Stay stay = stay(true, own);

        // Her flight lands the day after the group checks in, so it falls INSIDE the hotel event.
        final Trip saved = DAO.getInstance().getTrip(own.getId(), Cached.NO).orElseThrow();
        final String flightId = saved.addTripEvent(TripEvent.Type.FLIGHT, "Late flight", "",
                CHECK_IN.plusDays(1).minusHours(3), CHECK_IN.plusDays(1).minusHours(1));
        assertTrue(DAO.getInstance().saveTrip(saved));
        final TripCommands trips = new TripCommands();
        assertTrue(trips.updateEventParticipants(DAO.getInstance().getTrip(own.getId(), Cached.NO).orElseThrow(),
                flightId, List.of(rey.getId()), List.of()));

        final ReservationForm late = admin.reservationFormFor(own.getId(), null);
        late.setOfferId(stay.offer().getId().getValue());
        late.setPersonIds(List.of(rey.getId().getValue()));
        late.setStart(CHECK_IN.plusDays(1));
        late.setEnd(CHECK_OUT);
        assertEquals(admin.createReservations(own.getId(), late), 1);

        final List<ItineraryRow> rows = admin.itineraryRowsFor(
                DAO.getInstance().getTrip(own.getId(), Cached.NO).orElseThrow(), rey.getId());
        assertEquals(rows.stream().map(ItineraryRow::getTitle).toList().indexOf("Late flight"), 0,
                "the flight that brought her comes first");
        final int hotel = rows.stream().map(ItineraryRow::getId).toList().indexOf(stay.eventId());
        assertEquals(hotel, 1, "the hotel follows, on HER check-in date rather than the group's");
        assertEquals(rows.get(hotel).getEffectiveStart(), CHECK_IN.plusDays(1));
        for (int i = 1; i < rows.size(); i++) {
            assertTrue(!rows.get(i).getEffectiveStart().isBefore(rows.get(i - 1).getEffectiveStart()),
                    "every row is on or after the one above it");
        }
    }

    /**
     * Clicking a room with nobody selected asks who is in it. The answer has to carry the registration
     * answers and both kinds of room note, because that is the whole reason to ask mid-assignment.
     */
    @Test
    public void aRoomTellsYouWhoIsInItAndWhatTheyAskedFor() throws IOException {
        // Its OWN trip and people: this test houses somebody, and the shared trip's rooming list and bills
        // are asserted elsewhere (fixture side effects here are load-bearing).
        final Person pat = savedPerson("Pat");
        final Person quinn = savedPerson("Quinn");
        final Trip own = Trip.builder().id(java.util.UUID.randomUUID().toString()).title("Room dialog trip")
                .startDate(CHECK_IN).endDate(CHECK_OUT.plusDays(2))
                .people(new ArrayList<>(List.of(pat.getId(), quinn.getId())))
                .regOptions(List.of(new RegistrationOption(1, "Dietary needs", "", true),
                        new RegistrationOption(2, "Roommate request", "", true),
                        new RegistrationOption(3, "Join the trip?", "", true),
                        new RegistrationOption(4, "Shirt size", "", true)))
                .build();
        own.setOrgId(orgId);
        assertTrue(DAO.getInstance().saveTrip(own));
        final Stay stay = stay(true, own);
        final Registration reg = new Registration(own.getId(), pat.getId());
        reg.getOptions().put("1", "Vegetarian");
        reg.getOptions().put("2", "With Cy please");
        reg.getOptions().put("3", "Yes");
        assertTrue(saveRegistration(reg));

        final RoomForm room = admin.roomFormFor(stay.accId(), stay.r101());
        room.setNotes("Mountain view");
        room.setAdminNotes("Radiator bangs");
        assertTrue(admin.saveRoom(stay.accId(), room));

        final ReservationForm shared = admin.reservationFormFor(own.getId(), null);
        shared.setOfferId(stay.offer().getId().getValue());
        shared.setPersonIds(List.of(pat.getId().getValue(), quinn.getId().getValue()));
        shared.setShareOneRoom(true);
        shared.setStart(CHECK_IN);
        shared.setEnd(CHECK_OUT);
        shared.setRoomId(stay.r101());
        assertEquals(admin.createReservations(own.getId(), shared), 1);

        final RoomDetail detail = admin.roomDetail(own.getId(), stay.accId(), stay.r101(), null, null);
        assertEquals(detail.getRoomNumber(), "101");
        assertEquals(detail.getTypeName(), "Double");
        assertEquals(detail.getFloor(), "1");
        assertEquals(detail.getCount(), 2);
        assertEquals(detail.getMaxPeople(), 2);
        assertEquals(detail.getState(), "rs-full");
        assertEquals(detail.getNotes(), "Mountain view");
        assertEquals(detail.getAdminNotes(), "Radiator bangs", "the private note is for the person assigning");
        assertEquals(detail.getOccupants().size(), 2, "both people on the shared reservation");
        final PersonCard card = detail.getOccupants().stream()
                .filter(c -> c.getPersonId().equals(pat.getId().getValue())).findFirst().orElseThrow();
        assertEquals(List.copyOf(card.getAnswers().keySet()), List.of("Roommate request", "Dietary needs"),
                "the roommate request leads; a 'Join' question and an unanswered one never show");
        assertEquals(card.getAnswers().get("Roommate request"), "With Cy please");
        assertEquals(card.getStart(), CHECK_IN, "the card carries the stay, not the option's default");

        final RoomDetail empty = admin.roomDetail(own.getId(), stay.accId(), stay.r102(), null, null);
        assertEquals(empty.getState(), "rs-empty");
        assertTrue(empty.getOccupants().isEmpty());
        assertEquals(admin.roomDetail(own.getId(), stay.accId(), "nope", null, null).getRoomId(), null);
        assertEquals(admin.roomDetail(own.getId(), stay.accId(), " ", null, null).getRoomId(), null);
        assertEquals(admin.roomDetail(own.getId(), "nope", stay.r101(), null, null).getRoomId(), null);
        final LodgingCommands outsider = new LodgingCommands(() -> TestCallers.person(ada.getId()));
        assertEquals(outsider.roomDetail(own.getId(), stay.accId(), stay.r101(), null, null).getRoomId(), null,
                "a room is only visible to somebody who manages this trip's lodging");
    }

    /**
     * The board belongs to the HOTEL, not to one lodging option. Somebody waiting for a room shows up
     * whichever option pays for their stay -- scoping the column to the selected option hid them (a person
     * on "Double room" vanished when the toolbar was switched to "Single room") while the room grid went on
     * showing the whole hotel.
     */
    @Test
    public void theBoardIsTheHotelsNotOneOptions() {
        final Stay stay = stay(true);
        final OfferForm second = admin.offerFormFor(trip.getId(), null);
        second.setName("Single " + RandomData.genAlpha(4));
        second.setAccommodationId(stay.accId());
        second.setRoomTypeIds(new ArrayList<>(List.of(stay.typeId())));
        second.setPricingModel("PER_PERSON");
        second.setNightlyPrice(70.0);
        second.setDefaultStart(CHECK_IN);
        second.setDefaultEnd(CHECK_OUT);
        assertTrue(admin.saveOffer(trip.getId(), second));
        final ReservationOffer single = admin.getOffers(trip.getId()).stream()
                .filter(o -> o.getName().equals(second.getName())).findFirst().orElseThrow();

        final ReservationForm onDouble = admin.reservationFormFor(trip.getId(), null);
        onDouble.setOfferId(stay.offer().getId().getValue());
        onDouble.setPersonIds(List.of(cy.getId().getValue()));
        onDouble.setStart(CHECK_IN);
        onDouble.setEnd(CHECK_OUT);
        assertEquals(admin.createReservations(trip.getId(), onDouble), 1);

        final RoomBoard board = admin.roomBoard(trip.getId(), stay.accId(), null, null);
        assertTrue(board.getUnassigned().stream().anyMatch(c -> c.getPersonId().equals(cy.getId().getValue())),
                "a person waiting for a room is on the board whatever option pays for them");
        assertEquals(board.getAccommodationId(), stay.accId());
        // Both options are offered for a placement at this hotel, and the hotel is one board.
        assertEquals(admin.optionChoices(trip.getId(), stay.accId()).size(), 2);
        assertTrue(admin.accommodationChoices(trip.getId()).containsKey(stay.accId()));
        assertEquals(admin.defaultAccommodationId(trip.getId()), admin.accommodationChoices(trip.getId())
                .keySet().iterator().next());
        assertEquals(admin.optionChoices(trip.getId(), "nope").size(), 0);

        // A placement names the option explicitly, and its stay follows that option.
        final PlacementForm form = admin.placementFormFor(trip.getId(), stay.accId(), bob.getId().getValue(),
                stay.r102());
        assertNull(form.getOfferId(), "two options: the dialog makes you choose");
        form.setOfferId(single.getId().getValue());
        admin.applyPlacementOption(trip.getId(), form);
        assertEquals(form.getRange(), List.of(CHECK_IN.toLocalDate(), CHECK_OUT.toLocalDate()));
        assertTrue(admin.place(trip.getId(), form).isAssigned());
        assertEquals(admin.activeReservationsFor(trip.getId(), bob.getId()).stream()
                .filter(r -> r.getOfferId().equals(single.getId())).count(), 1L, "billed on the option chosen");
    }

    /** Switching hotels keeps the floor you were on when the new one has it, rather than dropping to the first. */
    @Test
    public void theFloorSurvivesAHotelSwitchWhenItExistsThere() {
        final Stay stay = stay(false);
        assertEquals(admin.bulkAddRooms(stay.accId(), "", 201, 202, "2", stay.typeId()), 2);
        assertEquals(admin.floorFor(stay.accId(), "2"), "2", "the floor you are on is kept");
        assertEquals(admin.floorFor(stay.accId(), "9"), "1", "a floor this hotel lacks falls back to its first");
        assertEquals(admin.floorFor(stay.accId(), ""), "1");
        assertEquals(admin.floorFor("nope", "1"), "", "no hotel, no floor");
    }

    /**
     * The board's Assigned list (2026-09-11): everyone in a room, with the room; a selected assigned card
     * dropped on another room is a MOVE and says so; the same room again is a no-op; and the placement
     * dialog can waive the single supplement the way the reservation dialog can.
     */
    @Test
    public void theAssignedListMovesPeopleAndAPlacementCanWaiveTheSupplement() {
        final Stay stay = stay(true);
        final PlacementForm form = admin.placementFormFor(trip.getId(), stay.accId(), bob.getId().getValue(),
                stay.r101());
        form.setWaiveSingleSupplement(true);
        assertTrue(admin.place(trip.getId(), form).isAssigned());
        // The class shares one trip, so bob may hold stays from other scenarios: this one is on THIS offer.
        final Reservation res = admin.activeReservationsFor(trip.getId(), bob.getId()).stream()
                .filter(r -> stay.offer().getId().equals(r.getOfferId())).findFirst().orElseThrow();
        assertTrue(res.isSupplementWaived(), "the placement dialog's concession lands on the reservation");

        RoomBoard board = admin.roomBoard(trip.getId(), stay.accId(), null, null);
        assertEquals(board.getAssigned().size(), 1, "one card per placed stay");
        assertEquals(board.getAssigned().get(0).getRoomLabel(), "101", "and the card names the room");
        assertEquals(board.getAssigned().get(0).getReservationId(), res.getId().getValue());
        assertTrue(board.getUnassigned().isEmpty(), "a placed person is not also waiting");

        final AssignOutcome same = admin.assignRoom(trip.getId(), res.getId().getValue(), stay.r101(), false,
                null, null);
        assertTrue(same.isAssigned(), "the room they are in is not a refusal");
        final AssignOutcome moved = admin.assignRoom(trip.getId(), res.getId().getValue(), stay.r102(), false,
                null, null);
        assertTrue(moved.isAssigned());
        assertTrue(moved.getMessage().contains("moved from room 101 to room 102"), moved.getMessage());
        board = admin.roomBoard(trip.getId(), stay.accId(), null, null);
        assertEquals(board.getAssigned().get(0).getRoomLabel(), "102", "the move shows on the list");
        assertEquals(admin.findReservation(trip.getId(), res.getId().getValue()).getRoomId(), stay.r102());
    }
}
