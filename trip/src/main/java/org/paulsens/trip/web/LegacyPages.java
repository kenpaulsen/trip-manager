package org.paulsens.trip.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.action.SiteCommands;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.content.OrgPageBootstrap;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.LocalMode;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.SitePage;
import org.paulsens.trip.site.SiteContext;

/**
 * Which of the shared site's addresses are HAND-WRITTEN pages (XHTML files of content, as opposed to the
 * application), and what each one answers on which host: the rule {@link LegacyPageFilter} applies, as a pure
 * {@link #decide decision} over data read from the deployment's {@code WEB-INF/legacy-pages.json}. The data lives
 * there, in the private webapp, because the pages and the organization they link out to are that deployment's own;
 * this class knows no path and no organization (docs/org-admin.md, "Hand-written shared pages").
 *
 * <ul>
 *   <li>On an ORGANIZATION's host (and on any host that is not a shared one) every address under a hand-written
 *       prefix answers exactly like {@code /board} does there: a redirect to that org's Site Page of the mapped
 *       name when it has one, else 404. Without this, {@code acme.unitetrip.com/dm} rendered the shared site's
 *       novena in Acme's look.</li>
 *   <li>On a SHARED host an address in the link-out list redirects to the Site Page of its name on the site of the
 *       organization the data names (its custom domain, else its subdomain), so the shared site no longer keeps a
 *       second copy of that organization's pages. While that organization holds no such page, the hand-written
 *       page keeps rendering: a link out never lands on a 404.</li>
 *   <li>Everything else passes through untouched.</li>
 * </ul>
 */
@Slf4j
public final class LegacyPages {

    /** What a request for an address gets. */
    public enum Kind {
        /** Serve the address as usual. */
        PASS,
        /** A 302 to {@link Decision#location()}. */
        REDIRECT,
        /** A 404. */
        NOT_FOUND
    }

    /**
     * The answer for one request.
     *
     * @param kind     what to do
     * @param location the redirect target (site-relative for this host's own Site Page, absolute for a link out);
     *                 null unless {@link Kind#REDIRECT}
     */
    public record Decision(Kind kind, String location) {
        static final Decision PASS = new Decision(Kind.PASS, null);
        static final Decision NOT_FOUND = new Decision(Kind.NOT_FOUND, null);

        static Decision redirect(final String location) {
            return new Decision(Kind.REDIRECT, location);
        }
    }

    /** No hand-written pages at all: what a deployment without the data file gets (every request passes). */
    public static final LegacyPages NONE = new LegacyPages(List.of(), Map.of(), Map.of(), null, null);

    /** A Site Page's name ({@link SitePage}): the data may only map an address to a name a Site Page can have. */
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String[] PAGE_SUFFIXES = {".jsf", ".xhtml", ".faces"};
    private static final String FACES_PREFIX = "/faces";
    private static final String INDEX = "/index";

    /** The data the filter loaded, for the menu's links ({@code #{legacyPages}}); {@link #NONE} until it has. */
    private static volatile LegacyPages installed = NONE;

    private final List<String> prefixes;
    private final Map<String, String> sitePages;
    private final Map<String, String> linkOut;
    private final String linkOutSlug;
    private final String localLinkOutSlug;

    LegacyPages(final List<String> prefixes, final Map<String, String> sitePages, final Map<String, String> linkOut,
            final String linkOutSlug, final String localLinkOutSlug) {
        this.prefixes = List.copyOf(prefixes);
        this.sitePages = Map.copyOf(sitePages);
        this.linkOut = Map.copyOf(linkOut);
        this.linkOutSlug = linkOutSlug;
        this.localLinkOutSlug = localLinkOutSlug;
    }

    /**
     * Reads the data file. Malformed data fails loudly (the filter then fails the deployment's start, which the
     * browser tests and the deploy's health check both see) rather than quietly reopening the tenancy gap.
     *
     * @throws IOException when the stream is not JSON
     * @throws IllegalArgumentException when an address or a page name is not one the rule can use
     */
    public static LegacyPages parse(final InputStream in) throws IOException {
        final JsonNode root = MAPPER.readTree(in);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("legacy pages: the data must be a JSON object");
        }
        final List<String> prefixes = new ArrayList<>();
        root.path("handWritten").forEach(node -> prefixes.add(address(node.asText())));
        final Map<String, String> sitePages = new HashMap<>();
        final Iterator<Map.Entry<String, JsonNode>> fields = root.path("sitePages").fields();
        while (fields.hasNext()) {
            final Map.Entry<String, JsonNode> field = fields.next();
            sitePages.put(address(field.getKey()), pageName(field.getValue().asText()));
        }
        final JsonNode out = root.path("linkOut");
        final Map<String, String> linkOut = new HashMap<>();
        out.path("paths").forEach(node -> linkOut.put(address(node.asText()), mappedName(sitePages, node.asText())));
        return new LegacyPages(prefixes, sitePages, linkOut, text(out.path("slug")), text(out.path("localSlug")));
    }

    /** Makes {@code pages} the data the menu reads (the filter calls this once it has loaded the file). */
    static void install(final LegacyPages pages) {
        installed = pages == null ? NONE : pages;
    }

    /** The data in force: what the filter loaded, else {@link #NONE}. */
    public static LegacyPages installed() {
        return installed;
    }

    /**
     * The address a request names, in the form the data uses: the servlet path and path info joined, without
     * the {@code /faces} prefix mapping, the page suffix ({@code .jsf}, {@code .xhtml}, {@code .faces}), a
     * trailing slash or a trailing {@code /index}. So {@code /dm/}, {@code /dm/index.jsf} and
     * {@code /faces/dm/index.xhtml} are all {@code /dm}.
     */
    public static String canonical(final String servletPath, final String pathInfo) {
        String path = (servletPath == null ? "" : servletPath) + (pathInfo == null ? "" : pathInfo);
        if (FACES_PREFIX.equals(servletPath)) {
            path = pathInfo == null ? "" : pathInfo;
        }
        for (final String suffix : PAGE_SUFFIXES) {
            if (path.endsWith(suffix)) {
                path = path.substring(0, path.length() - suffix.length());
                break;
            }
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.endsWith(INDEX)) {
            path = path.substring(0, path.length() - INDEX.length());
        }
        return path.isEmpty() ? "/" : path;
    }

    /**
     * What a request for the {@link #canonical} address {@code path} gets on {@code site}.
     *
     * @param siteHasPage whether THIS host's organization has a Site Page (not a link) of a name; asked only on a
     *                    host that is not a shared one
     * @param linkOutUrl  the absolute address of a name's Site Page on the link-out organization's site, or null
     *                    when there is none to send a visitor to; asked only on a shared host
     */
    public Decision decide(final String path, final SiteContext site, final Predicate<String> siteHasPage,
            final Function<String, String> linkOutUrl) {
        if (site.isShared()) {
            final String url = linkOutUrlOf(path, linkOutUrl);
            return url == null ? Decision.PASS : Decision.redirect(url);
        }
        if (!handWritten(path)) {
            return Decision.PASS;
        }
        final String name = sitePages.get(path);
        return name != null && siteHasPage.test(name) ? Decision.redirect(SitePage.PATH + name) : Decision.NOT_FOUND;
    }

    /** Whether {@code path} (canonical) is one of the hand-written pages: it is, or is under, a prefix. */
    boolean handWritten(final String path) {
        return prefixes.stream().anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
    }

    /**
     * The menu's link for the shared site's entry {@code rawPath} ({@code /dm}): the link-out organization's Site
     * Page when the address links out on this host, else {@code rawPath} itself, unchanged.
     */
    public String href(final String rawPath, final SiteContext site, final HttpServletRequest request) {
        if (rawPath == null || !site.isShared()) {
            return rawPath;
        }
        final String url = linkOutUrlOf(canonical(rawPath, null), linkOutUrl(request));
        return url == null ? rawPath : url;
    }

    private String linkOutUrlOf(final String path, final Function<String, String> linkOutUrl) {
        final String name = linkOut.get(path);
        return name == null ? null : linkOutUrl.apply(name);
    }

    /**
     * The link-out resolver for a request: a name's Site Page on the site of the organization holding the
     * link-out slug, addressed as the admin pages address that site ({@link SiteCommands#orgSiteRoot}: its custom
     * domain, else its subdomain, {@code {slug}.localhost} from a localhost request). Null for a name that
     * organization has no page of, and for every name when no organization holds the slug. Lazy: the filter
     * hands it to every request, and only a link-out address on a shared host ever asks it.
     */
    public Function<String, String> linkOutUrl(final HttpServletRequest request) {
        return name -> sitePageUrlOf(name, request);
    }

    private String sitePageUrlOf(final String name, final HttpServletRequest request) {
        final Organization target = orgWithSlug(linkOutSlug(LocalMode.isLocal()));
        return holdsPage(target, name)
                ? SiteCommands.orgSiteRoot(target, request) + SitePage.PATH + name
                        + langParam(RequestContext.currentLanguage(), new ConfigCommands().siteLanguages(target),
                                RequestLocale.choiceOf(request, new ConfigCommands().siteLanguages()).orElse(null))
                : null;
    }

    /**
     * The {@code &lang=} a link-out carries: the visitor's language, when they CHOSE it on this host and it is the
     * language this page is in ({@code choice}, null for none; not a stale cookie or a pick this site does not offer),
     * and the other site offers it among two or more. The choice is a cookie of this host, which the other
     * organization's domain never sees, so without it that site follows its own cookie or the browser even against
     * the choice. A language this host only inferred (its default, the browser's) is not sent: the other site takes a
     * {@code lang} it is given as a pick and remembers it for months, over the browser's own preference.
     */
    static String langParam(final Language current, final List<Language> offered, final Language choice) {
        return current == choice && offered.size() > 1 && offered.contains(current)
                ? "&" + RequestLocale.CHOICE + "=" + current.getTag()
                : "";
    }

    /** The slug of the organization the shared site links out to: the local one in local mode. */
    String linkOutSlug(final boolean local) {
        return local ? localLinkOutSlug : linkOutSlug;
    }

    /** Whether {@code org} has a Site Page (not a link) called {@code name}; false for no org. */
    static boolean holdsPage(final Organization org, final String name) {
        return org != null && org.getSitePages() != null
                && org.getSitePages().stream().anyMatch(page -> !page.isLink() && page.name().equals(name));
    }

    /** The organization whose site {@code slug} names; null for none or an unreadable list. */
    static Organization orgWithSlug(final String slug) {
        return SiteCommands.orgWithSlug(slug).orElse(null);
    }

    /** The organization of an org host, from the cache; null on any other site or for an unreadable row. */
    static Organization orgOf(final SiteContext site) {
        if (site.orgId() == null) {
            return null;
        }
        try {
            final Optional<Organization> org = DAO.getInstance().getOrganization(site.orgId(), Cached.YES);
            return org.orElse(null);
        } catch (final RuntimeException ex) {
            log.warn("Unable to read organization {} for a hand-written page", site.orgId(), ex);
            return null;
        }
    }

    private static String address(final String raw) {
        if (raw == null || !raw.startsWith("/") || raw.length() < 2 || !raw.equals(canonical(raw, null))) {
            throw new IllegalArgumentException("legacy pages: '" + raw
                    + "' is not an address in canonical form (/dir/page, no suffix, no trailing / or /index)");
        }
        return raw;
    }

    private static String pageName(final String raw) {
        if (raw == null || !OrgPageBootstrap.isPageName(raw)) {
            throw new IllegalArgumentException("legacy pages: '" + raw + "' is not a Site Page name");
        }
        return raw;
    }

    private static String mappedName(final Map<String, String> sitePages, final String path) {
        final String name = sitePages.get(path);
        if (name == null) {
            throw new IllegalArgumentException("legacy pages: link-out address '" + path
                    + "' has no Site Page name in sitePages");
        }
        return name;
    }

    private static String text(final JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText().trim() : null;
    }
}
