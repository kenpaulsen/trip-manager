package org.paulsens.trip.web;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.CheckinCommands;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.security.TicketCodes;
import org.paulsens.trip.util.TicketQr;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The emailed QR image: a PNG of exactly the ticket page's URL for a real ticket on its trip, session-free and
 * privately cacheable; every other request (no trip, no code, a malformed code, an unknown trip, a wrong or
 * prefix code, a ticket with no URL) is a bare 404 that never consults the registrations it does not need to.
 */
public class TicketQrServletTest {

    private static final String TRIP_ID = "7d1ec2a9-2d33-4a4a-9c4e-0a7e1b2c3d4e";
    private static final String CODE = TicketCodes.mint();

    private HttpServletRequest req;
    private HttpServletResponse resp;
    private ByteArrayOutputStream out;
    private AtomicInteger status;
    private CheckinCommands checkin;
    private Trip trip;
    private TicketQrServlet servlet;

    @BeforeMethod
    public void setUp() throws IOException {
        req = Mockito.mock(HttpServletRequest.class);
        resp = Mockito.mock(HttpServletResponse.class);
        out = new ByteArrayOutputStream();
        status = new AtomicInteger(200);
        checkin = Mockito.mock(CheckinCommands.class);
        trip = Trip.builder().id(TRIP_ID).title("Conf").build();
        servlet = new TicketQrServlet();
        servlet.setCheckin(checkin, id -> TRIP_ID.equals(id) ? Optional.of(trip) : Optional.empty());

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
        Mockito.doAnswer(call -> status.getAndSet(call.getArgument(0)))
                .when(resp).sendError(ArgumentMatchers.anyInt());
    }

    private void request(final String tripParam, final String codeParam) {
        Mockito.when(req.getParameter("trip")).thenReturn(tripParam);
        Mockito.when(req.getParameter("t")).thenReturn(codeParam);
    }

    private void ticketExists(final String url) {
        final Registration reg = new Registration(TRIP_ID, org.paulsens.trip.model.Person.Id.newInstance())
                .withStatus(Registration.Status.CONFIRMED).withTicketCode(CODE);
        Mockito.when(checkin.byTicket(TRIP_ID, CODE)).thenReturn(reg);
        Mockito.when(checkin.ticketUrl(trip, CODE)).thenReturn(url);
    }

    @Test
    public void aRealTicketIsItsPageUrlAsAPrivatelyCacheablePng() throws IOException {
        final String url = "https://acme.example.com/trip/ticket.jsf?trip=" + TRIP_ID + "&t=" + CODE;
        ticketExists(url);
        // The grouped, lower-cased spelling someone copies from the email resolves to the same ticket.
        request(TRIP_ID, TicketCodes.grouped(CODE).toLowerCase(java.util.Locale.ROOT));

        servlet.doGet(req, resp);

        Assert.assertEquals(status.get(), 200);
        Assert.assertEquals(out.toByteArray(), TicketQr.png(url), "exactly the page's QR, as the page encodes it");
        Mockito.verify(resp).setContentType("image/png");
        Mockito.verify(resp).setContentLength(out.size());
        Mockito.verify(resp).setHeader("Cache-Control", "private, max-age=86400");
        Mockito.verify(resp).setHeader("X-Content-Type-Options", "nosniff");
        Mockito.verify(req, Mockito.never()).getSession();
        Mockito.verify(req, Mockito.never()).getSession(ArgumentMatchers.anyBoolean());
    }

    @Test
    public void noTripNoCodeOrAMalformedCodeIs404BeforeAnyTicketIsRead() throws IOException {
        final String[][] refused = {{null, CODE}, {"", CODE}, {TRIP_ID, null}, {TRIP_ID, ""}, {TRIP_ID, "ABCD"},
                {TRIP_ID, CODE.substring(1)}, {TRIP_ID, CODE + "A"}, {TRIP_ID, CODE.replace(CODE.charAt(0), '!')}};
        for (final String[] pair : refused) {
            status.set(200);
            request(pair[0], pair[1]);
            servlet.doGet(req, resp);
            Assert.assertEquals(status.get(), 404, "must be refused: trip=" + pair[0] + " t=" + pair[1]);
        }
        Mockito.verify(checkin, Mockito.never()).byTicket(ArgumentMatchers.any(), ArgumentMatchers.any());
        Assert.assertEquals(out.size(), 0, "a 404 carries no image");
    }

    @Test
    public void anUnknownTripIs404WithoutReadingItsRegistrations() throws IOException {
        request("no-such-trip", CODE);
        servlet.doGet(req, resp);
        Assert.assertEquals(status.get(), 404);
        Mockito.verify(checkin, Mockito.never()).byTicket(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    public void aCodeNobodyHoldsIs404() throws IOException {
        request(TRIP_ID, CODE);
        Mockito.when(checkin.byTicket(TRIP_ID, CODE)).thenReturn(null);
        servlet.doGet(req, resp);
        Assert.assertEquals(status.get(), 404);
        Mockito.verify(resp, Mockito.never()).setContentType(ArgumentMatchers.anyString());
    }

    @Test
    public void theDefaultTripLookupReadsTheCachedTripRow() throws IOException {
        // No injected lookup: the seeded conference resolves through the DAO (local mode) and an unknown id does
        // not; the bean seam stays injected because CDI is not running here.
        servlet.setCheckin(checkin, null);
        final String url = "https://acme.example.com/trip/ticket.jsf?t=" + CODE;
        final Registration reg = new Registration(org.paulsens.trip.dynamo.FakeData.CONF_TRIP_ID,
                org.paulsens.trip.model.Person.Id.newInstance()).withTicketCode(CODE);
        Mockito.when(checkin.byTicket(org.paulsens.trip.dynamo.FakeData.CONF_TRIP_ID, CODE)).thenReturn(reg);
        Mockito.when(checkin.ticketUrl(ArgumentMatchers.any(), ArgumentMatchers.eq(CODE))).thenReturn(url);
        request(org.paulsens.trip.dynamo.FakeData.CONF_TRIP_ID, CODE);

        servlet.doGet(req, resp);

        Assert.assertEquals(status.get(), 200);
        Assert.assertEquals(out.toByteArray(), TicketQr.png(url));

        status.set(200);
        request("no-such-trip", CODE);
        servlet.doGet(req, resp);
        Assert.assertEquals(status.get(), 404);
    }

    @Test
    public void aTicketWithNoUrlIs404() throws IOException {
        ticketExists("");
        request(TRIP_ID, CODE);
        servlet.doGet(req, resp);
        Assert.assertEquals(status.get(), 404, "nothing to encode: no image");
        Assert.assertEquals(out.size(), 0);
    }
}
