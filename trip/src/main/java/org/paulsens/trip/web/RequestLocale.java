package org.paulsens.trip.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.i18n.LanguageChooser;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.security.SecureCookies;

/**
 * Decides the language one request is answered in ({@code docs/i18n.md}, "Who decides the language") and
 * records a visitor's explicit choice in a cookie. Called once per request by {@code SessionRecoveryFilter},
 * inside the bound {@link RequestContext} (the site's offered languages are an org-overridable setting, read
 * through the site).
 *
 * <p>Three constraints shape it:
 * <ul>
 *   <li><b>No session, ever.</b> An anonymous page must not create one (issue medjugorje#19), so the explicit
 *       choice is a plain cookie and the session is only read, never asked for.</li>
 *   <li><b>Free while one language is offered.</b> A site that offers only English, which is every site until
 *       a language is switched on, returns before any cookie, header or person is looked at.</li>
 *   <li><b>Never the reason a request fails.</b> Any error here answers in English.</li>
 * </ul>
 */
@Slf4j
public final class RequestLocale {

    /** The cookie holding a visitor's explicit choice; also the query parameter that makes one. */
    public static final String CHOICE = "lang";
    /** Half a year: long enough to feel permanent, and renewed by every later switch. */
    private static final int REMEMBER_SECONDS = 180 * 24 * 60 * 60;
    private static final Pattern QUERY_CHOICE = Pattern.compile("(?:^|&)" + CHOICE + "=([A-Za-z_-]{2,12})(?:&|$)");
    private static final Pattern APP_AGENT = Pattern.compile("^UniteTrip/(\\d+(?:\\.\\d+)*)");

    private RequestLocale() {
    }

    /** The request's language; a {@code ?lang=} on a page GET is remembered on the way. */
    public static Locale resolve(final HttpServletRequest request, final HttpServletResponse response,
            final RequestContext context) {
        try {
            return resolveOffered(request, response, context).getLocale();
        } catch (final RuntimeException ex) {
            log.error("Unable to resolve the request's language; answering in English", ex);
            return Locale.ENGLISH;
        }
    }

    private static Language resolveOffered(final HttpServletRequest request, final HttpServletResponse response,
            final RequestContext context) {
        final ConfigCommands config = new ConfigCommands();
        final List<Language> offered = config.siteLanguages();
        if (offered.size() == 1) {
            return offered.get(0);
        }
        final String appFloor = config.getString(KnownSettings.API_LANGUAGES_MIN_APP_VERSION);
        return among(offered, appFloor, request, response, context);
    }

    /** The choice among two or more offered languages; the settings are read by the caller, so tests name them. */
    static Language among(final List<Language> offered, final String appFloor, final HttpServletRequest request,
            final HttpServletResponse response, final RequestContext context) {
        if (context.site().apiClient()) {
            return forApi(request, context, offered, appFloor);
        }
        final Optional<Language> picked = pickedNow(request).filter(offered::contains);
        picked.ifPresent(language -> remember(language, request, response));
        final List<String> wishes = new ArrayList<>();
        wishes.add(picked.map(Language::getTag).orElse(null));
        wishes.add(cookieChoice(request));
        wishes.add(preferenceOf(context));
        wishes.addAll(accepted(request));
        return LanguageChooser.choose(offered, wishes);
    }

    /**
     * An {@code /api/} request. A browser's script calls belong to the page that made them, so they follow the
     * page's order. The native apps render their own screens in the phone's language and say so in
     * {@code Accept-Language}, which therefore leads; the saved preference only breaks a tie the header leaves.
     * A sessionless path (Discover) is a public, cacheable answer that may vary on that header and nothing else.
     */
    private static Language forApi(final HttpServletRequest request, final RequestContext context,
            final List<Language> offered, final String appFloor) {
        final String agent = request.getHeader("User-Agent");
        final Matcher app = APP_AGENT.matcher(agent == null ? "" : agent);
        final List<String> wishes = new ArrayList<>();
        if (SessionlessPaths.matches(request)) {
            wishes.addAll(accepted(request));
        } else if (!app.find()) {
            wishes.add(cookieChoice(request));
            wishes.add(preferenceOf(context));
            wishes.addAll(accepted(request));
        } else if (atLeast(app.group(1), appFloor)) {
            wishes.addAll(accepted(request));
            wishes.add(preferenceOf(context));
        }
        return LanguageChooser.choose(offered, wishes);
    }

    /** Whether dotted version {@code have} is {@code floor} or later; a blank or unreadable floor admits nothing. */
    static boolean atLeast(final String have, final String floor) {
        if (floor == null || !floor.trim().matches("\\d+(\\.\\d+)*")) {
            return false;
        }
        final String[] mine = have.split("\\.");
        final String[] theirs = floor.trim().split("\\.");
        for (int i = 0; i < Math.max(mine.length, theirs.length); i++) {
            final int difference = Integer.compare(part(mine, i), part(theirs, i));
            if (difference != 0) {
                return difference > 0;
            }
        }
        return true;
    }

    private static int part(final String[] parts, final int index) {
        // Capped so an absurd component cannot overflow; no real version comes near it.
        if (index >= parts.length) {
            return 0;
        }
        return Integer.parseInt(parts[index].substring(0, Math.min(6, parts[index].length())));
    }

    /**
     * A {@code ?lang=} on a page GET. Read from the raw query string, never {@code getParameter}: this runs in
     * the outermost filter, and asking for a parameter there would parse a form body before anything has set
     * its encoding.
     */
    private static Optional<Language> pickedNow(final HttpServletRequest request) {
        final String query = request.getQueryString();
        if (query == null || !"GET".equals(request.getMethod())) {
            return Optional.empty();
        }
        final Matcher choice = QUERY_CHOICE.matcher(query);
        return choice.find() ? Language.forTag(choice.group(1)) : Optional.empty();
    }

    private static String cookieChoice(final HttpServletRequest request) {
        final Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (final Cookie cookie : cookies) {
            if (CHOICE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /** The browser's or app's languages, best first; the container has already ordered them by weight. */
    private static List<String> accepted(final HttpServletRequest request) {
        if (request.getHeader("Accept-Language") == null) {
            return List.of();
        }
        return Collections.list(request.getLocales()).stream().map(Locale::toLanguageTag).toList();
    }

    /** The signed-in person's saved language, or null for a visitor and for a person who never chose. */
    private static String preferenceOf(final RequestContext context) {
        final String personId = context.actor() == null ? null : context.actor().id();
        if (personId == null) {
            return null;
        }
        return DAO.getInstance().getPerson(Person.Id.from(personId), Cached.YES)
                .map(Person::getLanguage)
                .orElse(null);
    }

    /**
     * Keeps an explicit choice in a long-lived cookie for this browser, and nothing more. A GET never writes the
     * person's saved language: any page could carry such a link (an image in a typed description loads it for
     * every signed-in reader), so the preference that mail and push follow changes only through the profile's
     * Language field or {@code PUT /api/people/{id}/language}, both protected and audited.
     */
    private static void remember(final Language language, final HttpServletRequest request,
            final HttpServletResponse response) {
        final Cookie cookie = new Cookie(CHOICE, language.getTag());
        cookie.setPath("/");
        cookie.setMaxAge(REMEMBER_SECONDS);
        cookie.setHttpOnly(true);
        cookie.setSecure(SecureCookies.flagFor(request));
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }
}
