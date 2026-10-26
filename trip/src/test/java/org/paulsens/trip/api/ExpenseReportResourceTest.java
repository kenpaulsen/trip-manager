package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.paulsens.trip.action.ExpenseViews;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.action.PhotoUploadBean;
import org.paulsens.trip.action.PrivilegeCommands;
import org.paulsens.trip.action.TripCommands;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * {@link ExpenseReportResource}: the wire shape and the gates over the REAL bean against the fake store;
 * {@code ExpenseCommandsTest} owns the report's rules and {@code ExpenseReceiptsTest} the receipts'.
 */
public class ExpenseReportResourceTest extends ResourceTestSupport {
    private static final byte[] PDF = "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    private Trip trip;

    @BeforeClass
    public void seed() throws IOException {
        FakeData.initFakeData();
        FakeData.addFakeData();
        trip = Trip.builder().id(UUID.randomUUID().toString()).title("Api expense trip")
                .startDate(LocalDateTime.of(2026, 9, 20, 8, 0)).build();
        assertTrue(DAO.getInstance().saveTrip(trip));
    }

    @BeforeMethod
    public void bindBeans() {
        bind(PersonCommands.class, new PersonCommands());
        bind(TripCommands.class, new TripCommands());
        bind(PrivilegeCommands.class, new PrivilegeCommands());
    }

    private static InputStream body(final byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    @Test
    public void everyWriteNeedsTheCsrfHeaderAndTheEditGate() {
        signedInAs(Person.Id.newInstance());
        final ExpenseReportResource stranger = resource(new ExpenseReportResource());
        final String id = trip.getId();
        assertError(stranger.saveHeader(id, null, Map.of()), 403, ApiErrors.CSRF);
        assertError(stranger.saveHeader(id, CSRF_OK, Map.of()), 403, ApiErrors.FORBIDDEN);
        assertError(stranger.submit(id, CSRF_OK), 403, ApiErrors.FORBIDDEN);
        assertError(stranger.unsubmit(id, CSRF_OK), 403, ApiErrors.FORBIDDEN);
        assertError(stranger.createCurrency(id, CSRF_OK, Map.of()), 403, ApiErrors.FORBIDDEN);
        assertError(stranger.updateCurrency(id, "EUR", CSRF_OK, Map.of()), 403, ApiErrors.FORBIDDEN);
        assertError(stranger.deleteCurrency(id, "EUR", CSRF_OK), 403, ApiErrors.FORBIDDEN);
        assertError(stranger.createLine(id, CSRF_OK, Map.of()), 403, ApiErrors.FORBIDDEN);
        assertError(stranger.updateLine(id, "l", CSRF_OK, Map.of()), 403, ApiErrors.FORBIDDEN);
        assertError(stranger.deleteLine(id, "l", CSRF_OK), 403, ApiErrors.FORBIDDEN);
        assertError(stranger.uploadReceipt(id, CSRF_OK, 3L, null, "x.pdf", body(PDF)), 403, ApiErrors.FORBIDDEN);
        assertError(stranger.deleteReceipt(id, "r", CSRF_OK), 403, ApiErrors.FORBIDDEN);
        assertError(stranger.get(id), 403, ApiErrors.FORBIDDEN);
        assertError(stranger.pdf(id, false), 403, ApiErrors.FORBIDDEN);
        assertError(stranger.emailTreasurer(id, CSRF_OK), 403, ApiErrors.FORBIDDEN);
        assertError(stranger.emailTreasurer(id, null), 403, ApiErrors.CSRF);
        assertThrown(() -> stranger.get(UUID.randomUUID().toString()), 404, ApiErrors.NOT_FOUND);
    }

    @Test
    public void aFinanceViewerReadsButCannotWrite() {
        final Person.Id viewer = Person.Id.newInstance();
        final PrivilegeCommands priv = new PrivilegeCommands();
        assertTrue(priv.savePrivilege(priv.getOrCreate(PrivilegeCommands.TRIP_FIN_VIEW, trip.getId(), "v")
                .withNewPerson(viewer)));
        signedInAs(viewer);
        final ExpenseReportResource resource = resource(new ExpenseReportResource());
        assertOk(resource.get(trip.getId()));
        assertError(resource.createLine(trip.getId(), CSRF_OK, Map.of("description", "x", "amount", 1)), 403,
                ApiErrors.FORBIDDEN);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void anAdminBuildsAReportOverTheWire() throws IOException {
        signedInAsSiteAdmin(Person.Id.newInstance());
        final ExpenseReportResource api = resource(new ExpenseReportResource());
        final Trip own = Trip.builder().id(UUID.randomUUID().toString()).title("Wire report")
                .startDate(LocalDateTime.of(2026, 9, 20, 8, 0)).build();
        assertTrue(DAO.getInstance().saveTrip(own));
        final String id = own.getId();

        assertError(api.submit(id, CSRF_OK), 400, ApiErrors.VALIDATION_FAILED);
        assertOk(api.saveHeader(id, CSRF_OK, Map.of("submittedBy", "Ken", "cashAdvance", 45765,
                "paymentInstructions", "Zelle", "paidOn", "2026-10-20")));
        assertError(api.saveHeader(id, CSRF_OK, Map.of("paidOn", "Oct 20")), 400, ApiErrors.VALIDATION_FAILED);
        assertError(api.saveHeader(id, CSRF_OK, Map.of("cashAdvance", -1)), 400, ApiErrors.VALIDATION_FAILED);

        final Response eur = api.createCurrency(id, CSRF_OK, Map.of("code", "eur", "rate", 0.83));
        assertEquals(((Map<?, ?>) eur.getEntity()).get("code"), "EUR");
        assertError(api.createCurrency(id, CSRF_OK, Map.of("code", "USD", "rate", 1)), 400,
                ApiErrors.VALIDATION_FAILED);
        assertOk(api.updateCurrency(id, "EUR", CSRF_OK, Map.of("rate", 0.85, "note", "tuned")));

        final Map<String, Object> candles = new HashMap<>(Map.of("date", "2026-09-25", "category", "SUPPLIES",
                "description", "Candles", "currency", "EUR", "amount", 23));
        final Response created = api.createLine(id, CSRF_OK, candles);
        assertOk(created);
        final String lineId = (String) ((Map<?, ?>) created.getEntity()).get("id");
        assertOk(api.createLine(id, CSRF_OK, Map.of("description", "Van", "currency", "BAM", "rate", 1.66,
                "amount", 800)));
        assertOk(api.createLine(id, CSRF_OK, Map.of("description", "Guide", "amount", 1440)));
        assertError(api.createLine(id, CSRF_OK, Map.of("description", " ")), 400, ApiErrors.VALIDATION_FAILED);
        assertError(api.createLine(id, CSRF_OK, Map.of("description", "x", "amount", 1, "date", "soon")), 400,
                ApiErrors.VALIDATION_FAILED);
        candles.put("amount", 25);
        assertEquals(((Map<?, ?>) api.updateLine(id, lineId, CSRF_OK, candles).getEntity()).get("id"), lineId);
        assertError(api.deleteCurrency(id, "EUR", CSRF_OK), 400, ApiErrors.VALIDATION_FAILED);

        final Response uploaded = api.uploadReceipt(id, CSRF_OK, (long) PDF.length, lineId, "candles.pdf",
                body(PDF));
        assertOk(uploaded);
        final Map<String, Object> receipt = (Map<String, Object>) uploaded.getEntity();
        final String receiptId = (String) receipt.get("id");
        assertTrue(((String) receipt.get("url")).endsWith("/expense-receipts/" + receiptId));
        assertOk(api.uploadReceipt(id, CSRF_OK, null, null, "advance.pdf", body(PDF)));
        final byte[] text = "text".getBytes(StandardCharsets.UTF_8);
        assertError(api.uploadReceipt(id, CSRF_OK, null, null, "a.txt", body(text)), 400,
                ApiErrors.VALIDATION_FAILED);
        assertError(api.uploadReceipt(id, CSRF_OK, PhotoUploadBean.MAX_UPLOAD_BYTES + 1, null, "big", body(PDF)),
                413, ApiErrors.PAYLOAD_TOO_LARGE);
        assertOk(api.submit(id, CSRF_OK));

        final Map<String, Object> got = (Map<String, Object>) api.get(id).getEntity();
        assertEquals(((ExpenseViews.HeaderForm) got.get("header")).getSubmittedBy(), "Ken");
        assertEquals(((List<?>) got.get("currencies")).size(), 2, "EUR, and BAM added with its line");
        assertEquals(((List<?>) got.get("lines")).size(), 3);
        final List<Map<String, Object>> receipts = (List<Map<String, Object>>) got.get("receipts");
        assertEquals(receipts.size(), 2);
        assertNull(receipts.get(0).get("lineId"), "Report level first");
        assertEquals(receipts.get(1).get("lineId"), lineId);
        final ExpenseViews.ExpensePage summary = (ExpenseViews.ExpensePage) got.get("summary");
        assertEquals(summary.getStatus(), "Paid", "paidOn was set");
        assertEquals(summary.getTotal(), "$1,951.34", "25 / 0.85 + 800 / 1.66 + 1,440");

        final Response pdf = api.pdf(id, true);
        assertEquals(pdf.getStatus(), 200);
        assertEquals(new String((byte[]) pdf.getEntity(), 0, 5, StandardCharsets.US_ASCII), "%PDF-");
        assertTrue(pdf.getHeaderString("Content-Disposition").contains("Wire report - Expense Report.pdf"));
        assertError(api.emailTreasurer(id, CSRF_OK), 400, ApiErrors.VALIDATION_FAILED);
        assertOk(api.deleteReceipt(id, receiptId, CSRF_OK));
        assertError(api.deleteReceipt(id, receiptId, CSRF_OK), 400, ApiErrors.VALIDATION_FAILED);
        assertOk(api.unsubmit(id, CSRF_OK));
        assertOk(api.deleteLine(id, lineId, CSRF_OK));
        assertOk(api.deleteCurrency(id, "EUR", CSRF_OK));
        assertFalse(DAO.getInstance().getTripExpenseReport(id, Cached.NO).orElseThrow()
                .getLines().isEmpty());
    }
}
