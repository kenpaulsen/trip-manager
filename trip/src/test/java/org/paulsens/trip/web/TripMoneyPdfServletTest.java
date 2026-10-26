package org.paulsens.trip.web;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.Caller;
import org.paulsens.trip.action.PrivilegeCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/** The two PDF downloads: the bytes and headers for a reader, login for nobody, 404 for anyone else. */
public class TripMoneyPdfServletTest {
    private static final Person.Id ADMIN = Person.Id.from("money-pdf-admin");

    private HttpServletRequest req;
    private HttpServletResponse resp;
    private ByteArrayOutputStream out;
    private AtomicInteger status;
    private Caller caller;
    private String tripId;
    private final TripMoneyPdfServlet servlet = new TripMoneyPdfServlet() {
        @Override
        protected Caller callerOf(final HttpServletRequest request) {
            return caller;
        }
    };

    @BeforeClass
    public void seed() throws IOException {
        FakeData.initFakeData();
        final Trip trip = Trip.builder().id(UUID.randomUUID().toString()).title("Money \"PDF\" trip")
                .startDate(LocalDateTime.of(2026, 9, 20, 8, 0)).people(new ArrayList<>()).build();
        assertTrue(DAO.getInstance().saveTrip(trip));
        tripId = trip.getId();
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
        Mockito.when(req.getParameter("trip")).thenReturn(tripId);
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

    private void get(final String servletPath) throws IOException {
        Mockito.when(req.getServletPath()).thenReturn(servletPath);
        Mockito.when(req.getRequestURI()).thenReturn(servletPath);
        servlet.doGet(req, resp);
    }

    private String head() {
        return new String(out.toByteArray(), 0, 5, StandardCharsets.US_ASCII);
    }

    @Test
    public void theBudgetDownloads() throws IOException {
        get("/reports/budget");
        assertEquals(status.get(), 200);
        assertEquals(head(), "%PDF-");
        Mockito.verify(resp).setContentType("application/pdf");
        Mockito.verify(resp).setHeader("Cache-Control", "private, no-store");
        Mockito.verify(resp).setHeader(ArgumentMatchers.eq("Content-Disposition"),
                ArgumentMatchers.startsWith("attachment; filename=\"Money -PDF- trip - Budget.pdf\""));
    }

    @Test
    public void theExpenseReportDownloadsWithOrWithoutReceipts() throws IOException {
        Mockito.when(req.getParameter("receipts")).thenReturn("true");
        get("/reports/expense-report");
        assertEquals(status.get(), 200);
        assertEquals(head(), "%PDF-");
        Mockito.verify(resp).setHeader(ArgumentMatchers.eq("Content-Disposition"),
                ArgumentMatchers.contains("Expense Report.pdf"));
    }

    @Test
    public void nobodySignedInGoesToLoginAndBack() throws IOException {
        caller = new Caller(null, false, AuditActor.system(), new PrivilegeCommands());
        Mockito.when(req.getQueryString()).thenReturn("trip=" + tripId);
        get("/reports/budget");
        Mockito.verify(resp).sendRedirect("/account/login.jsf?to=%2Freports%2Fbudget%3Ftrip%3D" + tripId);
    }

    @Test
    public void aStrangerAnUnknownTripAndAnUnknownPathAre404() throws IOException {
        caller = new Caller(Person.Id.newInstance(), false, AuditActor.system(), new PrivilegeCommands());
        get("/reports/budget");
        assertEquals(status.get(), 404, "a stranger learns nothing, not even that the trip exists");
        caller = new Caller(ADMIN, true, AuditActor.system(), new PrivilegeCommands());
        Mockito.when(req.getParameter("trip")).thenReturn(UUID.randomUUID().toString());
        status.set(200);
        get("/reports/expense-report");
        assertEquals(status.get(), 404);
        status.set(200);
        get("/reports/other");
        assertEquals(status.get(), 404);
        assertEquals(out.size(), 0);
    }
}
