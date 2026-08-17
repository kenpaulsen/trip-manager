package org.paulsens.trip.action;

import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.web.Sessions;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class TripUtilCommandsTest {
    private final TripUtilCommands tripUtil = new TripUtilCommands();

    @Test
    public void testWithTimeZone() {
        final LocalDateTime time = LocalDateTime.of(2022, 7, 14, 5, 0); // This should be 7/13 in PST
        assertEquals(tripUtil.withTimeZone(time, "America/Los_Angeles").getDayOfMonth(), 13);
        assertEquals(tripUtil.withTimeZone(time, "Europe/Paris").getDayOfMonth(), 14);
    }

    /**
     * The post-login return target rides a user-controlled form field, so the normalizer is the entire
     * defense against turning the login page into an open redirect.
     */
    @Test
    public void returnPathNormalizationAcceptsOnlySameSitePaths() {
        assertEquals(TripUtilCommands.normalizeReturnPath("/trip/joinTrip.jsf?trip=x"),
                "/trip/joinTrip.jsf?trip=x", "A same-site path passes through untouched");
        assertEquals(TripUtilCommands.normalizeReturnPath(
                        "https://visitqueenofpeace.com/trip/joinTrip.jsf?trip=x"),
                "/trip/joinTrip.jsf?trip=x", "The legacy absolute stash format is reduced to its path");
        assertEquals(TripUtilCommands.normalizeReturnPath("http://host"), "/",
                "An absolute URL with no path reduces to the root");
        assertNull(TripUtilCommands.normalizeReturnPath(null));
        assertNull(TripUtilCommands.normalizeReturnPath("   "));
        assertNull(TripUtilCommands.normalizeReturnPath("//evil.example.com/phish"),
                "Protocol-relative URLs are browser redirects to another host -- refused");
        assertNull(TripUtilCommands.normalizeReturnPath("/\\evil.example.com"),
                "Backslash tricks (browsers treat \\ as /) are refused");
        assertNull(TripUtilCommands.normalizeReturnPath("ftp://x/y"), "Foreign schemes are refused");
        assertNull(TripUtilCommands.normalizeReturnPath("relative/path"),
                "Only absolute paths can be trusted to stay on this site");
        // No FacesContext in unit tests: the accessors must no-op rather than crash.
        assertNull(tripUtil.getReturnPath());
        tripUtil.setReturnPath("/somewhere");
        assertEquals(tripUtil.currentPathAndQuery(), "/");
        assertTrue(tripUtil.loginUrl().startsWith("/account/login.jsf?to="));
    }

    /** The with-context halves: the stash round-trip and the current-request path builder. */
    @Test
    public void returnPathStashAndCurrentPathWorkAgainstARealishContext() {
        final Map<String, Object> session = new HashMap<>();
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getRequestURI()).thenReturn("/trip/joinTrip.jsf");
        Mockito.when(request.getQueryString()).thenReturn("trip=pub-en-2");

        try (MockedStatic<FacesContext> faces = Mockito.mockStatic(FacesContext.class)) {
            final FacesContext ctx = Mockito.mock(FacesContext.class);
            final ExternalContext ext = Mockito.mock(ExternalContext.class);
            Mockito.when(ctx.getExternalContext()).thenReturn(ext);
            Mockito.when(ext.getSessionMap()).thenReturn(session);
            Mockito.when(ext.getRequest()).thenReturn(request);
            faces.when(FacesContext::getCurrentInstance).thenReturn(ctx);

            assertEquals(tripUtil.currentPathAndQuery(), "/trip/joinTrip.jsf?trip=pub-en-2");
            assertEquals(tripUtil.loginUrl(),
                    "/account/login.jsf?to=%2Ftrip%2FjoinTrip.jsf%3Ftrip%3Dpub-en-2");

            tripUtil.setReturnPath("https://visitqueenofpeace.com/account.jsf?x=1");
            assertEquals(session.get(Sessions.AFTER_LOGIN_URL), "/account.jsf?x=1",
                    "The setter normalizes before stashing");
            assertEquals(tripUtil.getReturnPath(), "/account.jsf?x=1");

            tripUtil.setReturnPath("//evil.example.com/phish");
            assertEquals(session.get(Sessions.AFTER_LOGIN_URL), "/account.jsf?x=1",
                    "An untrustworthy value must not clobber the stash");
            tripUtil.setReturnPath("");
            assertEquals(session.get(Sessions.AFTER_LOGIN_URL), "/account.jsf?x=1",
                    "An empty submitted hidden field is a no-op, not a wipe");

            // A query string is optional on the current request.
            Mockito.when(request.getQueryString()).thenReturn(null);
            assertEquals(tripUtil.currentPathAndQuery(), "/trip/joinTrip.jsf");

            // A poisoned stash (however it got there) is normalized on the way OUT too.
            session.put(Sessions.AFTER_LOGIN_URL, "//evil.example.com");
            assertNull(tripUtil.getReturnPath());
        }
    }

    /** Save-returns-to-referrer: what counts as a usable "where I came from". */
    @Test
    public void refererPathAnswersUsableSameSiteOriginsOnly() {
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getRequestURI()).thenReturn("/account/person.jsf");

        try (MockedStatic<FacesContext> faces = Mockito.mockStatic(FacesContext.class)) {
            final FacesContext ctx = Mockito.mock(FacesContext.class);
            final ExternalContext ext = Mockito.mock(ExternalContext.class);
            Mockito.when(ctx.getExternalContext()).thenReturn(ext);
            Mockito.when(ext.getRequest()).thenReturn(request);
            faces.when(FacesContext::getCurrentInstance).thenReturn(ctx);

            Mockito.when(request.getHeader("Referer"))
                    .thenReturn("https://visitqueenofpeace.com/account/family.jsf");
            assertEquals(tripUtil.refererPath(), "/account/family.jsf",
                    "The linking page, reduced to its same-site path");

            Mockito.when(request.getHeader("Referer"))
                    .thenReturn("https://visitqueenofpeace.com/account/person.jsf?id=x");
            assertNull(tripUtil.refererPath(), "This very page is not a return target (reloads, self-links)");

            Mockito.when(request.getHeader("Referer"))
                    .thenReturn("https://visitqueenofpeace.com/account/login.jsf?to=x");
            assertNull(tripUtil.refererPath(), "Arriving from login must not send Save back to login");

            Mockito.when(request.getHeader("Referer")).thenReturn(null);
            assertNull(tripUtil.refererPath(), "Privacy settings strip the header routinely: null, not a crash");
        }
        assertNull(tripUtil.refererPath(), "No FacesContext: null, not a crash");
    }

    @Test
    public void withInfoEncodesAndJoinsCorrectly() {
        assertEquals(tripUtil.withInfo("/account/family.jsf", "Lucy's profile saved."),
                "/account/family.jsf?info=Lucy%27s+profile+saved.");
        assertEquals(tripUtil.withInfo("/trip/itinerary.jsf?trip=x", "ok"),
                "/trip/itinerary.jsf?trip=x&info=ok", "An existing query string joins with &");
    }

    @Test
    public void flightTitleUppercasesAndTrimsTheAirportCodes() {
        assertEquals(tripUtil.composeFlightTitle("pdx", "fco"), "PDX -> FCO");
        assertEquals(tripUtil.composeFlightTitle("  pdx ", " Fco"), "PDX -> FCO", "Typed whitespace is not a code");
    }

    @Test
    public void flightNotesCarryTheNumberTimesAndDuration() {
        assertEquals(tripUtil.composeFlightNotes("Alaska Airlines AS 123",
                        LocalDateTime.of(2028, 5, 1, 8, 15), LocalDateTime.of(2028, 5, 1, 11, 45), "9h 10m"),
                "Alaska Airlines AS 123: 8:15am -> 11:45am (9h 10m)");
    }

    /**
     * The old inline script formatted the departure with {@code K} (hour 0-11), so an afternoon departure read
     * {@code 0:15pm}. Arrival used {@code h} and was correct, which is why nobody noticed.
     */
    @Test
    public void aNoonDepartureIsTwelveNotZero() {
        assertEquals(tripUtil.composeFlightNotes("AS 1", LocalDateTime.of(2028, 5, 1, 12, 15),
                LocalDateTime.of(2028, 5, 1, 14, 0), "1h 45m"), "AS 1: 12:15pm -> 2:00pm (1h 45m)");
    }

    @Test
    public void anOvernightFlightIsMarked() {
        assertEquals(tripUtil.composeFlightNotes("AS 2", LocalDateTime.of(2028, 5, 1, 22, 30),
                LocalDateTime.of(2028, 5, 2, 6, 5), "7h 35m"), "AS 2: 10:30pm -> 6:05am +1 (7h 35m)");
    }

    /**
     * The marker used to compare {@code dayOfYear}, which goes NEGATIVE across New Year -- a 31-Dec red-eye
     * silently lost its {@code +1}.
     */
    @Test
    public void anOvernightFlightAcrossNewYearIsStillMarked() {
        assertEquals(tripUtil.composeFlightNotes("AS 3", LocalDateTime.of(2028, 12, 31, 23, 30),
                LocalDateTime.of(2029, 1, 1, 7, 0), "7h 30m"), "AS 3: 11:30pm -> 7:00am +1 (7h 30m)");
    }

    /** A null field used to abort the whole JSFT block; composing must degrade instead. */
    @Test
    public void missingPiecesDegradeRatherThanThrow() {
        assertEquals(tripUtil.composeFlightTitle(null, null), " -> ");
        assertEquals(tripUtil.composeFlightNotes(null, null, null, null), ":  ->  ()");
    }
}