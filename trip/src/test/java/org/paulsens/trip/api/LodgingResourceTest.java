package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.paulsens.trip.action.LodgingCommands;
import org.paulsens.trip.action.OrgCommands;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Reservation;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
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
        resource = resource(new LodgingResource());
    }

    @Test
    public void everyWriteNeedsTheCsrfHeaderAndEveryCallNeedsTheGate() {
        signedInAs(ada.getId());
        assertError(resource.createAccommodation(null, Map.of()), 403, ApiErrors.CSRF);
        assertError(resource.createOffer(trip.getId(), null, Map.of()), 403, ApiErrors.CSRF);
        assertError(resource.createReservations(trip.getId(), null, Map.of()), 403, ApiErrors.CSRF);
        assertError(resource.assignRoom(trip.getId(), "r", null, Map.of()), 403, ApiErrors.CSRF);
        // Ada is on the roster but manages nothing: refused everywhere.
        assertError(resource.createAccommodation(CSRF_OK, Map.of("name", "X")), 403, ApiErrors.FORBIDDEN);
        assertError(resource.accommodations(), 403, ApiErrors.FORBIDDEN);
        assertError(resource.createOffer(trip.getId(), CSRF_OK, Map.of()), 403, ApiErrors.FORBIDDEN);
        assertError(resource.createReservations(trip.getId(), CSRF_OK, Map.of()), 403, ApiErrors.FORBIDDEN);
        assertError(resource.reservations(trip.getId()), 403, ApiErrors.FORBIDDEN);
        assertError(resource.assignRoom(trip.getId(), "r", CSRF_OK, Map.of()), 403, ApiErrors.FORBIDDEN);
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
