package org.paulsens.trip.web;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.Caller;
import org.paulsens.trip.action.PrivilegeCommands;
import org.paulsens.trip.action.WaiverHtml;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripWaiver;
import org.paulsens.trip.model.WaiverAcceptance;
import org.paulsens.trip.model.WaiverDocument;
import org.paulsens.trip.model.WaiverVersion;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/** The signed-copy download: the bytes and headers for a reader, login for nobody, 404 for anyone else. */
public class WaiverPdfServletTest {
    private static final Person.Id ADMIN = Person.Id.from("waiver-pdf-admin");
    private static final String BODY = "<h2>Waiver</h2><p>I accept.</p><ul><li>One</li></ul>";

    private HttpServletRequest req;
    private HttpServletResponse resp;
    private ByteArrayOutputStream out;
    private AtomicInteger status;
    private Caller caller;
    private String tripId;
    private final Person.Id kid = Person.Id.newInstance();
    private final String waiverId = UUID.randomUUID().toString();
    private final WaiverPdfServlet servlet = new WaiverPdfServlet() {
        @Override
        protected Caller callerOf(final HttpServletRequest request) {
            return caller;
        }
    };

    @BeforeClass
    public void seed() throws IOException {
        FakeData.initFakeData();
        final String orgId = UUID.randomUUID().toString();
        assertTrue(DAO.getInstance().saveWaiverDocument(WaiverDocument.builder().id(waiverId).orgId(orgId)
                .title("Release").versions(List.of(WaiverVersion.builder().number(1).title("Release").bodyHtml(BODY)
                        .sha256(WaiverHtml.sha256(BODY)).publishedAt(LocalDateTime.now()).build())).build()));
        final Trip trip = Trip.builder().id(UUID.randomUUID().toString()).title("Signed trip")
                .waivers(List.of(TripWaiver.builder().waiverId(waiverId).version(1).build())).build();
        trip.setOrgId(orgId);
        assertTrue(DAO.getInstance().saveTrip(trip));
        tripId = trip.getId();
        assertTrue(DAO.getInstance().saveWaiverAcceptance(WaiverAcceptance.builder().tripId(tripId).personId(kid)
                .waiverId(waiverId).version(1).title("Release").sha256(WaiverHtml.sha256(BODY))
                .signerId(Person.Id.newInstance()).signerName("Ken Parent").signerEmail("ken@example.com")
                .subjectName("Lucy Parent").agreed(true).authorityAttested(true).acceptedAt(Instant.now())
                .ip("192.0.2.1").orgId(orgId).build()));
    }

    @BeforeMethod
    public void setUp() throws IOException {
        req = Mockito.mock(HttpServletRequest.class);
        resp = Mockito.mock(HttpServletResponse.class);
        out = new ByteArrayOutputStream();
        status = new AtomicInteger(200);
        caller = new Caller(ADMIN, true, new AuditActor("admin@example.com", ADMIN.getValue()),
                new PrivilegeCommands());
        Mockito.when(req.getContextPath()).thenReturn("");
        Mockito.when(req.getRequestURI()).thenReturn("/waivers/signed");
        Mockito.when(req.getParameter("trip")).thenReturn(tripId);
        Mockito.when(req.getParameter("person")).thenReturn(kid.getValue());
        Mockito.when(req.getParameter("waiver")).thenReturn(waiverId);
        Mockito.when(req.getParameter("version")).thenReturn("1");
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

    @Test
    public void aReaderDownloadsTheSignedCopy() throws IOException {
        servlet.doGet(req, resp);
        assertEquals(status.get(), 200);
        assertEquals(new String(out.toByteArray(), 0, 5, StandardCharsets.US_ASCII), "%PDF-");
        Mockito.verify(resp).setHeader("Cache-Control", "private, no-store");
        Mockito.verify(resp).setHeader(ArgumentMatchers.eq("Content-Disposition"),
                ArgumentMatchers.startsWith("attachment; filename=\"Release - Lucy Parent.pdf\""));
    }

    @Test
    public void nobodySignedInGoesToLoginAndBack() throws IOException {
        caller = new Caller(null, false, AuditActor.system(), new PrivilegeCommands());
        Mockito.when(req.getQueryString()).thenReturn("trip=" + tripId);
        servlet.doGet(req, resp);
        Mockito.verify(resp).sendRedirect("/account/login.jsf?to=%2Fwaivers%2Fsigned%3Ftrip%3D" + tripId);
    }

    @Test
    public void strangersAndBadParametersAre404() throws IOException {
        caller = new Caller(Person.Id.newInstance(), false, AuditActor.system(), new PrivilegeCommands());
        servlet.doGet(req, resp);
        assertEquals(status.get(), 404, "a stranger learns nothing");
        caller = new Caller(ADMIN, true, AuditActor.system(), new PrivilegeCommands());
        for (final String version : new String[] {"x", null, "2"}) {
            status.set(200);
            Mockito.when(req.getParameter("version")).thenReturn(version);
            servlet.doGet(req, resp);
            assertEquals(status.get(), 404, "version " + version);
        }
        status.set(200);
        Mockito.when(req.getParameter("version")).thenReturn("1");
        Mockito.when(req.getParameter("person")).thenReturn(" ");
        servlet.doGet(req, resp);
        assertEquals(status.get(), 404);
    }
}
