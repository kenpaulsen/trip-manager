package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.chat.MailTemplates;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.i18n.Formats;
import org.paulsens.trip.i18n.ReaderLanguage;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.site.SiteContext;
import org.paulsens.trip.web.LocalRedirect;
import org.paulsens.trip.web.RequestLocale;

/**
 * The pages' door to the request's language ({@code docs/i18n.md}), as {@code #{text}}: messages that take
 * arguments, dates and numbers in the reader's format, and what the language switcher needs.
 *
 * <p>A message with no arguments is written {@code #{msg['common.save']}}; one with arguments is
 * {@code #{text.format('trip.daysLeft', days)}}, so a page never joins translated fragments itself. Holds no
 * state: the language is a fact about the request, read from {@link RequestContext} each time, and must never
 * be kept in a session or a view.
 */
@Named("text")
@ApplicationScoped
public class TextCommands {

    /** A message with arguments, in the request's language. */
    public String format(final String key, final Object... args) {
        return Text.get(key, args);
    }

    /**
     * A message whose VALUE holds markup (a link or a bold word inside a sentence), for an
     * {@code escape="false"} output: {@code <h:outputText value="#{text.html('help.x.intro', name)}"
     * escape="false"/>}. The bundle's own markup is trusted, being ours; every ARGUMENT is HTML-escaped first,
     * so a name or anything else a person typed can never become markup. Use it only where the sentence really
     * needs inline markup: plain text belongs in {@code msg[...]} or {@link #format}, escaped as usual.
     */
    public String html(final String key, final Object... args) {
        return MailTemplates.html(RequestContext.currentLocale(), key, args).html();
    }

    /**
     * A message for STAFF (a page's notice to the office), known by the address it is sent to: in that person's
     * own language when the address is one account's and the sending site offers it, else English
     * ({@link ReaderLanguage#ofStaffAddress}). Never the request's language: the office is not the one on the
     * page. The sender is {@code trip}'s organization, or this site's when there is no trip.
     */
    public String forStaff(final String address, final Trip trip, final String key, final Object... args) {
        final Organization org = trip != null ? trip.getOrganization() : siteOrganization();
        return Text.forLocale(ReaderLanguage.ofStaffAddress(address, org), key, args);
    }

    /** The organization whose site this request is on; null on the shared and marketing sites. */
    private static Organization siteOrganization() {
        final Organization.Id id = SiteContext.current().orgId();
        return id == null ? null : DAO.getInstance().getOrganization(id, Cached.YES).orElse(null);
    }

    /** The scripts' own bundle ({@code docs/i18n.md}, "In a script"): every key of it reaches every page. */
    static final String SCRIPT_BUNDLE = "js";
    /** Built once per language and list: the bundles are read once and never change while the app runs. */
    private static final Map<String, String> SCRIPT_MESSAGES = new ConcurrentHashMap<>();

    /**
     * Every message a browser script may show, in the request's language, as a JSON object of key to raw pattern
     * (the script fills {@code {0}} itself): the whole {@code js} bundle, plus the shared words named in
     * {@code shared} (space-separated keys, {@code "common.cancel common.ok"}) that the scripts read as the pages
     * do. Only those, not all of {@code common}: the JSON is in every page, and a page's source should not hold
     * words it never shows. {@code WEB-INF/tripText.xhtml} writes it into an inline script, unescaped, so it is
     * made safe for that context HERE: no value can close the script element, open a comment, or end the string,
     * whatever a translator writes.
     */
    public String scriptMessages(final String shared) {
        final Locale locale = RequestContext.currentLocale();
        return SCRIPT_MESSAGES.computeIfAbsent(locale.toLanguageTag() + " " + shared,
                cacheKey -> scriptJson(locale, shared));
    }

    static String scriptJson(final Locale locale, final String shared) {
        final Map<String, String> messages = new TreeMap<>(Text.patterns(SCRIPT_BUNDLE, locale));
        for (final String key : (shared == null ? "" : shared).trim().split("\\s+")) {
            final int dot = key.indexOf('.');
            final String pattern = dot <= 0 ? null : Text.patterns(key.substring(0, dot), locale).get(key);
            if (pattern != null) {
                messages.put(key, pattern);
            }
        }
        final StringBuilder json = new StringBuilder("{");
        for (final Map.Entry<String, String> message : messages.entrySet()) {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append(scriptString(message.getKey())).append(':').append(scriptString(message.getValue()));
        }
        return json.append('}').toString();
    }

    /**
     * A JSON string that is also inert inside an HTML script element: besides JSON's own escapes, every
     * character that means something to the HTML parser ({@code < > &}), both quotes, and the two line
     * separators older JavaScript rejected in a string literal are written as backslash-u escapes.
     */
    static String scriptString(final String value) {
        final StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int at = 0; at < value.length(); at++) {
            final char c = value.charAt(at);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '"', '\'', '<', '>', '&', '\u2028', '\u2029' -> out.append(unicodeEscape(c));
                default -> out.append(c < 0x20 ? unicodeEscape(c) : String.valueOf(c));
            }
        }
        return out.append('"').toString();
    }

    private static String unicodeEscape(final char c) {
        return String.format("\\u%04x", (int) c);
    }

    /**
     * The pattern a typed birthdate is read and shown in: English month first, as it has always been; every
     * other language day first, the order its readers write a date in (and the passport dates beside it use).
     * Its placeholder is {@code common.birthdatePlaceholder}; the 99-99-9999 mask fits both orders.
     */
    public String getBirthdatePattern() {
        return Formats.isEnglish(RequestContext.currentLocale()) ? "MM-dd-yyyy" : "dd-MM-yyyy";
    }

    /** The request's language as a BCP 47 tag, for {@code <html lang>} and {@code Content-Language}. */
    public String getLanguageTag() {
        return RequestContext.currentLocale().toLanguageTag();
    }

    /** The request's language. */
    public Language getLanguage() {
        return RequestContext.currentLanguage();
    }

    /** The languages this site offers, default first: the switcher's entries and the profile menu's choices. */
    public List<Language> getOffered() {
        return new ConfigCommands().siteLanguages();
    }

    /**
     * {@link #getOffered()} plus {@code savedTag}'s language when this site does not offer it: the profile's
     * menu, so a language saved on another organization's site is shown and kept, never blanked by a save here.
     */
    public List<Language> offeredWith(final String savedTag) {
        final List<Language> offered = getOffered();
        final Optional<Language> saved = Language.forTag(savedTag).filter(language -> !offered.contains(language));
        if (saved.isEmpty()) {
            return offered;
        }
        final List<Language> withSaved = new ArrayList<>(offered);
        withSaved.add(saved.get());
        return withSaved;
    }

    /** Whether there is anything to switch between; the switcher renders only then. */
    public boolean isChoice() {
        return getOffered().size() > 1;
    }

    /**
     * This same page in another language: its own address with {@code lang} set, every other parameter kept.
     * The page passes {@code request.requestURI} and {@code request.queryString}, so this stays a pure function.
     * A plain link on purpose. A GET needs no form, no view state and no session, so an anonymous visitor can
     * switch without one being created ({@code RequestLocale} remembers the choice in a cookie).
     */
    public String switchUrl(final String requestUri, final String queryString, final String tag) {
        final String kept = keptParameters(queryString, RequestLocale.CHOICE);
        return localPath(requestUri) + "?" + (kept.isEmpty() ? "" : kept + "&") + RequestLocale.CHOICE + "=" + tag;
    }

    /**
     * THE filter of a query string for this page's own links (the switcher, the version tabs, hreflang): the
     * query without the named parameters,
     * in its own order; empty for none left. A bare {@code name} with no value is dropped too.
     */
    static String keptParameters(final String queryString, final String... dropped) {
        return Arrays.stream((queryString == null ? "" : queryString).split("&"))
                .filter(pair -> !pair.isEmpty() && Arrays.stream(dropped).noneMatch(name -> isParameter(pair, name)))
                .collect(Collectors.joining("&"));
    }

    private static boolean isParameter(final String pair, final String name) {
        return pair.equals(name) || pair.startsWith(name + "=");
    }

    /**
     * The request's path, safe to put at the front of an href. The container hands the URI back exactly as the
     * client sent it, so {@code //evil.example/x} would make a protocol-relative link to another host; anything
     * that is not a plain local path becomes the site's root.
     */
    static String localPath(final String requestUri) {
        final String local = LocalRedirect.sanitizeLocalTarget(requestUri);
        return local == null ? "/" : local;
    }

    public String date(final TemporalAccessor when) {
        return Formats.date(when, RequestContext.currentLocale());
    }

    public String longDate(final TemporalAccessor when) {
        return Formats.longDate(when, RequestContext.currentLocale());
    }

    public String time(final TemporalAccessor when) {
        return Formats.time(when, RequestContext.currentLocale());
    }

    public String dateTime(final TemporalAccessor when) {
        return Formats.dateTime(when, RequestContext.currentLocale());
    }

    public String number(final long value) {
        return Formats.number(value, RequestContext.currentLocale());
    }

    /**
     * The request's pattern for a date skeleton, for a pattern attribute:
     * {@code <f:convertDateTime pattern="#{text.datePattern('yMMMd')}" .../>} or a {@code p:datePicker}'s
     * {@code pattern="#{text.datePattern('yMMdd')}"}, which also decides how a TYPED date is read. Use it only
     * where the skeleton's English pattern is the page's old one ({@code FormatsTest} pins each one used).
     */
    public String datePattern(final String skeleton) {
        return Formats.pattern(skeleton, RequestContext.currentLocale());
    }

    /** {@link #datePattern}, except that English keeps {@code english}, the page's pattern as it has been. */
    public String datePatternOr(final String skeleton, final String english) {
        return Formats.pattern(skeleton, english, RequestContext.currentLocale());
    }

    /** A time picker's {@code hourFormat}: "12" where the language reads a 12-hour clock, else "24". */
    public String getHourFormat() {
        return Formats.pattern("j", RequestContext.currentLocale()).matches("[^']*[hK].*") ? "12" : "24";
    }

    /** A time picker's {@code pattern}, matching {@link #getHourFormat}: "hh:mm a" or "HH:mm". */
    public String getTimePickerPattern() {
        return "12".equals(getHourFormat()) ? "hh:mm a" : "HH:mm";
    }
}
