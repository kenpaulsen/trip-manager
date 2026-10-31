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
import org.paulsens.trip.action.TransactionsCommands;
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
import static org.testng.Assert.assertNotNull;
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
        assertError(stranger.createPayment(id, CSRF_OK, Map.of()), 403, ApiErrors.FORBIDDEN);
        assertError(stranger.createPayment(id, null, Map.of()), 403, ApiErrors.CSRF);
        assertError(stranger.updatePayment(id, "p", CSRF_OK, Map.of()), 403, ApiErrors.FORBIDDEN);
        assertError(stranger.deletePayment(id, "p", CSRF_OK), 403, ApiErrors.FORBIDDEN);
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
    public void aTripManagerEditsButRecordsNoPayment() throws IOException {
        final Person.Id manager = Person.Id.newInstance();
        final PrivilegeCommands priv = new PrivilegeCommands();
        assertTrue(priv.savePrivilege(priv.getOrCreate(PrivilegeCommands.TRIP_MGR, trip.getId(), "m")
                .withNewPerson(manager)));
        signedInAs(manager);
        final ExpenseReportResource resource = resource(new ExpenseReportResource());
        assertOk(resource.createLine(trip.getId(), CSRF_OK, Map.of("description", "Stamps", "amount", 5)));
        final Response refused = resource.createPayment(trip.getId(), CSRF_OK, Map.of("date", "2026-10-20",
                "amount", 5, "method", "Zelle"));
        assertError(refused, 403, ApiErrors.FORBIDDEN);
        assertTrue(String.valueOf(refused.getEntity()).contains("Finance admin required."));
        final Person giver = Person.builder().first("Pat").last("Giver").build();
        assertTrue(DAO.getInstance().savePerson(giver));
        trip.getPeople().add(giver.getId());
        assertTrue(DAO.getInstance().saveTrip(trip));
        final Response given = resource.createAdvance(trip.getId(), CSRF_OK, Map.of("type", "PARTICIPANT",
                "amount", 40, "personIds", List.of(giver.getId().getValue()), "recordTransaction", true));
        assertOk(given);
        assertNull(((Map<?, ?>) given.getEntity()).get("transactionId"), "a manager's tick is ignored");
        assertTrue(new TransactionsCommands().getTransactions(giver.getId()).isEmpty());
        final String advanceId = (String) ((Map<?, ?>) given.getEntity()).get("id");
        assertOk(resource.deleteAdvance(trip.getId(), advanceId, CSRF_OK));
        assertError(resource.deleteAdvance(trip.getId(), advanceId, null), 403, ApiErrors.CSRF);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void anImageReceiptRotatesOverTheWireAndAPdfIsRefused() throws IOException {
        signedInAsSiteAdmin(Person.Id.newInstance());
        final ExpenseReportResource api = resource(new ExpenseReportResource());
        final Trip own = Trip.builder().id(UUID.randomUUID().toString()).title("Rotate report")
                .startDate(LocalDateTime.of(2026, 9, 20, 8, 0)).build();
        assertTrue(DAO.getInstance().saveTrip(own));
        final String id = own.getId();
        final byte[] photo = org.paulsens.trip.media.PhotoFixtures.jpeg(900, 600);
        final String receiptId = (String) ((Map<String, Object>) api.uploadReceipt(id, CSRF_OK, null, null,
                "van.jpg", body(photo)).getEntity()).get("id");
        final String pdfId = (String) ((Map<String, Object>) api.uploadReceipt(id, CSRF_OK, null, null, "a.pdf",
                body(PDF)).getEntity()).get("id");
        final org.paulsens.trip.api.dto.RotateRequest right = new org.paulsens.trip.api.dto.RotateRequest("right");

        final Response rotated = api.rotateReceipt(id, receiptId, CSRF_OK, right);

        assertOk(rotated);
        final Map<String, Object> body = (Map<String, Object>) rotated.getEntity();
        assertEquals(body.get("id"), receiptId);
        assertEquals(body.get("title"), "van.jpg");
        assertEquals(body.get("pdf"), false);
        assertEquals(body.get("rev"), 1);
        assertTrue(((String) body.get("url")).endsWith("/expense-receipts/" + receiptId + "?r=1"), body.toString());
        assertTrue(((String) body.get("thumbUrl")).endsWith("/expense-receipts/" + receiptId + "/thumb?r=1"));
        final List<Map<String, Object>> listed = (List<Map<String, Object>>) ((Map<String, Object>) api.get(id)
                .getEntity()).get("receipts");
        assertTrue(listed.stream().anyMatch(r -> String.valueOf(r.get("url")).endsWith(receiptId + "?r=1")),
                "the report's own listing carries the buster too");

        assertError(api.rotateReceipt(id, pdfId, CSRF_OK, right), 422, ApiErrors.VALIDATION_FAILED);
        assertError(api.rotateReceipt(id, "no-such", CSRF_OK, right), 404, ApiErrors.NOT_FOUND);
        assertError(api.rotateReceipt(id, receiptId, CSRF_OK, null), 400, ApiErrors.VALIDATION_FAILED);
        assertError(api.rotateReceipt(id, receiptId, CSRF_OK, new org.paulsens.trip.api.dto.RotateRequest("up")),
                400, ApiErrors.VALIDATION_FAILED);
        assertError(api.rotateReceipt(id, receiptId, null, right), 403, ApiErrors.CSRF);
        assertTrue(org.paulsens.trip.media.RotationGuard.begin(receiptId));
        try {
            assertError(api.rotateReceipt(id, receiptId, CSRF_OK, right), 409, ApiErrors.CONFLICT);
        } finally {
            org.paulsens.trip.media.RotationGuard.end(receiptId);
        }
        signedInAs(Person.Id.newInstance());
        assertError(resource(new ExpenseReportResource()).rotateReceipt(id, receiptId, CSRF_OK, right), 403,
                ApiErrors.FORBIDDEN);
    }

    @Test
    public void aReceiptThatCannotBeStoredAfterTurningIsA500() throws IOException {
        signedInAsSiteAdmin(Person.Id.newInstance());
        final Trip own = Trip.builder().id(UUID.randomUUID().toString()).title("Rotate fail")
                .startDate(LocalDateTime.of(2026, 9, 20, 8, 0)).build();
        assertTrue(DAO.getInstance().saveTrip(own));
        final org.paulsens.trip.model.MediaItem gone = new org.paulsens.trip.model.MediaItem(
                UUID.randomUUID().toString(), "receipts/" + own.getId() + "/gone.jpg", "gone", null, "image/jpeg", 3,
                org.paulsens.trip.action.ExpenseReceipts.slotFor(own.getId()), 0, LocalDateTime.now(), null, null,
                Boolean.TRUE, null);
        assertTrue(DAO.getInstance().saveMedia(gone));
        assertError(resource(new ExpenseReportResource()).rotateReceipt(own.getId(), gone.getId(), CSRF_OK,
                new org.paulsens.trip.api.dto.RotateRequest("left")), 500, ApiErrors.STORE_FAILED);
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
        assertOk(api.saveHeader(id, CSRF_OK, Map.of("submittedBy", "Ken", "paymentInstructions", "Zelle")));
        assertError(api.saveHeader(id, CSRF_OK, Map.of("submittedBy", "n".repeat(121))), 400,
                ApiErrors.VALIDATION_FAILED);
        final Response advance = api.createAdvance(id, CSRF_OK, Map.of("type", "CASH", "amount", 45765,
                "party", "CFPW", "date", "2026-09-19"));
        final String advanceId = (String) ((Map<?, ?>) advance.getEntity()).get("id");
        assertNotNull(advanceId);
        assertNull(((Map<?, ?>) advance.getEntity()).get("transactionId"));
        assertError(api.createAdvance(id, CSRF_OK, Map.of("type", "CASH", "amount", -1)), 400,
                ApiErrors.VALIDATION_FAILED);
        assertError(api.createAdvance(id, CSRF_OK, Map.of("type", "CASH", "amount", 1, "date", "yesterday")), 400,
                ApiErrors.VALIDATION_FAILED);
        assertOk(api.updateAdvance(id, advanceId, CSRF_OK, Map.of("type", "CASH", "amount", 45765, "party", "CFPW",
                "note", "check 1001")));

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
        final Response guide = api.createLine(id, CSRF_OK, Map.of("description", "Guide", "amount", 1440,
                "paidStatus", "ORG"));
        assertOk(guide);
        final String guideId = (String) ((Map<?, ?>) guide.getEntity()).get("id");
        assertError(api.createLine(id, CSRF_OK, Map.of("description", "x", "amount", 1, "paidStatus", "x")), 400,
                ApiErrors.VALIDATION_FAILED);
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
        final List<ExpenseViews.ExpenseForm> lines = (List<ExpenseViews.ExpenseForm>) got.get("lines");
        assertEquals(lines.size(), 3);
        assertEquals(lines.get(2).getPaidStatus(), "ORG");
        assertEquals(guideId, lines.get(2).getId());
        final List<Map<String, Object>> receipts = (List<Map<String, Object>>) got.get("receipts");
        assertEquals(receipts.size(), 2);
        assertNull(receipts.get(0).get("lineId"), "Report level first");
        assertEquals(receipts.get(1).get("lineId"), lineId);
        final ExpenseViews.ExpensePage summary = (ExpenseViews.ExpensePage) got.get("summary");
        assertEquals(summary.getStatus(), "Submitted");
        assertEquals(summary.getTotal(), "$1,951.34", "25 / 0.85 + 800 / 1.66 + 1,440");
        assertEquals(summary.getOutOfPocket(), "$511.34", "The org-paid guide is not out of pocket");

        // Payments: the candles in one, the rest of the balance in another.
        final Response paid = api.createPayment(id, CSRF_OK, Map.of("date", "2026-10-20", "amount", 29.41,
                "method", "Zelle", "note", "Candles", "lineIds", List.of(lineId)));
        assertOk(paid);
        final String paymentId = (String) ((Map<?, ?>) paid.getEntity()).get("id");
        assertError(api.createPayment(id, CSRF_OK, Map.of("date", "someday", "amount", 1, "method", "x")), 400,
                ApiErrors.VALIDATION_FAILED);
        assertError(api.createPayment(id, CSRF_OK, Map.of("date", "2026-10-20", "amount", 1)), 400,
                ApiErrors.VALIDATION_FAILED);
        assertOk(api.updatePayment(id, paymentId, CSRF_OK, Map.of("date", "2026-10-21", "amount", 29.41,
                "method", "Zelle", "lineIds", List.of(lineId))));
        final Map<String, Object> after = (Map<String, Object>) api.get(id).getEntity();
        final List<ExpenseViews.PaymentForm> payments = (List<ExpenseViews.PaymentForm>) after.get("payments");
        assertEquals(payments.size(), 1);
        assertEquals(payments.get(0).getLineIds(), List.of(lineId));
        assertEquals(((ExpenseViews.ExpensePage) after.get("summary")).getStatus(), "Partly paid");
        assertError(api.deleteLine(id, lineId, CSRF_OK), 400, ApiErrors.VALIDATION_FAILED);
        assertOk(api.deletePayment(id, paymentId, CSRF_OK));

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
