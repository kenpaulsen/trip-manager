package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.paulsens.trip.action.BudgetViews;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.action.PrivilegeCommands;
import org.paulsens.trip.action.TripCommands;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PricingModel;
import org.paulsens.trip.model.RateSchedule;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.Trip;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * {@link BudgetResource}: the wire shape and the gates over the REAL bean against the fake store;
 * {@code BudgetCommandsTest} owns the budget rules themselves.
 */
public class BudgetResourceTest extends ResourceTestSupport {
    private static final LocalDateTime START = LocalDateTime.of(2027, 9, 14, 8, 0);

    private Trip trip;
    private ReservationOffer offer;

    @BeforeClass
    public void seed() throws IOException {
        FakeData.initFakeData();
        FakeData.addFakeData();
        trip = Trip.builder().id(UUID.randomUUID().toString()).title("Api budget trip").startDate(START)
                .endDate(START.plusDays(10)).build();
        assertTrue(DAO.getInstance().saveTrip(trip));
        offer = ReservationOffer.builder().tripId(trip.getId()).name("Double")
                .price(RateSchedule.builder().pricingModel(PricingModel.PER_PERSON).nightlyCents(60_00).build())
                .cost(RateSchedule.builder().pricingModel(PricingModel.PER_ROOM).nightlyCents(50_00).build())
                .build();
        assertTrue(DAO.getInstance().saveReservationOffer(offer));
    }

    @BeforeMethod
    public void bindBeans() {
        bind(PersonCommands.class, new PersonCommands());
        bind(TripCommands.class, new TripCommands());
        bind(PrivilegeCommands.class, new PrivilegeCommands());
    }

    @Test
    public void everyWriteNeedsTheCsrfHeaderAndTheEditGate() {
        signedInAs(Person.Id.newInstance());
        final BudgetResource stranger = resource(new BudgetResource());
        final String id = trip.getId();
        assertError(stranger.saveInputs(id, null, Map.of()), 403, ApiErrors.CSRF);
        assertError(stranger.createLine(id, null, Map.of()), 403, ApiErrors.CSRF);
        assertError(stranger.saveInputs(id, CSRF_OK, Map.of()), 403, ApiErrors.FORBIDDEN);
        assertError(stranger.createLine(id, CSRF_OK, Map.of()), 403, ApiErrors.FORBIDDEN);
        assertError(stranger.updateLine(id, "l", CSRF_OK, Map.of()), 403, ApiErrors.FORBIDDEN);
        assertError(stranger.deleteLine(id, "l", CSRF_OK), 403, ApiErrors.FORBIDDEN);
        assertError(stranger.createLodging(id, CSRF_OK, Map.of()), 403, ApiErrors.FORBIDDEN);
        assertError(stranger.updateLodging(id, "l", CSRF_OK, Map.of()), 403, ApiErrors.FORBIDDEN);
        assertError(stranger.deleteLodging(id, "l", CSRF_OK), 403, ApiErrors.FORBIDDEN);
        assertError(stranger.get(id), 403, ApiErrors.FORBIDDEN);
        assertError(stranger.pdf(id), 403, ApiErrors.FORBIDDEN);
        assertThrown(() -> stranger.get(UUID.randomUUID().toString()), 404, ApiErrors.NOT_FOUND);
        assertThrown(() -> stranger.deleteLine(UUID.randomUUID().toString(), "l", CSRF_OK), 404,
                ApiErrors.NOT_FOUND);
    }

    @Test
    public void aFinanceViewerReadsButCannotWrite() {
        final Person.Id viewer = Person.Id.newInstance();
        final PrivilegeCommands priv = new PrivilegeCommands();
        assertTrue(priv.savePrivilege(priv.getOrCreate(PrivilegeCommands.TRIP_FIN_VIEW, trip.getId(), "v")
                .withNewPerson(viewer)));
        signedInAs(viewer);
        final BudgetResource resource = resource(new BudgetResource());
        assertOk(resource.get(trip.getId()));
        final Response pdf = resource.pdf(trip.getId());
        assertEquals(pdf.getStatus(), 200);
        assertTrue(pdf.getHeaderString("Content-Disposition").endsWith("Api budget trip - Budget.pdf\""));
        assertError(resource.saveInputs(trip.getId(), CSRF_OK, Map.of("people", 3)), 403, ApiErrors.FORBIDDEN);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void anAdminBuildsABudgetOverTheWire() {
        signedInAsSiteAdmin(Person.Id.newInstance());
        final BudgetResource api = resource(new BudgetResource());
        final String id = trip.getId();
        assertOk(api.saveInputs(id, CSRF_OK, Map.of("people", 46, "tripNights", 10, "perPersonBudget", 400,
                "toBill", 1000)));
        assertError(api.saveInputs(id, CSRF_OK, Map.of("people", -1)), 400, ApiErrors.VALIDATION_FAILED);

        final Map<String, Object> guide = new HashMap<>(Map.of("kind", "SHARED", "category", "GENERAL",
                "name", "Guide", "amount", 1440, "payee", "Guide", "note", "incl. tip"));
        final Response created = api.createLine(id, CSRF_OK, guide);
        assertOk(created);
        final String lineId = (String) ((Map<?, ?>) created.getEntity()).get("id");
        assertFalse(lineId.isBlank());
        assertOk(api.createLine(id, CSRF_OK, Map.of("kind", "INDIVIDUAL", "name", "Snacks", "amount", 5,
                "perNight", true)));
        final Response refused = api.createLine(id, CSRF_OK, Map.of("kind", "SHARED", "name", " "));
        assertError(refused, 400, ApiErrors.VALIDATION_FAILED);
        guide.put("amount", 1500);
        final Response updated = api.updateLine(id, lineId, CSRF_OK, guide);
        assertEquals(((Map<?, ?>) updated.getEntity()).get("id"), lineId);

        final Response room = api.createLodging(id, CSRF_OK, Map.of("offerId", offer.getId().getValue(),
                "nights", 10, "people", 46, "rooms", 28));
        assertOk(room);
        final String roomId = (String) ((Map<?, ?>) room.getEntity()).get("id");
        assertError(api.createLodging(id, CSRF_OK, Map.of("offerId", "nope")), 400, ApiErrors.VALIDATION_FAILED);
        assertOk(api.updateLodging(id, roomId, CSRF_OK, Map.of("offerId", offer.getId().getValue(),
                "nights", 9, "rooms", 20)));

        final Response read = api.get(id);
        assertOk(read);
        final Map<String, Object> body = (Map<String, Object>) read.getEntity();
        assertEquals(((BudgetViews.InputsForm) body.get("inputs")).getPeople(), 46);
        assertEquals(((List<BudgetViews.LineForm>) body.get("lines")).get(0).getAmount(), 1500.0);
        assertEquals(((List<BudgetViews.LodgingLineForm>) body.get("lodging")).get(0).getNights(), 9);
        final BudgetViews.BudgetPage summary = (BudgetViews.BudgetPage) body.get("summary");
        assertEquals(summary.getSharedTotal(), "$1,500.00");
        assertEquals(summary.getLodgingRows().get(0).getRevenue(), "$24,840.00", "46 x 9 x $60");

        final Response percent = api.createLine(id, CSRF_OK, Map.of("kind", "SHARED", "category", "TIPS",
                "name", "Tips", "percentOf", lineId, "percent", 10, "onSite", false));
        assertOk(percent);
        final String percentId = (String) ((Map<?, ?>) percent.getEntity()).get("id");
        final BudgetViews.BudgetPage after = (BudgetViews.BudgetPage) ((Map<String, Object>) api.get(id).getEntity())
                .get("summary");
        assertEquals(after.getOtherRows().get(0).getAmount(), "$150.00", "10% of $1,500.00, not paid on site");
        assertError(api.deleteLine(id, lineId, CSRF_OK), 400, ApiErrors.VALIDATION_FAILED);
        assertOk(api.deleteLine(id, percentId, CSRF_OK));
        assertOk(api.deleteLine(id, lineId, CSRF_OK));
        assertOk(api.deleteLodging(id, roomId, CSRF_OK));
        assertError(api.deleteLine(id, null, CSRF_OK), 400, ApiErrors.VALIDATION_FAILED);
        assertError(api.deleteLodging(id, null, CSRF_OK), 400, ApiErrors.VALIDATION_FAILED);
    }

    @Test
    public void cloneNeedsTheGateAndAnEmptyBudget() throws IOException {
        signedInAsSiteAdmin(Person.Id.newInstance());
        final BudgetResource api = resource(new BudgetResource());
        final Trip target = Trip.builder().id(UUID.randomUUID().toString()).title("Api clone target").build();
        target.setOrgId(trip.getOrgId());
        assertTrue(DAO.getInstance().saveTrip(target));
        assertError(api.cloneFrom(target.getId(), null, Map.of()), 403, ApiErrors.CSRF);
        assertError(api.cloneFrom(target.getId(), CSRF_OK, Map.of("fromTripId", "nope")), 400,
                ApiErrors.VALIDATION_FAILED);
    }

    @Test
    public void versionedTypeIsTheBudgetType() {
        assertEquals(new BudgetResource().versionedType(), ApiMediaTypes.BUDGET_V1);
    }
}
