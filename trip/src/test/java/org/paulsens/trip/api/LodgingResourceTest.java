package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.paulsens.trip.action.LodgingCommands;
import org.paulsens.trip.action.LodgingViews;
import org.paulsens.trip.action.OrgCommands;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.action.RatePlanCommands;
import org.paulsens.trip.action.RatePlanViews;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Reservation;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * {@link LodgingResource}: the browser tests' fixture door. Runs against the fake store with the REAL command
 * bean (the resource constructs it from the request's caller), so what is asserted here is the wire shape and
 * the gates, not the lodging rules themselves -- {@code LodgingCommandsTest} owns those.
 */
public class LodgingResourceTest extends ResourceTestSupport {

    private static final LocalDateTime CHECK_IN = LocalDateTime.of(2027, 10, 4, 15, 0);
    private static final LocalDateTime CHECK_OUT = LocalDateTime.of(2027, 10, 8, 10, 0);

    private String orgId;
    private Person admin;
    private Person ada;
    private Person bob;
    private Person cy;
    private Trip trip;
    private LodgingResource resource;

    @BeforeClass
    public void seed() throws IOException {
        FakeData.initFakeData();
        FakeData.addFakeData();
        final OrgCommands orgs = new OrgCommands(LodgingResourceTest::siteAdmin);
        final Organization org = orgs.createOrganization("Api lodging " + RandomData.genAlpha(6), "ALG", null);
        assertNotNull(org);
        orgId = org.getId().getValue();
        admin = savedPerson("Admin");
        ada = savedPerson("Ada");
        bob = savedPerson("Bob");
        cy = savedPerson("Cy");
        trip = Trip.builder().id(UUID.randomUUID().toString()).title("Api lodging trip")
                .startDate(CHECK_IN).endDate(CHECK_OUT)
                .people(new ArrayList<>(List.of(ada.getId(), bob.getId(), cy.getId()))).build();
        trip.setOrgId(orgId);
        assertTrue(DAO.getInstance().saveTrip(trip));
    }

    @BeforeMethod
    public void bindBeans() {
        // The operators endpoint resolves its person through BaseResource.findPerson: the real bean, so a real
        // id is found and a made-up one is a 404 (a mock would answer null for both).
        bind(PersonCommands.class, new PersonCommands());
        resource = resource(new LodgingResource());
    }

    @Test
    public void everyWriteNeedsTheCsrfHeaderAndEveryCallNeedsTheGate() {
        signedInAs(ada.getId());
        assertError(resource.createAccommodation(null, Map.of()), 403, ApiErrors.CSRF);
        assertError(resource.createOffer(trip.getId(), null, Map.of()), 403, ApiErrors.CSRF);
        assertError(resource.createReservations(trip.getId(), null, Map.of()), 403, ApiErrors.CSRF);
        assertError(resource.assignRoom(trip.getId(), "r", null, Map.of()), 403, ApiErrors.CSRF);
        assertError(resource.createBlock("acc", null, Map.of()), 403, ApiErrors.CSRF);
        assertError(resource.addOperator("acc", null, Map.of()), 403, ApiErrors.CSRF);
        assertError(resource.removeOperator("acc", "p", null), 403, ApiErrors.CSRF);
        assertError(resource.setOfferCost(trip.getId(), "o", null, Map.of()), 403, ApiErrors.CSRF);
        assertError(resource.setConcession(trip.getId(), "r", "price", null, Map.of()), 403, ApiErrors.CSRF);
        assertError(resource.createRatePlan("acc", null, Map.of()), 403, ApiErrors.CSRF);
        assertError(resource.createOfferFromPlan(trip.getId(), null, Map.of()), 403, ApiErrors.CSRF);
        assertError(resource.detachOffer(trip.getId(), "o", null), 403, ApiErrors.CSRF);
        // Ada is on the roster but manages nothing: refused everywhere.
        assertError(resource.createAccommodation(CSRF_OK, Map.of("name", "X")), 403, ApiErrors.FORBIDDEN);
        assertError(resource.accommodations(), 403, ApiErrors.FORBIDDEN);
        assertError(resource.createOffer(trip.getId(), CSRF_OK, Map.of()), 403, ApiErrors.FORBIDDEN);
        assertError(resource.createReservations(trip.getId(), CSRF_OK, Map.of()), 403, ApiErrors.FORBIDDEN);
        assertError(resource.reservations(trip.getId()), 403, ApiErrors.FORBIDDEN);
        assertError(resource.assignRoom(trip.getId(), "r", CSRF_OK, Map.of()), 403, ApiErrors.FORBIDDEN);
        assertError(resource.createBlock("acc", CSRF_OK, Map.of()), 403, ApiErrors.FORBIDDEN);
        assertError(resource.calendar("acc", "2026-09"), 403, ApiErrors.FORBIDDEN);
        assertError(resource.addOperator("acc", CSRF_OK, Map.of("personId", bob.getId().getValue())), 403,
                ApiErrors.FORBIDDEN);
        assertError(resource.removeOperator("acc", bob.getId().getValue(), CSRF_OK), 403, ApiErrors.FORBIDDEN);
        assertError(resource.createRatePlan("acc", CSRF_OK, Map.of("name", "X")), 403, ApiErrors.FORBIDDEN);
        assertError(resource.ratePlans("acc"), 403, ApiErrors.FORBIDDEN);
        assertError(resource.createOfferFromPlan(trip.getId(), CSRF_OK, Map.of()), 403, ApiErrors.FORBIDDEN);
        assertError(resource.detachOffer(trip.getId(), "o", CSRF_OK), 403, ApiErrors.FORBIDDEN);
        // The two money endpoints answer 404 before 403 (the gate needs the row's hotel), so a stranger probing
        // a real row is what proves the gate: see theCostAndConcessionEndpointsFollowTheirGates.
        assertError(resource.setOfferCost(trip.getId(), "nope", CSRF_OK, Map.of()), 404, ApiErrors.NOT_FOUND);
        assertError(resource.setConcession(trip.getId(), "nope", "cost", CSRF_OK, Map.of()), 404,
                ApiErrors.NOT_FOUND);
        assertError(resource.setConcession(trip.getId(), "nope", "sideways", CSRF_OK, Map.of()), 400,
                ApiErrors.VALIDATION_FAILED);
    }

    /**
     * The cost endpoint is the operator's: allowed at their own hotel, refused at another, and it has no price
     * field to reach. The concessions endpoint follows its side: PRICE is the trip's alone, COST is also the
     * operator's; a bad kind is a 400 with the bean's own reason.
     */
    @Test
    public void theCostAndConcessionEndpointsFollowTheirGates() throws IOException {
        signedInAsSiteAdmin(admin.getId());
        final Person guest = savedPerson("Cga");
        final Person keeper = savedPerson("Cgk");
        final Trip own = Trip.builder().id(UUID.randomUUID().toString()).title("Cost gates trip")
                .startDate(CHECK_IN).endDate(CHECK_OUT).people(new ArrayList<>(List.of(guest.getId()))).build();
        own.setOrgId(orgId);
        assertTrue(DAO.getInstance().saveTrip(own));
        final String[] a = offerAtNewHotel(own.getId(), "Cost Pansion", "Double room");
        final String[] b = offerAtNewHotel(own.getId(), "Cost Berulia", "Double room");
        assertOk(resource.addOperator(a[0], CSRF_OK, Map.of("personId", keeper.getId().getValue())));
        final String resId = reservationId(resource.createReservations(own.getId(), CSRF_OK,
                Map.of("offerId", a[1], "personIds", List.of(guest.getId().getValue()))));
        final LodgingCommands lodging = new LodgingCommands(LodgingResourceTest::siteAdmin);

        signedInAs(keeper.getId());
        final LodgingResource asKeeper = resource(new LodgingResource());
        final Map<String, Object> cost = new HashMap<>();
        cost.put("costPricingModel", "PER_PERSON");
        cost.put("costNightlyPrice", 45);
        cost.put("costSupplement", 5);
        cost.put("costExtras", List.of(Map.of("label", "City tax", "basis", "PER_PERSON_NIGHT", "amount", 1.5)));
        final Response set = asKeeper.setOfferCost(own.getId(), a[1], CSRF_OK, cost);
        assertOk(set);
        assertEquals(((Map<?, ?>) set.getEntity()).get("costKnown"), true);
        final ReservationOffer offer = lodging.findOffer(own.getId(), a[1]);
        assertEquals(offer.getCost().getNightlyCents(), 4500L);
        assertEquals(offer.getCost().getSingleSupplementCents(), 500L);
        assertEquals(offer.getCostExtras().get(0).getAmountCents(), 150L);
        assertEquals(offer.getNightlyPriceCents(), 6000L, "the price is not this endpoint's to touch");
        assertError(asKeeper.setOfferCost(own.getId(), b[1], CSRF_OK, cost), 403, ApiErrors.FORBIDDEN,
                "not their hotel");
        assertError(asKeeper.setOfferCost(own.getId(), a[1], CSRF_OK, Map.of("costNightlyPrice", -1)), 400,
                ApiErrors.VALIDATION_FAILED);
        assertOk(asKeeper.setOfferCost(own.getId(), a[1], CSRF_OK, Map.of("costKnown", false)));
        assertNull(lodging.findOffer(own.getId(), a[1]).getCost(), "costKnown=false clears the stated cost");

        // Concessions: the operator records the hotel's, never the participant's.
        final Response conceded = asKeeper.setConcession(own.getId(), resId, "cost", CSRF_OK,
                Map.of("kind", "PERCENT", "amount", 10, "reason", "comped"));
        assertOk(conceded);
        assertEquals(((Map<?, ?>) conceded.getEntity()).get("label"), "10% discount");
        assertEquals(lodging.findReservation(own.getId(), resId).getCostConcession().getPercentBps(), 1000);
        assertError(asKeeper.setConcession(own.getId(), resId, "price", CSRF_OK, Map.of("kind", "COMPLIMENTARY")),
                403, ApiErrors.FORBIDDEN, "the price side is the trip's");
        assertNull(lodging.findReservation(own.getId(), resId).getPriceConcession());
        assertError(asKeeper.setConcession(own.getId(), resId, "cost", CSRF_OK, Map.of("kind", "PERCENT",
                "amount", 150)), 400, ApiErrors.VALIDATION_FAILED);
        final Response cleared = asKeeper.setConcession(own.getId(), resId, "cost", CSRF_OK, Map.of());
        assertOk(cleared);
        assertEquals(((Map<?, ?>) cleared.getEntity()).get("label"), "");
        assertNull(lodging.findReservation(own.getId(), resId).getCostConcession());

        // The trip's admin may grant the price side, and the bill follows.
        assertOk(resource.setConcession(own.getId(), resId, "PRICE", CSRF_OK, Map.of("kind", "TOTAL", "amount", 30)));
        assertEquals(lodging.findReservation(own.getId(), resId).getPriceConcession().getAmountCents(), 3000L);

        // A create may state the cost side up front, and may leave the price for later.
        final Map<String, Object> unpriced = new HashMap<>();
        unpriced.put("name", "Hotel first");
        unpriced.put("accommodationId", a[0]);
        unpriced.put("roomTypeIds", List.of(lodging.roomTypeRows(a[0]).get(0).getId()));
        unpriced.put("priceKnown", false);
        unpriced.put("costNightlyPrice", 40);
        unpriced.put("costExtras", List.of(Map.of("label", "Coupon", "basis", "FIXED", "amount", -5)));
        final Response created = resource.createOffer(own.getId(), CSRF_OK, unpriced);
        assertOk(created);
        final ReservationOffer later = lodging.findOffer(own.getId(),
                (String) ((Map<?, ?>) created.getEntity()).get("id"));
        assertFalse(later.isPriced(), "priceKnown=false is 'needs participant pricing'");
        assertEquals(later.getCost().getNightlyCents(), 4000L);
        assertEquals(later.getCostExtras().get(0).getAmountCents(), -500L);
        assertError(resource.createReservations(own.getId(), CSRF_OK, Map.of("offerId", later.getId().getValue(),
                "personIds", List.of(guest.getId().getValue()))), 400, ApiErrors.VALIDATION_FAILED);
    }

    /**
     * Rate plans over the API: an operator creates one at THEIR hotel (its price fields ignored, so the plan
     * needs participant pricing and the operator's rows carry no price column), the admin prices it, the trip
     * takes it as a linked option through the {@code createdOffer} diff, and detaches it again.
     */
    @Test
    public void ratePlansAreCreatedListedAttachedAndDetachedOverTheApi() throws IOException {
        signedInAsSiteAdmin(admin.getId());
        final Person keeper = savedPerson("Rpk");
        final Trip own = Trip.builder().id(UUID.randomUUID().toString()).title("Rate plan api trip")
                .startDate(CHECK_IN).endDate(CHECK_OUT).people(new ArrayList<>(List.of(ada.getId()))).build();
        own.setOrgId(orgId);
        assertTrue(DAO.getInstance().saveTrip(own));
        final String[] a = offerAtNewHotel(own.getId(), "Plan Pansion", "Standalone");
        final String[] b = offerAtNewHotel(own.getId(), "Plan Berulia", "Elsewhere");
        assertOk(resource.addOperator(a[0], CSRF_OK, Map.of("personId", keeper.getId().getValue())));
        final LodgingCommands lodging = new LodgingCommands(LodgingResourceTest::siteAdmin);
        final String typeId = lodging.roomTypeRows(a[0]).get(0).getId();

        signedInAs(keeper.getId());
        final LodgingResource asKeeper = resource(new LodgingResource());
        final Map<String, Object> body = new HashMap<>();
        body.put("name", "Hotel rate");
        body.put("roomTypeIds", List.of(typeId));
        body.put("validFrom", "2027-09-01");
        body.put("validUntil", "2027-10-31");
        body.put("costNightlyPrice", 50);
        body.put("costExtras", List.of(Map.of("label", "City tax", "basis", "FIXED", "amount", 12)));
        body.put("priceKnown", true);
        body.put("nightlyPrice", 999);
        final Response created = asKeeper.createRatePlan(a[0], CSRF_OK, body);
        assertOk(created);
        final String planId = (String) ((Map<?, ?>) created.getEntity()).get("id");
        assertFalse(planId.isEmpty(), "the minted id comes back");
        assertError(asKeeper.createRatePlan(b[0], CSRF_OK, body), 403, ApiErrors.FORBIDDEN, "not their hotel");
        assertError(asKeeper.createRatePlan(a[0], CSRF_OK, Map.of("name", "No rooms")), 400,
                ApiErrors.VALIDATION_FAILED);
        final Response keeperRows = asKeeper.ratePlans(a[0]);
        assertOk(keeperRows);
        final List<?> rows = (List<?>) keeperRows.getEntity();
        assertEquals(rows.size(), 1);
        final RatePlanViews.RatePlanRow row = (RatePlanViews.RatePlanRow) rows.get(0);
        assertEquals(row.getCostSummary(), "$50.00/night per room, +1 extra");
        assertNull(row.getPriceSummary(), "an operator is shown no price column");
        assertTrue(row.isNeedsPricing(), "the operator's $999 was ignored");
        assertError(asKeeper.createOfferFromPlan(own.getId(), CSRF_OK, Map.of("accId", a[0], "planId", planId)),
                403, ApiErrors.FORBIDDEN, "attaching is the trip's business");

        signedInAsSiteAdmin(admin.getId());
        final Response adminRows = resource.ratePlans(a[0]);
        assertOk(adminRows);
        assertEquals(((RatePlanViews.RatePlanRow) ((List<?>) adminRows.getEntity()).get(0)).getPriceSummary(),
                "needs participant pricing");
        final RatePlanCommands ratePlans = new RatePlanCommands(LodgingResourceTest::siteAdmin);
        final RatePlanViews.RatePlanForm price = ratePlans.planFormFor(a[0], planId);
        price.setPriceKnown(true);
        price.setNightlyPrice(70.0);
        assertTrue(ratePlans.savePlan(price));

        final Set<String> before = new HashSet<>();
        for (final ReservationOffer offer : lodging.getOffers(own.getId())) {
            before.add(offer.getId().getValue());
        }
        final Response attached = resource.createOfferFromPlan(own.getId(), CSRF_OK,
                Map.of("accId", a[0], "planId", planId));
        assertOk(attached);
        final Map<?, ?> attachedEntity = (Map<?, ?>) attached.getEntity();
        final String offerId = (String) attachedEntity.get("id");
        assertFalse(before.contains(offerId), "a NEW offer");
        assertNotNull(attachedEntity.get("tripEventId"), "with its LODGING event");
        final ReservationOffer linked = lodging.findOffer(own.getId(), offerId);
        assertEquals(linked.getRatePlanId(), planId);
        assertEquals(linked.getNightlyPriceCents(), 7000L, "the plan's price, copied");
        assertEquals(linked.getCost().getNightlyCents(), 5000L);
        assertError(resource.createOfferFromPlan(own.getId(), CSRF_OK, Map.of("accId", a[0], "planId", planId)),
                400, ApiErrors.VALIDATION_FAILED, "already on the trip");
        assertError(resource.createOfferFromPlan(own.getId(), CSRF_OK, Map.of("accId", a[0], "planId", "nope")),
                400, ApiErrors.VALIDATION_FAILED);

        assertError(resource.detachOffer(own.getId(), "nope", CSRF_OK), 404, ApiErrors.NOT_FOUND);
        assertError(resource.detachOffer(own.getId(), a[1], CSRF_OK), 400, ApiErrors.VALIDATION_FAILED,
                "a standalone option has nothing to detach from");
        assertOk(resource.detachOffer(own.getId(), offerId, CSRF_OK));
        final ReservationOffer loose = lodging.findOffer(own.getId(), offerId);
        assertFalse(loose.isLinked());
        assertEquals(loose.getNightlyPriceCents(), 7000L, "the numbers stay");
    }

    /**
     * The operators endpoint is the fixture door onto the Hotel Operator role, and the room endpoint answers
     * to that role: an operator rooms stays at THEIR hotel and is refused, with a 403 rather than a 409, at
     * any other hotel of the same trip.
     */
    @Test
    public void anOperatorAddedOverTheApiAssignsRoomsAtTheirHotelOnly() throws IOException {
        signedInAsSiteAdmin(admin.getId());
        final Person guestA = savedPerson("Opa");
        final Person guestB = savedPerson("Opb");
        final Person keeper = savedPerson("Opk");
        final Trip own = Trip.builder().id(UUID.randomUUID().toString()).title("Operator trip")
                .startDate(CHECK_IN).endDate(CHECK_OUT)
                .people(new ArrayList<>(List.of(guestA.getId(), guestB.getId()))).build();
        own.setOrgId(orgId);
        assertTrue(DAO.getInstance().saveTrip(own));
        final String[] a = offerAtNewHotel(own.getId(), "Operator Pansion", "Double room");
        final String[] b = offerAtNewHotel(own.getId(), "Operator Berulia", "Double room");
        assertError(resource.addOperator(a[0], CSRF_OK, Map.of("personId", "nobody")), 404, ApiErrors.NOT_FOUND);
        assertError(resource.addOperator(a[0], CSRF_OK, Map.of()), 404, ApiErrors.NOT_FOUND);
        assertError(resource.addOperator("no-such-hotel", CSRF_OK, Map.of("personId", keeper.getId().getValue())),
                404, ApiErrors.NOT_FOUND);
        final Response added = resource.addOperator(a[0], CSRF_OK, Map.of("personId", keeper.getId().getValue()));
        assertOk(added);
        assertEquals(((Map<?, ?>) added.getEntity()).get("ok"), true);
        final String atA = reservationId(resource.createReservations(own.getId(), CSRF_OK,
                Map.of("offerId", a[1], "personIds", List.of(guestA.getId().getValue()))));
        final String atB = reservationId(resource.createReservations(own.getId(), CSRF_OK,
                Map.of("offerId", b[1], "personIds", List.of(guestB.getId().getValue()))));
        final LodgingCommands lodging = new LodgingCommands(LodgingResourceTest::siteAdmin);
        final String roomA = lodging.roomRows(a[0]).get(0).getId();
        final String roomB = lodging.roomRows(b[0]).get(0).getId();

        // One resource serves one caller: a fresh one for the operator.
        signedInAs(keeper.getId());
        final LodgingResource asKeeper = resource(new LodgingResource());
        assertOk(asKeeper.assignRoom(own.getId(), atA, CSRF_OK, Map.of("roomId", roomA)));
        assertEquals(lodging.findReservation(own.getId(), atA).getRoomId(), roomA);
        assertError(asKeeper.assignRoom(own.getId(), atB, CSRF_OK, Map.of("roomId", roomB)), 403,
                ApiErrors.FORBIDDEN);
        assertNull(lodging.findReservation(own.getId(), atB).getRoomId(), "nothing moved at the other hotel");
        assertOk(asKeeper.assignRoom(own.getId(), atA, CSRF_OK, Map.of("roomId", "")));
        // Handing the role back over the API closes the door again at once (no per-request memo in a bare test).
        signedInAsSiteAdmin(admin.getId());
        assertError(resource.removeOperator("no-such-hotel", keeper.getId().getValue(), CSRF_OK), 404,
                ApiErrors.NOT_FOUND);
        assertOk(resource.removeOperator(a[0], keeper.getId().getValue(), CSRF_OK));
        signedInAs(keeper.getId());
        assertError(resource(new LodgingResource()).assignRoom(own.getId(), atA, CSRF_OK, Map.of("roomId", roomA)),
                403, ApiErrors.FORBIDDEN);
        assertError(asKeeper.reservations(own.getId()), 403, ApiErrors.FORBIDDEN, "still no reservation list");
        assertError(asKeeper.addOperator(b[0], CSRF_OK, Map.of("personId", bob.getId().getValue())), 403,
                ApiErrors.FORBIDDEN);
        assertOk(asKeeper.addOperator(a[0], CSRF_OK, Map.of("personId", bob.getId().getValue())));
        assertTrue(new LodgingCommands(() -> caller(bob.getId())).canEditAccommodation(a[0]),
                "an operator may appoint a colleague at their own hotel");
    }

    private String reservationId(final Response reserved) {
        assertOk(reserved);
        return (String) ((List<?>) ((Map<?, ?>) reserved.getEntity()).get("ids")).get(0);
    }

    private void assertError(final Response response, final int status, final String code, final String why) {
        assertEquals(response.getStatus(), status, why);
        assertError(response, status, code);
    }

    private static org.paulsens.trip.action.Caller caller(final Person.Id id) {
        return new org.paulsens.trip.action.Caller(id, false,
                new org.paulsens.trip.audit.AuditActor(id.getValue() + "@example.com", id.getValue()),
                new org.paulsens.trip.action.PrivilegeCommands());
    }

    /** Blocks and the hotel's calendar: the browser tests' door onto the availability feature. */
    @Test
    public void blocksAndTheCalendarRoundTrip() {
        signedInAsSiteAdmin(admin.getId());
        final Response created = resource.createAccommodation(CSRF_OK, accommodation("Block Pansion",
                List.of(Map.of("name", "Double", "minPeople", 1, "maxPeople", 2)),
                List.of(Map.of("number", "101", "floor", "1", "type", "Double"),
                        Map.of("number", "102", "floor", "1", "type", "Double"))));
        assertOk(created);
        final Map<?, ?> acc = (Map<?, ?>) created.getEntity();
        final String accId = (String) acc.get("id");
        final Map<?, ?> roomIds = (Map<?, ?>) acc.get("roomIds");

        assertError(resource.createBlock(accId, CSRF_OK, Map.of("roomIds", List.of())), 400,
                ApiErrors.VALIDATION_FAILED);
        final Map<String, Object> body = new HashMap<>();
        body.put("roomIds", List.of(roomIds.get("101")));
        body.put("start", "2027-09-22");
        body.put("end", "2027-09-25");
        body.put("reason", "another group");
        final Response blocked = resource.createBlock(accId, CSRF_OK, body);
        assertOk(blocked);
        final String blockId = (String) ((Map<?, ?>) blocked.getEntity()).get("id");
        assertTrue(blockId != null && !blockId.isBlank(), "the fixture needs the id back");

        final Response month = resource.calendar(accId, "2027-09");
        assertOk(month);
        final LodgingViews.HotelCalendar calendar = (LodgingViews.HotelCalendar) month.getEntity();
        assertEquals(calendar.getMonth(), "2027-09");
        assertEquals(calendar.getRoomsTotal(), 2);
        assertEquals(calendar.getDays().size(), 30);
        final LodgingViews.DayCell night = calendar.getDays().stream()
                .filter(d -> d.getDate().equals(java.time.LocalDate.of(2027, 9, 22))).findFirst().orElseThrow();
        assertEquals(night.getRoomsBlocked(), 1);
        assertEquals(night.getRoomsOccupied(), 0, "blocked is not occupied: nobody of ours is in it");
        assertOk(resource.calendar(accId, null));
    }

    @Test
    public void validationFailuresAnswer400() {
        signedInAsSiteAdmin(admin.getId());
        assertError(resource.createAccommodation(CSRF_OK, Map.of("name", " ")), 400, ApiErrors.VALIDATION_FAILED);
        // A nameless room type, then a room whose type does not exist.
        assertError(resource.createAccommodation(CSRF_OK, accommodation("Bad types",
                List.of(Map.of("name", " ")), List.of())), 400, ApiErrors.VALIDATION_FAILED);
        assertError(resource.createAccommodation(CSRF_OK, accommodation("Bad rooms",
                List.of(Map.of("name", "Double")), List.of(Map.of("number", "1", "floor", "1", "type", "Nope")))),
                400, ApiErrors.VALIDATION_FAILED);
        // An offer without an accommodation; a reservation on an unknown offer; a room on an unknown reservation.
        assertError(resource.createOffer(trip.getId(), CSRF_OK, Map.of("name", "Lonely")), 400,
                ApiErrors.VALIDATION_FAILED);
        assertError(resource.createReservations(trip.getId(), CSRF_OK, Map.of("offerId", "nope")), 404,
                ApiErrors.NOT_FOUND);
        assertError(resource.assignRoom(trip.getId(), "nope", CSRF_OK, Map.of("roomId", "x")), 404,
                ApiErrors.NOT_FOUND);
    }

    /**
     * Two hotels on one trip, each with an option of the SAME name, which is entirely legal. Each POST must
     * answer with the id of the offer IT created: the endpoint used to find that offer by name and take the
     * last match, so it could hand back the other hotel's, and the caller then reserved at the wrong hotel.
     */
    @Test
    public void eachCreatedOfferAnswersWithItsOwnIdEvenWhenTwoShareAName() {
        signedInAsSiteAdmin(admin.getId());
        final String[] first = offerAtNewHotel("Same Name Pansion", "Double room");
        final String[] second = offerAtNewHotel("Same Name Berulia", "Double room");
        assertNotEquals(first[1], second[1], "two hotels, two offers, two ids");

        // The proof that each id names the offer its OWN call created: the offer it points at is at the hotel
        // that call built. Checked by id rather than by name, which is the whole point.
        assertEquals(accommodationOf(first[1]), first[0], "the first id must name the first hotel's offer");
        assertEquals(accommodationOf(second[1]), second[0], "and the second id the second hotel's");
    }

    /** A fresh hotel with one Double room, and an offer on it named as asked: {accommodationId, offerId}. */
    private String[] offerAtNewHotel(final String hotel, final String offerName) {
        return offerAtNewHotel(trip.getId(), hotel, offerName);
    }

    private String[] offerAtNewHotel(final String tripId, final String hotel, final String offerName) {
        final Response created = resource.createAccommodation(CSRF_OK, accommodation(hotel,
                List.of(Map.of("name", "Double", "minPeople", 1, "maxPeople", 2)),
                List.of(Map.of("number", "101", "floor", "1", "type", "Double"))));
        assertOk(created);
        final Map<?, ?> acc = (Map<?, ?>) created.getEntity();
        final Map<?, ?> typeIds = (Map<?, ?>) acc.get("roomTypeIds");
        final Map<String, Object> body = new HashMap<>();
        body.put("name", offerName);
        body.put("accommodationId", acc.get("id"));
        body.put("roomTypeIds", List.of(typeIds.get("Double")));
        body.put("nightlyPrice", 60);
        body.put("defaultStart", CHECK_IN.toString());
        body.put("defaultEnd", CHECK_OUT.toString());
        final Response offer = resource.createOffer(tripId, CSRF_OK, body);
        assertOk(offer);
        final String id = (String) ((Map<?, ?>) offer.getEntity()).get("id");
        assertNotNull(id);
        return new String[] {(String) acc.get("id"), id};
    }

    private String accommodationOf(final String offerId) {
        final LodgingCommands lodging = new LodgingCommands(LodgingResourceTest::siteAdmin);
        return lodging.findOffer(trip.getId(), offerId).getAccommodationId().getValue();
    }

    @Test
    public void theWholeFixtureChainWorksEndToEnd() {
        signedInAsSiteAdmin(admin.getId());
        final Response created = resource.createAccommodation(CSRF_OK, accommodation("Api Pansion",
                List.of(Map.of("name", "Double", "minPeople", 1, "maxPeople", 2)),
                List.of(Map.of("number", "101", "floor", "1", "type", "Double"),
                        Map.of("number", "102", "floor", "1", "type", "Double"))));
        assertOk(created);
        final Map<?, ?> acc = (Map<?, ?>) created.getEntity();
        final String accId = (String) acc.get("id");
        final Map<?, ?> typeIds = (Map<?, ?>) acc.get("roomTypeIds");
        final Map<?, ?> roomIds = (Map<?, ?>) acc.get("roomIds");
        assertEquals(roomIds.size(), 2);
        assertOk(resource.accommodations());
        assertTrue(((List<?>) resource.accommodations().getEntity()).size() >= 1);

        final Map<String, Object> offerBody = new HashMap<>();
        offerBody.put("name", "Double room");
        offerBody.put("accommodationId", accId);
        offerBody.put("roomTypeIds", List.of(typeIds.get("Double")));
        offerBody.put("pricingModel", "PER_ROOM");
        offerBody.put("nightlyPrice", 60);
        offerBody.put("singleSupplement", 10);
        offerBody.put("minNights", 1);
        offerBody.put("defaultStart", CHECK_IN.toString());
        offerBody.put("defaultEnd", CHECK_OUT.toString());
        offerBody.put("cancelFeeKind", "PERCENT");
        offerBody.put("cancelFeeAmount", 10);
        final Response offer = resource.createOffer(trip.getId(), CSRF_OK, offerBody);
        assertOk(offer);
        final Map<?, ?> offerEntity = (Map<?, ?>) offer.getEntity();
        final String offerId = (String) offerEntity.get("id");
        assertNotNull(offerEntity.get("tripEventId"), "the offer creates the LODGING event by default");

        // A second offer reusing that event by id, with the defaults left to the bean.
        final Response second = resource.createOffer(trip.getId(), CSRF_OK, Map.of("name", "Second",
                "accommodationId", accId, "roomTypeId", typeIds.get("Double"),
                "tripEventId", offerEntity.get("tripEventId")));
        assertOk(second);

        final Response reserved = resource.createReservations(trip.getId(), CSRF_OK, Map.of("offerId", offerId,
                "personIds", List.of(ada.getId().getValue(), bob.getId().getValue()), "shareOneRoom", true,
                "start", CHECK_IN.toString(), "end", CHECK_OUT.toString(), "notes", "api"));
        assertOk(reserved);
        final Map<?, ?> reservedEntity = (Map<?, ?>) reserved.getEntity();
        assertEquals(reservedEntity.get("created"), 1);
        final List<?> ids = (List<?>) reservedEntity.get("ids");
        assertEquals(ids.size(), 1, "one SHARED reservation, listed once although two people are on it");
        final String resId = (String) ids.get(0);
        assertOk(resource.reservations(trip.getId()));

        // Nobody free of a reservation on that offer: the bean creates nothing.
        assertError(resource.createReservations(trip.getId(), CSRF_OK, Map.of("offerId", offerId,
                "personIds", List.of())), 400, ApiErrors.VALIDATION_FAILED);

        final String room101 = (String) roomIds.get("101");
        assertOk(resource.assignRoom(trip.getId(), resId, CSRF_OK, Map.of("roomId", room101)));
        final LodgingCommands lodging = new LodgingCommands(LodgingResourceTest::siteAdmin);
        assertEquals(lodging.findReservation(trip.getId(), resId).getRoomId(), room101);
        // A third guest on the second offer over-fills the Double: refused unless forced, then cleared again.
        final Response overflow = resource.createReservations(trip.getId(), CSRF_OK, Map.of("offerId",
                (String) ((Map<?, ?>) second.getEntity()).get("id"), "personIds", List.of(cy.getId().getValue())));
        assertOk(overflow);
        final String cyRes = (String) ((List<?>) ((Map<?, ?>) overflow.getEntity()).get("ids")).get(0);
        assertError(resource.assignRoom(trip.getId(), cyRes, CSRF_OK, Map.of("roomId", room101)), 409,
                ApiErrors.CONFLICT);
        assertNull(lodging.findReservation(trip.getId(), cyRes).getRoomId());
        assertOk(resource.assignRoom(trip.getId(), cyRes, CSRF_OK, Map.of("roomId", room101, "force", true)));
        assertEquals(lodging.findReservation(trip.getId(), cyRes).getRoomId(), room101);
        assertOk(resource.assignRoom(trip.getId(), cyRes, CSRF_OK, Map.of("roomId", "")));
        assertNull(lodging.findReservation(trip.getId(), cyRes).getRoomId());
        final Reservation shared = lodging.findReservation(trip.getId(), resId);
        assertFalse(shared.getOccupants().isEmpty());
        assertError(resource.assignRoom(trip.getId(), cyRes, CSRF_OK, Map.of("roomId", "not-a-room")), 409,
                ApiErrors.CONFLICT);
    }

    private Map<String, Object> accommodation(final String name, final List<Map<String, Object>> types,
            final List<Map<String, Object>> rooms) {
        final Map<String, Object> body = new HashMap<>();
        body.put("name", name + " " + RandomData.genAlpha(5));
        body.put("email", RandomData.genAlpha(8).toLowerCase() + "@example.com");
        body.put("phone", "+387 36 " + (System.nanoTime() % 1_000_000L));
        body.put("street", "Podbrdo " + (System.nanoTime() % 100_000L));
        body.put("city", "Medjugorje");
        body.put("country", "Bosnia and Herzegovina");
        body.put("orgId", orgId);
        body.put("roomTypes", types);
        body.put("rooms", rooms);
        return body;
    }

    /** The action package's test caller is package-private; a site admin is a two-line Caller anyway. */
    private static org.paulsens.trip.action.Caller siteAdmin() {
        return new org.paulsens.trip.action.Caller(Person.Id.from("api-site-admin"), true,
                new org.paulsens.trip.audit.AuditActor("site-admin@example.com", "api-site-admin"),
                new org.paulsens.trip.action.PrivilegeCommands());
    }

    private static Person savedPerson(final String first) throws IOException {
        final Person person = Person.builder().first(first).last(RandomData.genAlpha(8))
                .email(first.toLowerCase() + "." + RandomData.genAlpha(8).toLowerCase() + "@example.com").build();
        assertTrue(DAO.getInstance().savePerson(person));
        return person;
    }
}
