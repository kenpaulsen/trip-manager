package org.paulsens.trip.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.Mockito;
import org.paulsens.trip.action.Caller;
import org.paulsens.trip.action.ExpenseReceipts;
import org.paulsens.trip.action.PrivilegeCommands;
import org.paulsens.trip.action.TestCallers;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.media.PhotoFixtures;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/** The receipt servlet's answers: login, 404, 403, and the bytes with headers that keep them private. */
public class ExpenseReceiptServletTest {
    private static final byte[] PDF = "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    private HttpServletRequest req;
    private HttpServletResponse resp;
    private ByteArrayOutputStream out;
    private AtomicInteger status;
    private Caller caller;
    private final ExpenseReceiptServlet servlet = new ExpenseReceiptServlet() {
        @Override
        protected Caller callerOf(final HttpServletRequest request) {
            return caller;
        }
    };
    private String tripId;
    private String pdfId;
    private String photoId;

    @BeforeClass
    public void seed() throws IOException {
        FakeData.initFakeData();
        final Trip trip = Trip.builder().id(UUID.randomUUID().toString()).title("Receipts")
                .startDate(LocalDateTime.of(2026, 9, 20, 8, 0)).people(new ArrayList<>()).build();
        assertTrue(DAO.getInstance().saveTrip(trip));
        tripId = trip.getId();
        final ExpenseReceipts receipts = new ExpenseReceipts(TestCallers::siteAdmin);
        pdfId = receipts.store(tripId, null, PDF, "Cash advance \"slip\".pdf").mediaId();
        photoId = receipts.store(tripId, null, PhotoFixtures.jpeg(1200, 800), "bus.jpg").mediaId();
    }

    @BeforeMethod
    public void setUp() throws IOException {
        req = Mockito.mock(HttpServletRequest.class);
        final CapturedResponse captured = CapturedResponse.capture();
        resp = captured.response();
        out = captured.body();
        status = captured.status();
        caller = TestCallers.siteAdmin();
        Mockito.when(req.getContextPath()).thenReturn("");
    }

    private void get(final String pathInfo) throws IOException {
        Mockito.when(req.getPathInfo()).thenReturn(pathInfo);
        Mockito.when(req.getRequestURI()).thenReturn("/expense-receipts" + pathInfo);
        servlet.doGet(req, resp);
    }

    @Test
    public void aPdfStreamsInlineAndPrivately() throws IOException {
        get("/" + pdfId);
        assertEquals(status.get(), 200);
        assertEquals(out.toByteArray(), PDF);
        Mockito.verify(resp).setContentType("application/pdf");
        Mockito.verify(resp).setHeader("Cache-Control", "private, no-store");
        Mockito.verify(resp).setHeader("X-Content-Type-Options", "nosniff");
        Mockito.verify(resp).setHeader("Content-Disposition",
                "inline; filename=\"Cash advance 'slip'.pdf\"; filename*=UTF-8''Cash%20advance%20%22slip%22.pdf");
    }

    @Test
    public void aThumbnailIsTheSmallRendition() throws IOException {
        get("/" + photoId + "/thumb");
        assertEquals(status.get(), 200);
        Mockito.verify(resp).setContentType("image/jpeg");
        final MediaItem item = DAO.getInstance().getMedia(photoId, Cached.NO).orElseThrow();
        assertTrue(out.size() > 0 && out.size() < item.getSize(), "Smaller than the original");
    }

    @Test
    public void nobodySignedInGoesToLoginAndBack() throws IOException {
        caller = new Caller(null, false, AuditActor.system(), new PrivilegeCommands());
        get("/" + pdfId);
        Mockito.verify(resp).sendRedirect("/account/login.jsf?to=%2Fexpense-receipts%2F" + pdfId);
    }

    @Test
    public void someoneWhoCannotReadTheReportIsRefused() throws IOException {
        caller = new Caller(Person.Id.newInstance(), false, AuditActor.system(), new PrivilegeCommands());
        get("/" + pdfId);
        assertEquals(status.get(), 403);
        assertEquals(out.size(), 0);
    }

    @Test
    public void unknownPathsAndNonReceiptsAre404() throws IOException {
        for (final String path : new String[] {null, "/", "/" + pdfId + "/big", "/a/b/c", "/no-such-id"}) {
            status.set(200);
            get(path);
            assertEquals(status.get(), 404, String.valueOf(path));
        }
        final MediaItem library = new MediaItem(UUID.randomUUID().toString(), "downloads/x.pdf", "x", null,
                "application/pdf", 3, "home-docs", 0, LocalDateTime.now(), null, null, null, null);
        assertTrue(DAO.getInstance().saveMedia(library));
        status.set(200);
        get("/" + library.getId());
        assertEquals(status.get(), 404, "Library media is the CDN's, never this servlet's");
        final MediaItem lost = new MediaItem(UUID.randomUUID().toString(), "receipts/" + tripId + "/lost.pdf", "x",
                null, "application/pdf", 3, ExpenseReceipts.slotFor(tripId), 0, LocalDateTime.now(), null, null,
                Boolean.TRUE, null);
        assertTrue(DAO.getInstance().saveMedia(lost));
        status.set(200);
        get("/" + lost.getId());
        assertEquals(status.get(), 404, "A row whose bytes are gone");
    }

    @Test
    public void aBlankTitleStillNamesTheFile() {
        assertEquals(ExpenseReceiptServlet.inline(null), "inline; filename=\"receipt\"; filename*=UTF-8''receipt");
    }
}
