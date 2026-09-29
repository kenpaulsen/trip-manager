package org.paulsens.trip.web;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.action.PrivilegeCommands;
import org.paulsens.trip.action.ReportCommands;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Privilege;
import org.paulsens.trip.model.Trip;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The Room Invoice PDF download: who gets the file, who is sent to login, who is refused, and the headers that
 * make it a download with the trip's name on it.
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
                id -> new ReportCommands.RoomInvoice());
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

    /** Uninjected, the servlet asks CDI for the beans; the test JVM has no container, so that is what fails. */
    @Test
    public void withoutInjectionTheServletLooksTheBeansUp() {
        final RoomInvoicePdfServlet real = new RoomInvoicePdfServlet();
        signedIn(UUID.randomUUID().toString(), "admin", FakeData.FAKE_TRIP_ID);
        Assert.expectThrows(IllegalStateException.class, () -> real.doGet(req, resp));
    }
}
