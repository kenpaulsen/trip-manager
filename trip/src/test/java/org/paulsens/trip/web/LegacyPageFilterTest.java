package org.paulsens.trip.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.site.SiteContext;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link LegacyPageFilter}: loads the deployment's data, answers a hand-written page on an org host with that org's
 * Site Page or a 404, links out on a shared host, and otherwise hands the request on untouched -- never touching
 * the session either way (the anonymous rule).
 */
public class LegacyPageFilterTest {

    private static final SiteContext ACME = SiteContext.org(Organization.Id.from(FakeData.ACME_ORG_ID), "acme",
            "acme.localhost");
    private static final SiteContext SHARED = SiteContext.shared("localhost");

    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;

    /**
     * The fake data seeds on the DAO's first use; that must not happen inside a test's org-site binding, where the
     * seed's own tenant checks would refuse it.
     */
    @BeforeClass
    public void seedOutsideAnySite() {
        DAO.getInstance().getOrganizations(Cached.NO);
    }

    @BeforeMethod
    public void setUp() {
        request = Mockito.mock(HttpServletRequest.class);
        response = Mockito.mock(HttpServletResponse.class);
        chain = Mockito.mock(FilterChain.class);
        Mockito.when(request.getContextPath()).thenReturn("");
        Mockito.when(request.getServerName()).thenReturn("localhost");
        Mockito.when(request.getServerPort()).thenReturn(8080);
    }

    @AfterMethod
    public void uninstall() {
        LegacyPages.install(null);
    }

    private static FilterConfig configServing(final String param, final String resource, final String body) {
        final FilterConfig config = Mockito.mock(FilterConfig.class);
        final ServletContext context = Mockito.mock(ServletContext.class);
        Mockito.when(config.getInitParameter(LegacyPageFilter.CONFIG_PARAM)).thenReturn(param);
        Mockito.when(config.getServletContext()).thenReturn(context);
        if (body != null) {
            Mockito.when(context.getResourceAsStream(resource))
                    .thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        }
        return config;
    }

    private static LegacyPageFilter loaded() throws ServletException {
        final LegacyPageFilter filter = new LegacyPageFilter();
        filter.init(configServing(null, LegacyPageFilter.DEFAULT_CONFIG, LegacyPagesTest.DATA));
        return filter;
    }

    private void get(final LegacyPageFilter filter, final SiteContext site, final String servletPath)
            throws Exception {
        Mockito.when(request.getServletPath()).thenReturn(servletPath);
        ScopedValue.where(RequestContext.SCOPE, RequestContext.of(null, null, site))
                .call(() -> run(filter));
    }

    private Void run(final LegacyPageFilter filter) throws IOException, ServletException {
        filter.doFilter(request, response, chain);
        return null;
    }

    @Test
    public void onAnOrgHostTheOrgsOwnSitePageIsWhereAHandWrittenPageGoes() throws Exception {
        get(loaded(), ACME, "/board/index.jsf");
        Mockito.verify(response).sendRedirect("/page.jsf?name=board");
        Mockito.verifyNoInteractions(chain);
    }

    @Test
    public void onAnOrgHostAHandWrittenPageTheOrgLacksIs404() throws Exception {
        get(loaded(), ACME, "/dm/day1.jsf");
        Mockito.verify(response).sendError(HttpServletResponse.SC_NOT_FOUND);
        Mockito.verifyNoInteractions(chain);
        Mockito.verify(request, Mockito.never()).getSession();
        Mockito.verify(request, Mockito.never()).getSession(ArgumentMatchers.anyBoolean());
    }

    @Test
    public void onASharedHostALinkOutPageGoesToTheLinkOutOrgsSite() throws Exception {
        get(loaded(), SHARED, "/medjugorje/about/mission.jsf");
        Mockito.verify(response).sendRedirect("http://acme.localhost:8080/page.jsf?name=mission");
        Mockito.verifyNoInteractions(chain);
    }

    @Test
    public void everythingElsePassesUntouched() throws Exception {
        final LegacyPageFilter filter = loaded();
        get(filter, SHARED, "/dm/day1.jsf");
        get(filter, SHARED, "/medjugorje/trips/may2022.jsf");
        get(filter, ACME, "/trip/tripDetails.jsf");
        Mockito.verify(chain, Mockito.times(3)).doFilter(request, response);
        Mockito.verify(response, Mockito.never()).sendRedirect(ArgumentMatchers.anyString());
        Mockito.verify(response, Mockito.never()).sendError(ArgumentMatchers.anyInt());
    }

    @Test
    public void initInstallsTheDataForTheMenu() throws Exception {
        loaded();
        Assert.assertNotSame(LegacyPages.installed(), LegacyPages.NONE);
    }

    @Test
    public void aNamedResourceIsReadInstead() throws Exception {
        final LegacyPageFilter filter = new LegacyPageFilter();
        filter.init(configServing(" /WEB-INF/other.json ", "/WEB-INF/other.json", LegacyPagesTest.DATA));
        get(filter, ACME, "/dm/index.jsf");
        Mockito.verify(response).sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    @Test
    public void withoutTheDataFileEveryRequestPasses() throws Exception {
        final LegacyPageFilter filter = new LegacyPageFilter();
        filter.init(configServing("", LegacyPageFilter.DEFAULT_CONFIG, null));
        get(filter, ACME, "/dm/index.jsf");
        Mockito.verify(chain).doFilter(request, response);
        Assert.assertSame(LegacyPages.installed(), LegacyPages.NONE);
    }

    @Test
    public void unreadableDataFailsTheStart() {
        final LegacyPageFilter filter = new LegacyPageFilter();
        final String relative = "{\"handWritten\": [\"dm\"]}";
        Assert.assertThrows(ServletException.class,
                () -> filter.init(configServing(null, LegacyPageFilter.DEFAULT_CONFIG, relative)));
        Assert.assertThrows(ServletException.class,
                () -> filter.init(configServing(null, LegacyPageFilter.DEFAULT_CONFIG, "{not json")));
    }

    @Test
    public void aNonHttpRequestPasses() throws Exception {
        final ServletRequest plain = Mockito.mock(ServletRequest.class);
        final ServletResponse plainResponse = Mockito.mock(ServletResponse.class);
        loaded().doFilter(plain, plainResponse, chain);
        Mockito.verify(chain).doFilter(plain, plainResponse);
        final ServletResponse other = Mockito.mock(ServletResponse.class);
        loaded().doFilter(request, other, chain);
        Mockito.verify(chain).doFilter(request, other);
    }
}
