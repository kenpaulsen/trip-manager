package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.content.OrgPageBootstrap;
import org.paulsens.trip.content.PageVersions;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.web.RequestLocale;

/**
 * The language versions of the page being shown, as {@code #{pageVersions}}: which version a request reads, and
 * the bar an editor uses to move between versions, start one and delete one ({@code content/PageVersions},
 * {@code docs/i18n.md}).
 *
 * <p>Stateless like every site bean: which version a request reads is a fact about that request (its language,
 * or the {@code version} parameter an editor's link carries), never something kept in a session or a view.
 */
@Named("pageVersions")
@ApplicationScoped
public class PageVersionCommands {

    /** The request parameter by which an editor names a version outright, whatever language they read in. */
    public static final String PARAM = "version";

    /**
     * The {@code version} value that names the page's unmarked key outright. It needs a name of its own once a
     * version marked with the page's own language has been written: no language then resolves to the unmarked
     * key for an editor, yet it is still what every language without a version falls back to.
     */
    static final String ORIGINAL = "original";

    /** The {@code hreflang} of the alternate a reader with no matching language is sent to. */
    static final String X_DEFAULT = "x-default";

    /** One entry of the editor's version bar. */
    public record Tab(String tag, String name, String key, boolean written, boolean current, boolean own) {
    }

    /** One {@code <link rel="alternate">} of a page's head: a language tag (or x-default) and its address. */
    public record Alternate(String hreflang, String href) {
    }

    /**
     * The key of the version of {@code baseKey} this request reads.
     *
     * @param ownLanguage the language the page itself is written in, or null for "the site's default" (a trip's
     *     page passes the trip's language)
     */
    static String forThisRequest(final String baseKey, final Language ownLanguage) {
        return forVersion(baseKey, ownLanguage, parameter());
    }

    /** {@link #forThisRequest} on an explicit {@code version} parameter (the testable half). */
    static String forVersion(final String baseKey, final Language ownLanguage, final String version) {
        final List<Language> offered = offered();
        if (baseKey == null || offered.size() == 1 || ORIGINAL.equals(version)) {
            return baseKey;
        }
        final Optional<Language> asked = Language.forTag(version).filter(offered::contains);
        final Language wanted = asked.orElseGet(RequestContext::currentLanguage);
        return PageVersions.resolve(baseKey, ownLanguage == null ? offered.get(0) : ownLanguage, wanted,
                asked.isPresent(), PageVersionCommands::hasRows);
    }

    /** Whether this site offers more than one language, so that a page can have versions at all. */
    public boolean isOffered() {
        return offered().size() > 1;
    }

    /**
     * The bar's entries for the page whose key (any version's) is {@code pageKey}: one per language the site
     * offers, the page's own language first, and the current one the entry whose key IS {@code pageKey}.
     *
     * <p>Once a version marked with the page's own language has been written (the site's default or the trip's
     * language changed after it was), that language's tab opens the version, and the unmarked page gets a tab of
     * its own after the languages, the "original page": its language is no longer known, it is still what every
     * language without a version falls back to, and without the tab an editor reading it would see some other
     * tab marked current and could not open it from the bar at all. Its tag is blank (an unknown {@code lang})
     * and {@link #editUrl} names it {@link #ORIGINAL}.
     */
    public List<Tab> tabs(final String pageKey) {
        final String base = PageVersions.baseOf(pageKey);
        final Language own = ownLanguageOf(base);
        final List<Tab> out = new ArrayList<>(offered().stream()
                .sorted((a, b) -> Boolean.compare(b == own, a == own))
                .map(language -> tab(base, language, own, pageKey))
                .toList());
        final boolean shownOriginal = base.equals(pageKey);
        if (ownLanguageVersioned(base, own) && (shownOriginal || hasRows(base))) {
            out.add(new Tab("", Text.get("labels.pageVersion.original"), base, hasRows(base), shownOriginal, true));
        }
        return out;
    }

    /**
     * The language the version {@code pageKey} names is written in: its own tag for a version, else the page's
     * own language. A page that fell back to its own version is NOT in the reader's language, so its host marks
     * the sections with this ({@code lang}) for screen readers and translators. Blank (an unknown language) for
     * the unmarked key once a version in the own language exists: the unmarked text is then in whatever the
     * own language used to be, which nothing records.
     */
    public String languageTagOf(final String pageKey) {
        final Optional<Language> marked = PageVersions.languageOf(pageKey);
        if (marked.isPresent()) {
            return marked.get().getTag();
        }
        final String base = PageVersions.baseOf(pageKey);
        final Language own = ownLanguageOf(base);
        return ownLanguageVersioned(base, own) ? "" : own.getTag();
    }

    /** The entry for the version being shown. */
    public Tab current(final String pageKey) {
        return tabs(pageKey).stream().filter(Tab::current).findFirst().orElse(null);
    }

    /** The key of the page's own version: what a new version is copied from. */
    public String ownKey(final String pageKey) {
        return PageVersions.baseOf(pageKey);
    }

    /**
     * This same page in edit mode on one named version: the request's own address with {@code version} set and
     * every other parameter kept. The page passes {@code request.requestURI} and {@code request.queryString},
     * so the bar works on any host without each one describing its own URL. A blank tag (the original page's
     * tab) names {@link #ORIGINAL}.
     */
    public String editUrl(final String requestUri, final String queryString, final String tag) {
        final String kept = TextCommands.keptParameters(queryString, PARAM, "edit");
        final String version = tag == null || tag.isBlank() ? ORIGINAL : tag;
        return TextCommands.localPath(requestUri) + "?" + (kept.isEmpty() ? "" : kept + "&") + PARAM + "=" + version
                + "&edit=true";
    }

    /**
     * The head's {@code <link rel="alternate" hreflang>} entries for the page whose key (any version's) is
     * {@code pageKey}, so a search engine can offer each reader the version in their language: one per language
     * the site offers that the page is WRITTEN in (its own, and each version with rows), each this same address
     * with {@code ?lang=} set, plus {@code x-default}, the address with no language named. Empty for a page with
     * no version beyond its own, on a one-language site, and off a page request: a page with nothing to choose
     * between says nothing.
     */
    public List<Alternate> alternates(final String pageKey) {
        final HttpServletRequest request = SiteCommands.facesRequest();
        if (pageKey == null || request == null) {
            return List.of();
        }
        return alternates(pageKey, request.getRequestURL().toString(), request.getQueryString());
    }

    /** {@link #alternates(String)} on explicit request facts (the testable half). */
    static List<Alternate> alternates(final String pageKey, final String requestUrl, final String queryString) {
        final List<Language> offered = offered();
        if (pageKey == null || offered.size() < 2) {
            return List.of();
        }
        final String base = PageVersions.baseOf(pageKey);
        final Language own = ownLanguageOf(base);
        final List<Language> written = offered.stream()
                .filter(language -> language == own || hasRows(PageVersions.keyFor(base, language)))
                .toList();
        if (written.stream().allMatch(language -> language == own)) {
            return List.of();
        }
        // The page's own address: its other parameters kept, and nothing that picks a language or a mode.
        final String kept = TextCommands.keptParameters(queryString, RequestLocale.CHOICE, PARAM, "edit");
        final String plain = requestUrl + (kept.isEmpty() ? "" : "?" + kept);
        final String joiner = kept.isEmpty() ? "?" : "&";
        final List<Alternate> out = new ArrayList<>();
        for (final Language language : written) {
            out.add(new Alternate(language.getTag(),
                    plain + joiner + RequestLocale.CHOICE + "=" + language.getTag()));
        }
        out.add(new Alternate(X_DEFAULT, plain));
        return out;
    }

    private static Tab tab(final String base, final Language language, final Language own, final String shown) {
        // A version marked with the own language (written before the site's default or the trip's language
        // changed) is that language's page, as PageVersions.resolve serves it: it keeps its tab, and stays a
        // version that can be deleted. The unmarked page then gets the original-page tab (tabs).
        final String version = PageVersions.keyFor(base, language);
        final boolean ownPage = language == own && !hasRows(version);
        final String key = ownPage ? base : version;
        return new Tab(language.getTag(), language.getDisplayName(), key, ownPage || hasRows(key),
                Objects.equals(key, shown), ownPage);
    }

    /** Whether a version marked with the page's own language has been written (see {@link #tabs}). */
    private static boolean ownLanguageVersioned(final String base, final Language own) {
        return hasRows(PageVersions.keyFor(base, own));
    }

    /** A trip page is in {@link OrgPageBootstrap#pageLanguageOf}; every other page in its site's default language. */
    private static Language ownLanguageOf(final String baseKey) {
        final String tripId = OrgPageBootstrap.tripOf(baseKey);
        if (tripId == null) {
            return offered().get(0);
        }
        return DAO.getInstance().getTrip(tripId, Cached.YES).map(OrgPageBootstrap::pageLanguageOf)
                .orElseGet(() -> offered().get(0));
    }

    private static List<Language> offered() {
        return new ConfigCommands().siteLanguages();
    }

    private static boolean hasRows(final String key) {
        return !DAO.getInstance().getContentForSection(key, Cached.YES).isEmpty();
    }

    /** The {@code version} parameter of a page request, or null off one (the API, a background sender). */
    private static String parameter() {
        final FacesContext faces = FacesContext.getCurrentInstance();
        return faces == null ? null : faces.getExternalContext().getRequestParameterMap().get(PARAM);
    }
}
