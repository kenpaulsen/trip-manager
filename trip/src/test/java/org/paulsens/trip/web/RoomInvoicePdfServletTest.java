package org.paulsens.trip.web;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.Caller;
import org.paulsens.trip.action.LodgingCommands;
import org.paulsens.trip.action.LodgingViews;
import org.paulsens.trip.action.LodgingViews.AccommodationForm;
import org.paulsens.trip.action.LodgingViews.OfferForm;
import org.paulsens.trip.action.LodgingViews.ReservationForm;
import org.paulsens.trip.action.LodgingViews.RoomTypeForm;
import org.paulsens.trip.action.OrgCommands;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.action.PrivilegeCommands;
import org.paulsens.trip.action.ReportCommands;
import org.paulsens.trip.action.TripCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Privilege;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The Room Invoice PDF download: who gets the file, who is sent to login, who is refused, and the headers that
 * make it a download with the trip's name on it. A Hotel Operator of a hotel in play is admitted to THEIR
 * hotel's part; a finance viewer gets every hotel; an invoice refused for want of a hotel cost is a 409.
 */
public class RoomInvoicePdfServletTest {

    private static final String TRIP_ID = UUID.randomUUID().toString();

    private HttpServletRequest req;
    private HttpServletResponse resp;
    private ByteArrayOutputStream out;
    private AtomicInteger status;
    private RoomInvoicePdfServlet servlet;

    @BeforeClass
    void beforeClass() {
        FakeData.initFakeData();
    }

    @BeforeMethod
    public void setUp() throws IOException {
        req = Mockito.mock(HttpServletRequest.class);
        resp = Mockito.mock(HttpServletResponse.class);
        out = new ByteArrayOutputStream();
        status = new AtomicInteger(200);
        servlet = new RoomInvoicePdfServlet();
        final Trip trip = Trip.builder().id(TRIP_ID).title("Spring Trip").build();
        servlet.setSources(id -> TRIP_ID.equals(id) ? trip : Trip.builder().build(),
                (id, access, finance) -> new ReportCommands.RoomInvoice());
        Mockito.when(req.getRequestURI()).thenReturn("/reports/room-invoice");
        Mockito.when(req.getContextPath()).thenReturn("");
        final ServletOutputStream sink = new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(final WriteListener listener) {
            }

            @Override
            public void write(final int b) {
                out.write(b);
            }
        };
        Mockito.when(resp.getOutputStream()).thenReturn(sink);
        Mockito.doAnswer(call -> recordStatus(call.getArgument(0))).when(resp).sendError(ArgumentMatchers.anyInt());
    }

    private Object recordStatus(final int code) {
        status.set(code);
        return null;
    }

    private void signedIn(final String personId, final String role, final String tripId) {
        final HttpSession session = Mockito.mock(HttpSession.class);
        Mockito.when(session.getAttribute(PersonCommands.ACTIVE_USER_ID)).thenReturn(personId);
        Mockito.when(session.getAttribute(PersonCommands.ACTIVE_USER_ROLE)).thenReturn(role);
        Mockito.when(req.getSession(false)).thenReturn(session);
        Mockito.when(req.getParameter("trip")).thenReturn(tripId);
    }

    @Test
    public void aSiteAdminDownloadsThePdfNamedForTheTrip() throws IOException {
        signedIn(UUID.randomUUID().toString(), "admin", TRIP_ID);
        servlet.doGet(req, resp);
        Assert.assertEquals(status.get(), 200);
        Mockito.verify(resp).setContentType("application/pdf");
        Mockito.verify(resp).setHeader("Content-Disposition",
                "attachment; filename=\"Spring Trip - Room Invoice.pdf\"; "
                        + "filename*=UTF-8''Spring%20Trip%20-%20Room%20Invoice.pdf");
        Mockito.verify(resp).setHeader("Cache-Control", "private, no-store");
        Assert.assertEquals(new String(out.toByteArray(), 0, 5, java.nio.charset.StandardCharsets.US_ASCII),
                "%PDF-");
    }

    @Test
    public void aTripFinanceViewerMayDownloadIt() throws IOException {
        final Person.Id viewer = Person.Id.newInstance();
        DAO.getInstance().savePrivilege(new Privilege(Privilege.idFor(PrivilegeCommands.TRIP_FIN_VIEW, TRIP_ID),
                "test", List.of(viewer)));
        signedIn(viewer.getValue(), "user", TRIP_ID);
        servlet.doGet(req, resp);
        Assert.assertEquals(status.get(), 200);
        Assert.assertTrue(out.size() > 0);
    }

    /**
     * Two hotels on one trip, an operator of hotel A: the servlet admits them on their lodging access alone,
     * builds the invoice through the real {@code roomInvoiceFor}, and the PDF names A and not B, addressed to
     * the trip's organization. The finance viewer's PDF has both. While hotel B's option has no cost the
     * finance viewer (whose view is the whole trip) gets a 409 naming it, and the operator of A still gets A.
     */
    @Test
    public void anOperatorGetsTheirHotelsPartAndAFinanceViewerGetsEveryHotel() throws IOException {
        final LodgingCommands admin = new LodgingCommands(RoomInvoicePdfServletTest::siteAdmin);
        final Organization org = new OrgCommands(RoomInvoicePdfServletTest::siteAdmin)
                .createOrganization("Invoice " + RandomData.genAlpha(8), "INV", null);
        Assert.assertNotNull(org);
        final Person guestA = savedPerson("Ava");
        final Person guestB = savedPerson("Ben");
        final Trip trip = Trip.builder().id(UUID.randomUUID().toString()).title("Two Hotels")
                .startDate(CHECK_IN).endDate(CHECK_OUT.plusDays(2))
                .people(new ArrayList<>(List.of(guestA.getId(), guestB.getId()))).build();
        trip.setOrgId(org.getId().getValue());
        Assert.assertTrue(DAO.getInstance().saveTrip(trip));
        final String hotelA = hotel(admin, org.getId().getValue(), "Alpha House");
        final String hotelB = hotel(admin, org.getId().getValue(), "Bravo Inn");
        reserve(admin, trip.getId(), offerAt(admin, trip.getId(), hotelA, true), guestA);
        final String offerB = offerAt(admin, trip.getId(), hotelB, false);
        reserve(admin, trip.getId(), offerB, guestB);
        final Person operator = savedPerson("Keeper");
        final PrivilegeCommands priv = new PrivilegeCommands();
        Assert.assertTrue(priv.savePrivilege(priv.getOrCreate(PrivilegeCommands.ACCOMMODATION_ADMIN, hotelA,
                PrivilegeCommands.ACCOMMODATION_ROLE_NAME).withNewPerson(operator.getId())));
        final ReportCommands reports = new ReportCommands(TripCommands::new, LodgingCommands::new);
        servlet.setSources(id -> DAO.getInstance().getTrip(id, Cached.NO).orElse(Trip.builder().build()),
                reports::roomInvoiceFor);

        signedIn(operator.getId().getValue(), "user", trip.getId());
        servlet.doGet(req, resp);
        Assert.assertEquals(status.get(), 200, "an operator of a hotel in play is admitted");
        final String theirs = text(out.toByteArray());
        Assert.assertTrue(theirs.contains("Alpha House"), theirs);
        Assert.assertFalse(theirs.contains("Bravo Inn"), "the other hotel is not theirs: " + theirs);
        Assert.assertTrue(theirs.contains("Ava"), "guest names at their hotel are theirs to see");
        Assert.assertTrue(theirs.contains("Invoice to " + org.getName() + " for Two Hotels room charges at Alpha"),
                "addressed to the trip's organization: " + theirs);

        // The finance viewer's view is the whole trip, and hotel B has no cost yet: refused, in their words.
        out.reset();
        final Person viewer = savedPerson("Fin");
        DAO.getInstance().savePrivilege(new Privilege(Privilege.idFor(PrivilegeCommands.TRIP_FIN_VIEW, trip.getId()),
                "test", List.of(viewer.getId())));
        signedIn(viewer.getId().getValue(), "user", trip.getId());
        servlet.doGet(req, resp);
        Mockito.verify(resp).setStatus(HttpServletResponse.SC_CONFLICT);
        Mockito.verify(resp).setContentType("text/plain;charset=UTF-8");
        final String refusal = new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        Assert.assertTrue(refusal.startsWith("No hotel cost is set for Room at "), refusal);
        Assert.assertTrue(refusal.contains("Offers tab"), "the internal wording: " + refusal);
        Assert.assertFalse(refusal.contains("%PDF"), "no PDF with a hole in it");

        // Once B states its cost, every hotel is theirs.
        final LodgingViews.OfferCostForm costB = admin.offerCostFormFor(trip.getId(), offerB);
        costB.setCostKnown(true);
        costB.setCostNightlyPrice(80.0);
        Assert.assertTrue(admin.setOfferCost(costB));
        out.reset();
        servlet.doGet(req, resp);
        Assert.assertEquals(status.get(), 200);
        final String everything = text(out.toByteArray());
        Assert.assertTrue(everything.contains("Alpha House") && everything.contains("Bravo Inn"), everything);
        Assert.assertFalse(everything.contains("Cost assumed"), everything);

        out.reset();
        final Person stranger = savedPerson("Nobody");
        signedIn(stranger.getId().getValue(), "user", trip.getId());
        servlet.doGet(req, resp);
        Assert.assertEquals(status.get(), HttpServletResponse.SC_FORBIDDEN, "signed in, operates nothing here");
        Assert.assertEquals(out.size(), 0);
    }

    @Test
    public void someoneWithoutAFinancePrivilegeIsRefused() throws IOException {
        signedIn(UUID.randomUUID().toString(), "user", TRIP_ID);
        servlet.doGet(req, resp);
        Assert.assertEquals(status.get(), HttpServletResponse.SC_FORBIDDEN);
        Assert.assertEquals(out.size(), 0, "no money leaves without the privilege");
    }

    @Test
    public void aVisitorIsSentToLoginAndBroughtBack() throws IOException {
        Mockito.when(req.getSession(false)).thenReturn(null);
        Mockito.when(req.getQueryString()).thenReturn("trip=" + TRIP_ID);
        servlet.doGet(req, resp);
        Mockito.verify(resp).sendRedirect("/account/login.jsf?to=%2Freports%2Froom-invoice%3Ftrip%3D" + TRIP_ID);
        Assert.assertEquals(out.size(), 0);
    }

    @Test
    public void aVisitorWithNoQueryIsStillSentToLogin() throws IOException {
        Mockito.when(req.getSession(false)).thenReturn(null);
        servlet.doGet(req, resp);
        Mockito.verify(resp).sendRedirect("/account/login.jsf?to=%2Freports%2Froom-invoice");
    }

    @Test
    public void anUnknownOrMissingTripIsNotFound() throws IOException {
        signedIn(UUID.randomUUID().toString(), "admin", "no-such-trip");
        servlet.doGet(req, resp);
        Assert.assertEquals(status.get(), HttpServletResponse.SC_NOT_FOUND);
        status.set(200);
        signedIn(UUID.randomUUID().toString(), "admin", " ");
        servlet.doGet(req, resp);
        Assert.assertEquals(status.get(), HttpServletResponse.SC_NOT_FOUND);
    }

    @Test
    public void aNonAsciiFileNameKeepsBothForms() {
        Assert.assertEquals(RoomInvoicePdfServlet.disposition("Međugorje \"A\".pdf"),
                "attachment; filename=\"Me_ugorje 'A'.pdf\"; filename*=UTF-8''Me%C4%91ugorje%20%22A%22.pdf");
    }

    // ------------------------------------------------------------------ fixtures

    private static final LocalDateTime CHECK_IN = LocalDateTime.of(2027, 11, 2, 15, 0);
    private static final LocalDateTime CHECK_OUT = LocalDateTime.of(2027, 11, 5, 10, 0);

    /** The fixture's writer: a site administrator, so the fixture is not itself a test of authorization. */
    private static Caller siteAdmin() {
        final Person.Id id = Person.Id.from("test-site-admin");
        return new Caller(id, true, new AuditActor("site-admin@example.com", id.getValue()), new PrivilegeCommands());
    }

    private static String text(final byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static Person savedPerson(final String first) throws IOException {
        final Person person = Person.builder().first(first).last(RandomData.genAlpha(8))
                .email(first.toLowerCase(Locale.ROOT) + "." + RandomData.genAlpha(8).toLowerCase(Locale.ROOT)
                        + "@example.com").build();
        Assert.assertTrue(DAO.getInstance().savePerson(person));
        return person;
    }

    private static String hotel(final LodgingCommands admin, final String orgId, final String name) {
        final AccommodationForm form = new AccommodationForm();
        final String tag = RandomData.genAlpha(8);
        form.setName(name + " " + tag);
        form.setEmail(tag.toLowerCase(Locale.ROOT) + "@invoice.example");
        // Discovery compares phone DIGITS only, so the random part must be digits.
        form.setPhone("+1 503 " + (1_000_000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(9_000_000)));
        form.setStreet(tag + " Way");
        form.setCity("Portland");
        final String accId = admin.saveAccommodation(form, orgId);
        Assert.assertTrue(accId != null && !accId.isBlank(), "the hotel fixture was refused");
        final RoomTypeForm type = new RoomTypeForm();
        type.setName("Double");
        type.setMinPeople(1);
        type.setMaxPeople(2);
        Assert.assertTrue(admin.saveRoomType(accId, type));
        return accId;
    }

    private static String offerAt(final LodgingCommands admin, final String tripId, final String accId,
            final boolean costed) {
        final OfferForm form = new OfferForm();
        form.setName("Room at " + RandomData.genAlpha(4));
        form.setAccommodationId(accId);
        form.setRoomTypeIds(new ArrayList<>(List.of(admin.roomTypeRows(accId).get(0).getId())));
        form.setTripEventId(OfferForm.NEW_EVENT);
        form.setPricingModel("PER_ROOM");
        form.setNightlyPrice(100.0);
        form.setCostKnown(costed);
        form.setCostNightlyPrice(90.0);
        form.setDefaultStart(CHECK_IN);
        form.setDefaultEnd(CHECK_OUT);
        Assert.assertTrue(admin.saveOffer(tripId, form));
        return DAO.getInstance().getReservationOffers(tripId, Cached.NO).stream()
                .filter(offer -> accId.equals(offer.getAccommodationId().getValue()))
                .findFirst().orElseThrow().getId().getValue();
    }

    private static void reserve(final LodgingCommands admin, final String tripId, final String offerId,
            final Person who) {
        final ReservationForm form = new ReservationForm();
        form.setOfferId(offerId);
        form.setPersonIds(List.of(who.getId().getValue()));
        form.setStart(CHECK_IN);
        form.setEnd(CHECK_OUT);
        Assert.assertEquals(admin.createReservations(tripId, form), 1, "the stay fixture was refused");
    }

    /** Uninjected, the servlet asks CDI for the beans; the test JVM has no container, so that is what fails. */
    @Test
    public void withoutInjectionTheServletLooksTheBeansUp() {
        final RoomInvoicePdfServlet real = new RoomInvoicePdfServlet();
        signedIn(UUID.randomUUID().toString(), "admin", FakeData.FAKE_TRIP_ID);
        Assert.expectThrows(IllegalStateException.class, () -> real.doGet(req, resp));
    }
}
