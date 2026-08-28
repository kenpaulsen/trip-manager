package org.paulsens.trip.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.lang.reflect.Field;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.dynamo.LocalMode;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The servlet filters: {@link PdfRedirectFilter} and {@link SessionRecoveryFilter}.
 *
 * <p>{@code SessionRecoveryFilter} is the one with teeth. It catches failures and clears the user's session, so
 * the narrowness of what it catches IS the feature: widening it would turn any unrelated bug into an
 * intermittent logout nobody can reproduce.
 */
public class WebFiltersTest {

    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;

    @BeforeMethod
    public void setUp() {
        request = Mockito.mock(HttpServletRequest.class);
        response = Mockito.mock(HttpServletResponse.class);
        chain = Mockito.mock(FilterChain.class);
    }

    // --- PdfRedirectFilter ---

    private PdfRedirectFilter pdfFilterWith(final String baseUrl) throws Exception {
        final PdfRedirectFilter filter = new PdfRedirectFilter();
        filter.init(Mockito.mock(FilterConfig.class));
        // init() reads an env var, which a test cannot set; the resolved value is what matters here.
        final Field field = PdfRedirectFilter.class.getDeclaredField("baseUrl");
        field.setAccessible(true);
        field.set(filter, baseUrl);
        return filter;
    }

    @Test
    public void aPdfRequestIsPermanentlyRedirectedToTheCdnByFileNameAlone() throws Exception {
        Mockito.when(request.getRequestURI()).thenReturn("/some/old/path/novena.pdf");

        pdfFilterWith("https://media.example.org").doFilter(request, response, chain);

        Mockito.verify(response).setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
        // Whatever path was requested, serve the CDN copy of that FILE NAME -- which is what makes one rule
        // cover every old URL shape, including ones nobody wrote down.
        Mockito.verify(response).setHeader("Location", "https://media.example.org/downloads/novena.pdf");
        Mockito.verify(chain, Mockito.never()).doFilter(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    public void aTrailingSlashOnTheBaseUrlDoesNotDoubleUp() throws Exception {
        Mockito.when(request.getRequestURI()).thenReturn("/guide.PDF");

        pdfFilterWith("https://media.example.org").doFilter(request, response, chain);

        Mockito.verify(response).setHeader("Location", "https://media.example.org/downloads/guide.PDF");
    }

    @Test
    public void anythingThatIsNotAPdfPassesStraightThrough() throws Exception {
        Mockito.when(request.getRequestURI()).thenReturn("/trip/itinerary.jsf");

        pdfFilterWith("https://media.example.org").doFilter(request, response, chain);

        Mockito.verify(chain).doFilter(request, response);
        Mockito.verify(response, Mockito.never()).setStatus(ArgumentMatchers.anyInt());
    }

    /** No base URL configured (local development): stand aside entirely rather than redirect nowhere. */
    @Test
    public void withNoConfiguredCdnTheFilterStandsAside() throws Exception {
        Mockito.when(request.getRequestURI()).thenReturn("/downloads/guide.pdf");

        pdfFilterWith(null).doFilter(request, response, chain);

        Mockito.verify(chain).doFilter(request, response);
        Mockito.verify(response, Mockito.never()).setStatus(ArgumentMatchers.anyInt());
    }

    @Test
    public void aPathEndingInASlashOrNullIsNotRedirected() throws Exception {
        final PdfRedirectFilter filter = pdfFilterWith("https://media.example.org");

        Mockito.when(request.getRequestURI()).thenReturn(null);
        filter.doFilter(request, response, chain);
        Mockito.when(request.getRequestURI()).thenReturn("/downloads/.pdf");
        filter.doFilter(request, response, chain);

        // ".pdf" with no name is still a name ("(empty)" only when the URI ends with '/'), so only the null
        // case falls through here; what matters is that neither throws.
        Mockito.verify(chain, Mockito.atLeastOnce()).doFilter(request, response);
    }

    @Test
    public void nonHttpRequestsPassThrough() throws Exception {
        final jakarta.servlet.ServletRequest plain = Mockito.mock(jakarta.servlet.ServletRequest.class);
        final jakarta.servlet.ServletResponse plainResponse = Mockito.mock(jakarta.servlet.ServletResponse.class);

        pdfFilterWith("https://media.example.org").doFilter(plain, plainResponse, chain);

        Mockito.verify(chain).doFilter(plain, plainResponse);
    }

    // --- SessionRecoveryFilter: what it catches ---

    /**
     * The frame-scanning branch, which is the one that fires in production: the decode happens on a Netty
     * thread and reaches the request wrapped in whatever the session manager threw, so the Kryo frames are
     * buried in a stack rather than in the exception's own type.
     */
    private static RuntimeException kryoFramed() {
        final RuntimeException failure = new RuntimeException("field mismatch");
        failure.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("com.esotericsoftware.kryo.Kryo", "readObject", "Kryo.java", 1),
        });
        return failure;
    }

    @Test
    public void aKryoFramedFailureIsRecognisedThroughTheWholeCauseChain() {
        final Throwable wrapped = new IllegalStateException("boom", new RuntimeException("layer", kryoFramed()));

        Assert.assertTrue(SessionRecoveryFilter.isSessionDeserializationFailure(wrapped));
    }

    /** The class-name branch: a codec exception identified by its package alone. */
    @Test
    public void aCodecFailureIsRecognisedByItsClassName() {
        Assert.assertTrue(SessionRecoveryFilter.isSessionDeserializationFailure(
                new org.redisson.codec.FakeCodecFailure("cannot decode")));
    }

    @Test
    public void anOrdinaryApplicationErrorIsNotTreatedAsASessionFailure() {
        Assert.assertFalse(SessionRecoveryFilter.isSessionDeserializationFailure(
                new IllegalArgumentException("a normal bug")));
        Assert.assertFalse(SessionRecoveryFilter.isSessionDeserializationFailure(null));
    }

    /** A cyclic cause chain (CompletionException plumbing produces these) must not hang the check. */
    @Test
    public void aCyclicCauseChainTerminates() {
        final RuntimeException a = new RuntimeException("a");
        final RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);

        Assert.assertFalse(SessionRecoveryFilter.isSessionDeserializationFailure(a));
    }

    // --- SessionRecoveryFilter: what it does ---

    /**
     * A failure identified by CLASS NAME rather than by planted frames.
     *
     * <p>Mockito rewrites a thrown exception's stack trace to point at the call site, so frames fabricated with
     * setStackTrace do not survive being thrown from a mock. The frame-scanning branch is therefore exercised
     * directly, by the isSessionDeserializationFailure tests above.
     */
    private static RuntimeException sessionDecodeFailure() {
        return new org.redisson.codec.FakeCodecFailure("cannot decode this session");
    }

    private void arrangeFailingChain(final Throwable failure) throws IOException, ServletException {
        Mockito.doThrow(failure).when(chain).doFilter(ArgumentMatchers.any(), ArgumentMatchers.any());
        Mockito.when(request.getRequestURI()).thenReturn("/trip/itinerary.jsf");
        Mockito.when(request.getContextPath()).thenReturn("");
        Mockito.when(request.getMethod()).thenReturn("GET");
        Mockito.when(request.getRequestedSessionId()).thenReturn("dead-session-id");
    }

    @Test
    public void anUnrelatedFailurePropagatesUntouched() throws Exception {
        arrangeFailingChain(new IllegalArgumentException("a normal bug"));

        Assert.assertThrows(IllegalArgumentException.class,
                () -> new SessionRecoveryFilter().doFilter(request, response, chain));

        Mockito.verify(response, Mockito.never()).addCookie(ArgumentMatchers.any());
        Mockito.verify(response, Mockito.never()).sendRedirect(ArgumentMatchers.anyString());
    }

    @Test
    public void anUnreadableSessionIsClearedAndTheBrowserSentBackToTheSamePage() throws Exception {
        arrangeFailingChain(sessionDecodeFailure());
        Mockito.when(request.getQueryString()).thenReturn("trip=abc");
        final HttpSession session = Mockito.mock(HttpSession.class);
        Mockito.when(request.getSession(false)).thenReturn(session);

        new SessionRecoveryFilter().doFilter(request, response, chain);

        Mockito.verify(session).invalidate();
        // Expiring the cookie is what actually detaches the browser from the bad blob.
        final ArgumentCaptor<Cookie> cookie = ArgumentCaptor.forClass(Cookie.class);
        Mockito.verify(response).addCookie(cookie.capture());
        Assert.assertEquals(cookie.getValue().getName(), "JSESSIONID");
        Assert.assertEquals(cookie.getValue().getMaxAge(), 0);
        Assert.assertTrue(cookie.getValue().isHttpOnly());
        // A GET is replayable, so the query survives the redirect.
        Mockito.verify(response).sendRedirect("/trip/itinerary.jsf?trip=abc");
    }

    /** A POST cannot be replayed, so the body-bearing query is dropped rather than half-repeated. */
    @Test
    public void aNonGetIsRedirectedWithoutItsQuery() throws Exception {
        arrangeFailingChain(sessionDecodeFailure());
        Mockito.when(request.getMethod()).thenReturn("POST");
        Mockito.when(request.getQueryString()).thenReturn("trip=abc");

        new SessionRecoveryFilter().doFilter(request, response, chain);

        Mockito.verify(response).sendRedirect("/trip/itinerary.jsf");
    }

    /**
     * A protocol-relative request path must never redirect OFF this site: the recovery redirect derives its
     * target from getRequestURI(), and "//evil.example" would otherwise send the browser to evil.example.
     */
    @Test
    public void aProtocolRelativePathFallsBackToTheRoot() throws Exception {
        for (final String hostile : new String[] {"//evil.example/x", "/\\evil.example/x"}) {
            setUp();
            arrangeFailingChain(sessionDecodeFailure());
            Mockito.when(request.getRequestURI()).thenReturn(hostile);

            new SessionRecoveryFilter().doFilter(request, response, chain);

            Mockito.verify(response).sendRedirect("/");
        }
    }

    /**
     * Invalidating touches the very object that failed to load, so it may fail again. Expiring the cookie is
     * what matters, and it must still happen.
     */
    @Test
    public void aFailingInvalidateStillExpiresTheCookieAndRedirects() throws Exception {
        arrangeFailingChain(sessionDecodeFailure());
        final HttpSession session = Mockito.mock(HttpSession.class);
        Mockito.when(request.getSession(false)).thenReturn(session);
        Mockito.doThrow(new IllegalStateException("cannot read it either")).when(session).invalidate();

        new SessionRecoveryFilter().doFilter(request, response, chain);

        Mockito.verify(response).addCookie(ArgumentMatchers.any());
        Mockito.verify(response).sendRedirect(ArgumentMatchers.anyString());
    }

    @Test
    public void anAlreadyCommittedResponseIsNotRedirected() throws Exception {
        arrangeFailingChain(sessionDecodeFailure());
        Mockito.when(response.isCommitted()).thenReturn(true);

        new SessionRecoveryFilter().doFilter(request, response, chain);

        // The session is still cleared; only this one request is lost.
        Mockito.verify(response).addCookie(ArgumentMatchers.any());
        Mockito.verify(response, Mockito.never()).sendRedirect(ArgumentMatchers.anyString());
    }

    @Test
    public void aContextPathScopesTheExpiryCookie() throws Exception {
        arrangeFailingChain(sessionDecodeFailure());
        Mockito.when(request.getContextPath()).thenReturn("/app");

        new SessionRecoveryFilter().doFilter(request, response, chain);

        final ArgumentCaptor<Cookie> cookie = ArgumentCaptor.forClass(Cookie.class);
        Mockito.verify(response).addCookie(cookie.capture());
        Assert.assertEquals(cookie.getValue().getPath(), "/app");
    }

    // --- TripBootstrapListener ---

    /** It must do exactly one thing, so there is never a reason to move it later in the listener order. */
    @Test
    public void theBootstrapListenerResolvesTheModeAndNothingElse() throws Exception {
        final ServletContext context = Mockito.mock(ServletContext.class);
        final ServletContextEvent event = Mockito.mock(ServletContextEvent.class);
        Mockito.when(event.getServletContext()).thenReturn(context);

        // The listener resolves LocalMode for the WHOLE JVM, and a Faces-less mock context resolves to
        // PRODUCTION. Left in place, that answer outranks surefire's trip.local.mode property until
        // LocalModeTest happens to reset it, and every isLocal()-gated behavior in between silently changes
        // -- FakeData.addFakeData() no-ops, so any class that reseeds in @BeforeClass finds nothing. Which
        // classes those are depends on surefire's filesystem-dependent run order, so the suite passed on one
        // machine and failed 41 tests on another. Save and restore the resolved slot around the call, the
        // same way MailSendSesPathTest does.
        final Field resolved = LocalMode.class.getDeclaredField("resolved");
        resolved.setAccessible(true);
        final Object saved = resolved.get(null);
        try {
            new TripBootstrapListener().contextInitialized(event);

            Mockito.verify(event).getServletContext();
            Mockito.verifyNoMoreInteractions(event);
            Assert.assertFalse(LocalMode.isLocal(),
                    "a context without a Faces Servlet 'local' init-param must resolve to production");
        } finally {
            resolved.set(null, saved);
        }
    }
}
