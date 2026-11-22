package org.paulsens.trip.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.site.SiteContext;

/**
 * Applies {@link LegacyPages}: the shared site's hand-written pages answer on an organization's host like
 * {@code /board} does (that org's Site Page of the same name, else 404), and on a shared host the ones another
 * organization now holds as Site Pages redirect there. The data is the deployment's
 * {@code WEB-INF/legacy-pages.json} (init-param {@code config} names another resource); without it every request
 * passes.
 *
 * <p>Mapped after {@code SessionRecoveryFilter}, which binds the request's {@link SiteContext}, and
 * {@code SecurityHeadersFilter}, so the redirects carry the headers too; ahead of {@code RememberMeFilter}, so a
 * request this filter answers never restores a sign-in (no session, no cookie: the anonymous rule). It reads only
 * the site and the cached organization rows, and never touches the session.
 */
@Slf4j
public class LegacyPageFilter implements Filter {

    static final String CONFIG_PARAM = "config";
    static final String DEFAULT_CONFIG = "/WEB-INF/legacy-pages.json";

    private LegacyPages pages = LegacyPages.NONE;

    @Override
    public void init(final FilterConfig config) throws ServletException {
        final String param = config.getInitParameter(CONFIG_PARAM);
        final String resource = param == null || param.isBlank() ? DEFAULT_CONFIG : param.trim();
        try (InputStream in = config.getServletContext().getResourceAsStream(resource)) {
            if (in == null) {
                log.info("No {}: no hand-written shared pages, every request passes", resource);
                pages = LegacyPages.NONE;
            } else {
                pages = LegacyPages.parse(in);
                log.info("Hand-written shared pages read from {}", resource);
            }
        } catch (final IOException | IllegalArgumentException ex) {
            throw new ServletException("Unreadable hand-written page data " + resource, ex);
        }
        LegacyPages.install(pages);
    }

    @Override
    public void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest req) || !(response instanceof HttpServletResponse resp)) {
            chain.doFilter(request, response);
            return;
        }
        final SiteContext site = SiteContext.current();
        final String path = LegacyPages.canonical(req.getServletPath(), req.getPathInfo());
        final LegacyPages.Decision decision = pages.decide(path, site, name -> hasPage(site, name),
                pages.linkOutUrl(req));
        switch (decision.kind()) {
            case REDIRECT -> resp.sendRedirect(decision.location().startsWith("/")
                    ? req.getContextPath() + decision.location() : decision.location());
            case NOT_FOUND -> resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            default -> chain.doFilter(request, response);
        }
    }

    private static boolean hasPage(final SiteContext site, final String name) {
        final Organization org = LegacyPages.orgOf(site);
        return LegacyPages.holdsPage(org, name);
    }
}
