package org.paulsens.trip.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.action.PassCommands;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Creds;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.paulsens.trip.cache.Cached;

/**
 * {@link RememberMeService} against the local-mode fake store. What must hold: a cookie signs its browser
 * back in exactly once per validator (rotation), a stolen-then-replayed validator kills the token loudly,
 * admins never get or redeem one, and every failure ends with "not signed in", never an error.
 */
public class RememberMeServiceTest {

    private final RememberMeService service = new RememberMeService(new ConfigCommands());

    /** A user login in local mode ("user*"/"user"), giving real Creds rows for the service to re-read. */
    private Creds userCreds(final String email) {
        final Creds creds = new PassCommands().login(email, "user");
        Assert.assertNotNull(creds, "local-mode login for " + email + " should work");
        return creds;
    }

    @Test
    public void issueThenValidateRotatesTheValidator() {
        final Creds creds = userCreds("user2");
        final List<Cookie> issued = new ArrayList<>();
        service.issue(requestWith((Cookie) null), responseCapturing(issued), creds);
        Assert.assertEquals(issued.size(), 1);
        final Cookie cookie = issued.get(0);
        Assert.assertTrue(cookie.isHttpOnly(), "the cookie must be HttpOnly");
        Assert.assertEquals(cookie.getPath(), "/");
        Assert.assertEquals(cookie.getAttribute("SameSite"), "Lax");

        final List<Cookie> rotated = new ArrayList<>();
        final Creds restored = service.validateAndRotate(requestWith(cookie), responseCapturing(rotated));
        Assert.assertNotNull(restored, "a fresh cookie must restore the login");
        Assert.assertEquals(restored.getUserId(), creds.getUserId());
        Assert.assertEquals(rotated.size(), 1);
        Assert.assertNotEquals(rotated.get(0).getValue(), cookie.getValue(),
                "the validator must rotate on use");
        Assert.assertEquals(rotated.get(0).getValue().split(":")[0], cookie.getValue().split(":")[0],
                "the selector must survive rotation -- it is what makes theft detectable");
    }

    /**
     * A browser on an org-site host can present TWO remember-me cookies (a host-only one from before the
     * shared-cookie deploy plus the domain-wide one); logout must burn both rows, not just the first.
     */
    @Test
    public void revokeBurnsEveryPresentedCookie() {
        final Creds creds = userCreds("user5");
        final List<Cookie> first = new ArrayList<>();
        service.issue(requestWith((Cookie) null), responseCapturing(first), creds);
        final List<Cookie> second = new ArrayList<>();
        service.issue(requestWith((Cookie) null), responseCapturing(second), creds);

        final HttpServletRequest both = Mockito.mock(HttpServletRequest.class);
        Mockito.when(both.getCookies()).thenReturn(new Cookie[] {first.get(0), second.get(0)});
        final List<Cookie> expired = new ArrayList<>();
        service.revoke(both, responseCapturing(expired));

        Assert.assertEquals(expired.size(), 1, "one expiry cookie; the container scopes it per host");
        Assert.assertEquals(expired.get(0).getMaxAge(), 0);
        Assert.assertNull(service.validateAndRotate(requestWith(first.get(0)),
                responseCapturing(new ArrayList<>())), "the first presented token is dead");
        Assert.assertNull(service.validateAndRotate(requestWith(second.get(0)),
                responseCapturing(new ArrayList<>())), "...and so is the second, which a first-only revoke left alive");
    }

    @Test
    public void revokeWithNoCookieIsSilent() {
        final List<Cookie> expired = new ArrayList<>();
        service.revoke(requestWith((Cookie) null), responseCapturing(expired));
        Assert.assertTrue(expired.isEmpty(), "nothing presented, nothing expired");
    }

    /** The theft signature: a stale validator presented AFTER the grace window kills the token for BOTH parties. */
    @Test
    public void aReplayedOldValidatorBurnsTheTokenAfterTheGraceWindow() {
        final Creds creds = userCreds("user3");
        final List<Cookie> issued = new ArrayList<>();
        service.issue(requestWith((Cookie) null), responseCapturing(issued), creds);
        final Cookie original = issued.get(0);

        // The rightful browser uses it once; the validator rotates.
        final List<Cookie> rotated = new ArrayList<>();
        Assert.assertNotNull(service.validateAndRotate(requestWith(original), responseCapturing(rotated)));

        // The grace window closes (aged in the store rather than slept through).
        ageLastRotation(original, RememberMeService.ROTATION_GRACE_SECONDS + 1);

        // A thief (or the same browser with the pre-rotation copy) replays the ORIGINAL cookie.
        Assert.assertNull(service.validateAndRotate(requestWith(original), responseCapturing(new ArrayList<>())),
                "a stale validator outside the grace window must be refused");

        // And the token is dead for the rotated copy too -- the row was deleted.
        Assert.assertNull(service.validateAndRotate(requestWith(rotated.get(0)),
                responseCapturing(new ArrayList<>())), "theft detection must burn the whole token");
    }

    /**
     * The browser racing its own rotation: a second request carrying the pre-rotation cookie lands moments
     * after the first rotated. It must restore quietly -- no rotation, no Set-Cookie (the winner's cookie is
     * already the live one), and the token must survive for both copies.
     */
    @Test
    public void aPreRotationCopyWithinTheGraceWindowStillRestores() {
        final Creds creds = userCreds("user7");
        final List<Cookie> issued = new ArrayList<>();
        service.issue(requestWith((Cookie) null), responseCapturing(issued), creds);
        final Cookie original = issued.get(0);

        final List<Cookie> rotated = new ArrayList<>();
        Assert.assertNotNull(service.validateAndRotate(requestWith(original), responseCapturing(rotated)));

        final List<Cookie> graceOut = new ArrayList<>();
        final Creds graced = service.validateAndRotate(requestWith(original), responseCapturing(graceOut));
        Assert.assertNotNull(graced, "the pre-rotation copy must restore within the grace window");
        Assert.assertEquals(graced.getUserId(), creds.getUserId());
        Assert.assertTrue(graceOut.isEmpty(),
                "the grace path must not set a cookie -- the winner's rotated cookie is the live one");

        // The token survived: the rotated cookie still works (and rotates normally).
        Assert.assertNotNull(service.validateAndRotate(requestWith(rotated.get(0)),
                responseCapturing(new ArrayList<>())), "the grace restore must not burn the token");
    }

    /** Only ONE generation back is honored: after a second rotation the original validator is theft again. */
    @Test
    public void theGraceWindowCoversOnlyTheLatestRotation() {
        final Creds creds = userCreds("user8");
        final List<Cookie> issued = new ArrayList<>();
        service.issue(requestWith((Cookie) null), responseCapturing(issued), creds);
        final Cookie original = issued.get(0);

        final List<Cookie> second = new ArrayList<>();
        Assert.assertNotNull(service.validateAndRotate(requestWith(original), responseCapturing(second)));
        Assert.assertNotNull(service.validateAndRotate(requestWith(second.get(0)),
                responseCapturing(new ArrayList<>())));

        Assert.assertNull(service.validateAndRotate(requestWith(original), responseCapturing(new ArrayList<>())),
                "a validator two rotations old must be refused even inside the time window");
    }

    /** Backdates the stored rotation timestamp so tests can cross the grace window without sleeping. */
    private static void ageLastRotation(final Cookie cookie, final long seconds) {
        final String selector = cookie.getValue().split(":")[0];
        final org.paulsens.trip.model.AuthToken token =
                DAO.getInstance().getAuthToken(selector, Cached.NO).orElseThrow();
        token.setRotatedAt(token.getRotatedAt() - seconds);
        Assert.assertTrue(DAO.getInstance().saveAuthToken(token));
    }

    @Test
    public void adminsGetNoCookieAtEitherEnd() {
        final Creds admin = new PassCommands().login("admin", "admin");
        final List<Cookie> issued = new ArrayList<>();
        service.issue(requestWith((Cookie) null), responseCapturing(issued), admin);
        Assert.assertTrue(issued.isEmpty(), "an admin login must not issue a remember-me cookie");
    }

    @Test
    public void garbageAndUnknownCookiesAreExpiredQuietly() {
        for (final String bad : new String[] {"no-separator", ":leading", "trailing:", "unknown:validator"}) {
            final List<Cookie> out = new ArrayList<>();
            Assert.assertNull(service.validateAndRotate(requestWith(new Cookie("trip_remember", bad)),
                    responseCapturing(out)));
            Assert.assertEquals(out.size(), 1, "'" + bad + "' should expire the cookie");
            Assert.assertEquals(out.get(0).getMaxAge(), 0, "'" + bad + "' should expire the cookie");
        }
    }

    @Test
    public void aDisabledFeatureIssuesAndHonorsNothing() {
        final ConfigCommands off = Mockito.mock(ConfigCommands.class);
        Mockito.when(off.getBoolean(KnownSettings.LOGIN_REMEMBER_ENABLED)).thenReturn(false);
        final RememberMeService disabled = new RememberMeService(off);

        final List<Cookie> issued = new ArrayList<>();
        disabled.issue(requestWith((Cookie) null), responseCapturing(issued), userCreds("user4"));
        Assert.assertTrue(issued.isEmpty());

        // A cookie issued while the feature was ON stops working the moment it is switched off.
        final List<Cookie> live = new ArrayList<>();
        service.issue(requestWith((Cookie) null), responseCapturing(live), userCreds("user4"));
        Assert.assertNull(disabled.validateAndRotate(requestWith(live.get(0)),
                responseCapturing(new ArrayList<>())), "disabling is the kill switch for existing cookies");
    }

    @Test
    public void revokeDeletesThisBrowsersTokenAndExpiresTheCookie() {
        final Creds creds = userCreds("user5");
        final List<Cookie> issued = new ArrayList<>();
        service.issue(requestWith((Cookie) null), responseCapturing(issued), creds);

        final List<Cookie> out = new ArrayList<>();
        service.revoke(requestWith(issued.get(0)), responseCapturing(out));
        Assert.assertEquals(out.get(0).getMaxAge(), 0);
        Assert.assertNull(service.validateAndRotate(requestWith(issued.get(0)),
                responseCapturing(new ArrayList<>())), "a revoked token must not restore anything");
    }

    @Test
    public void revokeAllKillsEveryBrowserForThatPerson() {
        final Creds creds = userCreds("user6");
        final List<Cookie> browserOne = new ArrayList<>();
        final List<Cookie> browserTwo = new ArrayList<>();
        service.issue(requestWith((Cookie) null), responseCapturing(browserOne), creds);
        service.issue(requestWith((Cookie) null), responseCapturing(browserTwo), creds);

        service.revokeAllFor(creds.getUserId());

        Assert.assertNull(service.validateAndRotate(requestWith(browserOne.get(0)),
                responseCapturing(new ArrayList<>())));
        Assert.assertNull(service.validateAndRotate(requestWith(browserTwo.get(0)),
                responseCapturing(new ArrayList<>())));
    }

    @Test
    public void noCookieMeansNoRestoreAndNoSideEffects() {
        final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        Assert.assertNull(service.validateAndRotate(requestWith((Cookie) null), response));
        Mockito.verify(response, Mockito.never()).addCookie(ArgumentMatchers.any());
    }

    /** An expired row is refused and cleaned up even before DynamoDB's TTL sweeper gets to it. */
    @Test
    public void anExpiredTokenIsRefused() {
        final Creds creds = userCreds("user2");
        final List<Cookie> issued = new ArrayList<>();
        service.issue(requestWith((Cookie) null), responseCapturing(issued), creds);
        final String selector = issued.get(0).getValue().split(":")[0];
        final org.paulsens.trip.model.AuthToken token =
                DAO.getInstance().getAuthToken(selector, Cached.NO).orElseThrow();
        token.setExpires(1L);
        Assert.assertTrue(DAO.getInstance().saveAuthToken(token));

        Assert.assertNull(service.validateAndRotate(requestWith(issued.get(0)),
                responseCapturing(new ArrayList<>())), "an expired token must be refused");
        Assert.assertTrue(DAO.getInstance().getAuthToken(selector, Cached.NO).isEmpty(),
                "the expired row should have been deleted");
    }

    private static HttpServletRequest requestWith(final Cookie cookie) {
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getCookies()).thenReturn(cookie == null ? null : new Cookie[] {cookie});
        Mockito.when(request.isSecure()).thenReturn(false);
        return request;
    }

    private static HttpServletResponse responseCapturing(final List<Cookie> sink) {
        final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        Mockito.doAnswer(call -> sink.add(call.getArgument(0)))
                .when(response).addCookie(ArgumentMatchers.any(Cookie.class));
        return response;
    }
}
