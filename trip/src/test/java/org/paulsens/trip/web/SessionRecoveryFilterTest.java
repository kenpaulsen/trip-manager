package org.paulsens.trip.web;

import java.time.DateTimeException;
import java.util.concurrent.CompletionException;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Which failures are treated as an unreadable session.
 *
 * <p>Both directions matter and neither is obvious from the code. Too narrow and the outage this exists for
 * comes back — the real failure arrives as a {@code DateTimeException}, a class with nothing to do with
 * serialisation, and is only identifiable by the Kryo frames underneath it. Too broad and any application error
 * silently logs someone out, which would be a far worse bug than the one being fixed because it would look
 * random and would never appear in a stack trace.
 */
public class SessionRecoveryFilterTest {

    @Test
    public void theProductionFailureIsRecognised() {
        // Exactly the shape that took the home page down: a plain DateTimeException whose only clue is the Kryo
        // frame beneath it, wrapped by the time it reaches a filter.
        final DateTimeException real = new DateTimeException("Invalid value for MonthOfYear (valid values 1 - 12): 19");
        real.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("java.time.temporal.ValueRange", "checkValidValue", "ValueRange.java", 319),
                new StackTraceElement("com.esotericsoftware.kryo.serializers.TimeSerializers$LocalDateSerializer",
                        "read", "TimeSerializers.java", 115),
                new StackTraceElement("org.redisson.codec.Kryo5Codec$4", "decode", "Kryo5Codec.java", 199),
        });
        Assert.assertTrue(SessionRecoveryFilter.isSessionDeserializationFailure(
                new RuntimeException("wrapped", real)));
    }

    /**
     * Kryo is on TOMCAT's classloader, not the webapp's, so this test cannot reference KryoException -- the same
     * separation that keeps Redisson's Netty away from the app's. Detection therefore has to work from frames and
     * names rather than instanceof, which is what this asserts.
     */
    @Test
    public void aKryoFailureIsRecognisedThroughAWrapper() {
        final RuntimeException kryoish = new RuntimeException("Error during Java deserialization.");
        kryoish.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.esotericsoftware.kryo.serializers.JavaSerializer", "read",
                        "JavaSerializer.java", 68),
        });
        Assert.assertTrue(SessionRecoveryFilter.isSessionDeserializationFailure(
                new CompletionException(kryoish)));
    }

    @Test
    public void aMissingClassInTheCodecIsRecognised() {
        // The other shape this produces: a class the session manager's loader cannot see.
        final ClassNotFoundException missing = new ClassNotFoundException("org.paulsens.trip.model.Trip");
        missing.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("org.redisson.codec.Kryo5Codec$4", "decode", "Kryo5Codec.java", 199),
        });
        Assert.assertTrue(SessionRecoveryFilter.isSessionDeserializationFailure(missing));
    }

    @Test
    public void ordinaryApplicationErrorsAreLeftAlone() {
        // The important negative. Clearing sessions on these would turn every NPE into an unexplained logout.
        Assert.assertFalse(SessionRecoveryFilter.isSessionDeserializationFailure(
                new NullPointerException("something in a bean")));
        Assert.assertFalse(SessionRecoveryFilter.isSessionDeserializationFailure(
                new IllegalStateException("a normal failure", new IllegalArgumentException("nested"))));
        Assert.assertFalse(SessionRecoveryFilter.isSessionDeserializationFailure(null));
    }

    @Test
    public void aDateTimeExceptionFromApplicationCodeIsLeftAlone() {
        // Same exception type as the real incident, but no serialisation frames -- so it must NOT match. This is
        // what stops "bad date somewhere in the app" from logging people out.
        final DateTimeException appLevel = new DateTimeException("Invalid value for MonthOfYear: 19");
        appLevel.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("org.paulsens.trip.action.TripCommands", "saveTrip", "TripCommands.java", 56),
        });
        Assert.assertFalse(SessionRecoveryFilter.isSessionDeserializationFailure(appLevel));
    }

    @Test
    public void aCyclicCauseChainTerminates() {
        // CompletionException plumbing can produce a cycle; walking it must not hang the request thread.
        final RuntimeException a = new RuntimeException("a");
        final RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);
        Assert.assertFalse(SessionRecoveryFilter.isSessionDeserializationFailure(b));
    }

    // --- doFilter itself: identity binding, checked-exception tunneling, and the recovery path ---

    private static jakarta.servlet.http.HttpServletRequest requestWithSession(final String email) {
        final jakarta.servlet.http.HttpServletRequest req =
                org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
        final jakarta.servlet.http.HttpSession session =
                org.mockito.Mockito.mock(jakarta.servlet.http.HttpSession.class);
        org.mockito.Mockito.when(req.getSession(false)).thenReturn(session);
        org.mockito.Mockito.when(session.getAttribute("loginEmail")).thenReturn(email);
        org.mockito.Mockito.when(req.getContextPath()).thenReturn("");
        org.mockito.Mockito.when(req.getRequestURI()).thenReturn("/page.jsf");
        org.mockito.Mockito.when(req.getMethod()).thenReturn("GET");
        return req;
    }

    @Test
    public void doFilterBindsTheRequestIdentityForTheChain() throws Exception {
        final SessionRecoveryFilter filter = new SessionRecoveryFilter();
        final java.util.concurrent.atomic.AtomicReference<org.paulsens.trip.audit.AuditActor> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        filter.doFilter(requestWithSession("bound@x.org"),
                org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletResponse.class),
                (rq, rs) -> seen.set(org.paulsens.trip.audit.AuditActor.current()));
        Assert.assertEquals(seen.get().email(), "bound@x.org",
                "the chain must observe the bound identity via AuditActor.current()");
    }

    /** The ScopedValue tunnel must not change what callers see: checked exceptions come out as themselves. */
    @Test
    public void chainExceptionsSurviveTheTunnelUnchanged() {
        final SessionRecoveryFilter filter = new SessionRecoveryFilter();
        final jakarta.servlet.http.HttpServletRequest req = requestWithSession(null);
        final jakarta.servlet.http.HttpServletResponse res =
                org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletResponse.class);
        Assert.assertThrows(java.io.IOException.class, () -> filter.doFilter(req, res, (rq, rs) -> {
            throw new java.io.IOException("io");
        }));
        Assert.assertThrows(jakarta.servlet.ServletException.class, () -> filter.doFilter(req, res, (rq, rs) -> {
            throw new jakarta.servlet.ServletException("se");
        }));
        Assert.assertThrows(IllegalStateException.class, () -> filter.doFilter(req, res, (rq, rs) -> {
            throw new IllegalStateException("app bug");
        }));
    }

    /** A Kryo-shaped failure from the chain triggers recovery (cookie expiry + redirect), not a rethrow. */
    @Test
    public void aKryoFailureFromTheChainTriggersRecovery() throws Exception {
        final SessionRecoveryFilter filter = new SessionRecoveryFilter();
        final jakarta.servlet.http.HttpServletRequest req = requestWithSession(null);
        final jakarta.servlet.http.HttpServletResponse res =
                org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletResponse.class);
        final DateTimeException kryoShaped =
                new DateTimeException("Invalid value for MonthOfYear (valid values 1 - 12): 19");
        kryoShaped.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("org.redisson.codec.Kryo5Codec$4", "decode", "Kryo5Codec.java", 199),
        });
        filter.doFilter(req, res, (rq, rs) -> {
            throw new RuntimeException("wrapped", kryoShaped);
        });
        org.mockito.Mockito.verify(res).sendRedirect("/page.jsf");
    }

    /**
     * The 2026-08-14 outage shape: the session blob is unreadable, and the FIRST attribute read is the
     * filter's own probe (a page that never touches the session in-request would otherwise defer the
     * decode to Mojarra's requestDestroyed -- valve level, where nothing recovers and the user gets a raw
     * 500). The probe must fail inside the guarded scope and recover: cookie expiry + same-path redirect,
     * and the chain must never run against the broken session.
     */
    @Test
    public void anUnreadableSessionFailsTheProbeAndRecoversBeforeTheChain() throws Exception {
        final SessionRecoveryFilter filter = new SessionRecoveryFilter();
        final jakarta.servlet.http.HttpServletRequest req = requestWithSession(null);
        final jakarta.servlet.http.HttpServletResponse res =
                org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletResponse.class);
        final java.io.InvalidClassException incompatible = new java.io.InvalidClassException(
                "org.paulsens.trip.model.Trip",
                "local class incompatible: stream classdesc serialVersionUID = 1, local = 2");
        // The marker frames live on the CAUSE: Mockito refills the THROWN exception's stack at throw
        // time, exactly as a real RedisException carries its own frames -- the decoder frames that
        // identify the failure are further down the chain there too.
        incompatible.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("org.redisson.client.handler.CommandDecoder", "decode",
                        "CommandDecoder.java", 461),
        });
        final RuntimeException decodeFailure = new RuntimeException(
                "Unexpected exception while processing command", incompatible);
        // EVERY attribute read explodes, exactly like a Valkey session whose lazy full-map load fails.
        org.mockito.Mockito.when(req.getSession(false).getAttribute(org.mockito.Mockito.anyString()))
                .thenThrow(decodeFailure);
        final java.util.concurrent.atomic.AtomicBoolean chainRan =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        filter.doFilter(req, res, (rq, rs) -> chainRan.set(true));

        Assert.assertFalse(chainRan.get(), "the chain must not run against a session that cannot be read");
        org.mockito.Mockito.verify(res).sendRedirect("/page.jsf");
        org.mockito.Mockito.verify(res).addCookie(org.mockito.Mockito.argThat(
                cookie -> "JSESSIONID".equals(cookie.getName()) && cookie.getMaxAge() == 0));
    }

    // --- host -> site resolution: the per-organization subdomain gate ---

    /**
     * A label under the org-site base that no organization owns is answered before ANY application work: no
     * session read, no chain, one static 404. All of *.unitetrip.com reaches the app (the ALB has no host
     * rules), so this is the scanner-load-shedding posture of NotFoundServlet applied to hostnames.
     */
    @Test
    public void anUnknownOrgSubdomainGetsTheStaticPageAndNeverRunsTheChain() throws Exception {
        final SessionRecoveryFilter filter = new SessionRecoveryFilter();
        final jakarta.servlet.http.HttpServletRequest req = requestWithSession(null);
        org.mockito.Mockito.when(req.getServerName()).thenReturn("no-such-tenant.unitetrip.com");
        final jakarta.servlet.http.HttpServletResponse res =
                org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletResponse.class);
        final java.io.StringWriter body = new java.io.StringWriter();
        org.mockito.Mockito.when(res.getWriter()).thenReturn(new java.io.PrintWriter(body));
        final java.util.concurrent.atomic.AtomicBoolean chainRan =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        filter.doFilter(req, res, (rq, rs) -> chainRan.set(true));

        Assert.assertFalse(chainRan.get(), "an unknown org host must never reach the application");
        org.mockito.Mockito.verify(res).setStatus(jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND);
        org.mockito.Mockito.verify(req, org.mockito.Mockito.never()).getSession(false);
        Assert.assertTrue(body.toString().contains("No such site"));
        Assert.assertFalse(body.toString().contains("no-such-tenant"),
                "the attacker-controlled Host header must never be echoed into the page");
    }

    /** A KNOWN org subdomain binds an ORG site context and the chain runs normally. */
    @Test
    public void aKnownOrgSubdomainBindsItsSiteForTheChain() throws Exception {
        // Self-provisioned rather than leaning on the FakeData slugs: suite-mates may clear the local
        // cache, which in local mode IS the org datastore. *.localhost mirrors the base-domain grammar.
        final String slug = "f" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        final org.paulsens.trip.model.Organization saved = new org.paulsens.trip.model.Organization();
        saved.setName("Filter site " + slug);
        saved.setSlug(slug);
        Assert.assertTrue(org.paulsens.trip.dynamo.DAO.getInstance().saveOrganization(saved));
        final SessionRecoveryFilter filter = new SessionRecoveryFilter();
        final jakarta.servlet.http.HttpServletRequest req = requestWithSession("bound@x.org");
        org.mockito.Mockito.when(req.getServerName()).thenReturn(slug + ".localhost");
        final java.util.concurrent.atomic.AtomicReference<org.paulsens.trip.site.SiteContext> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        filter.doFilter(req, org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletResponse.class),
                (rq, rs) -> seen.set(org.paulsens.trip.audit.RequestContext.SCOPE.get().site()));
        Assert.assertTrue(seen.get().isOrg(), "the chain must observe the resolved org site");
        Assert.assertEquals(seen.get().slug(), slug);
    }
}
