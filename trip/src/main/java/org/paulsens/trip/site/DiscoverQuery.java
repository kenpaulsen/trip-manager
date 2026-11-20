package org.paulsens.trip.site;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * One Discover request, parsed: the search words, the filters, the order and the page. Parsing is the only place
 * a query parameter's text is read, so {@link DiscoverSearch} works on values that are already valid and a
 * malformed one is refused once, here, as an {@link IllegalArgumentException} the resource answers 400 with.
 *
 * <p>Filters repeat ({@code country=BA&country=PT}): values of ONE filter are alternatives, different filters
 * must all hold. Every filter value is kept trimmed and lowercased, and matched that way, so {@code ba} and
 * {@code BA} are one country. An unknown value is not an error: it simply matches nothing, which is also what a
 * value from a newer server or an expired link should do. What IS refused is text that cannot mean anything: a
 * date that is not a date, an order or a registration state this server does not know, a damaged cursor.
 *
 * <p>The sets are sorted so two requests that differ only in parameter order parse to equal queries: the
 * response's {@code ETag} is derived from this record.
 */
public record DiscoverQuery(List<String> tokens, SortedSet<String> countries, SortedSet<String> tags,
        SortedSet<String> languages, SortedSet<String> kinds, SortedSet<String> orgs, SortedSet<String> states,
        SortedSet<String> months, LocalDate from, LocalDate to, Sort sort, When when, int limit, Cursor cursor) {

    /** A page when the caller names no size. */
    public static final int DEFAULT_LIMIT = 30;
    /** The largest page; a larger {@code limit} is served as this, not refused. */
    public static final int MAX_LIMIT = 100;
    /** Search words beyond this many are ignored: each one is a pass over every listed trip. */
    public static final int MAX_TOKENS = 5;

    /** How much of {@code q} is read at all; the rest of an over-long search is dropped. */
    private static final int MAX_QUERY_CHARS = 200;
    /** More values than this on one filter is not a filter anybody built by tapping chips. */
    private static final int MAX_VALUES = 50;
    /** The registration states, lower-cased as every filter value is ({@code notOpen} arrives as {@code notopen}). */
    private static final Set<String> STATES = Set.of("open", "full", "closed", "notopen");
    private static final String CURSOR_SEPARATOR = "\n";
    private static final String BAD_CURSOR = "cursor does not belong to this list.";

    /** The orders a list may be asked in; every one ends on departure and then the id, so it is total. */
    public enum Sort {
        /** Soonest departure first: the default. */
        DEPARTURE("departure"),
        /** Latest departure first. */
        DEPARTURE_DESC("-departure"),
        TITLE("title"),
        ORGANIZATION("organization"),
        COUNTRY("country"),
        LANGUAGE("language");

        private final String wire;

        Sort(final String wire) {
            this.wire = wire;
        }

        /** The {@code sort} parameter's value for this order. */
        public String wire() {
            return wire;
        }

        static Sort parse(final String raw) {
            return Arrays.stream(values()).filter(sort -> sort.wire.equals(raw)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown sort; expected departure, "
                            + "-departure, title, organization, country or language."));
        }
    }

    /**
     * Which list is asked for ({@code when}, 2026-11-19): the trips that have not ended (the default), or those
     * listed but for having ended ({@code past}, most recent first unless a {@code sort} is named).
     */
    public enum When {
        UPCOMING("upcoming"),
        PAST("past");

        private final String wire;

        When(final String wire) {
            this.wire = wire;
        }

        /** The {@code when} parameter's value for this list. */
        public String wire() {
            return wire;
        }

        static When parse(final String raw) {
            return Arrays.stream(values()).filter(when -> when.wire.equals(raw)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown when; expected upcoming or past."));
        }
    }

    /**
     * Where the previous page ended: the last trip's place in the order ({@code primary} is the order's own key,
     * empty for the departure orders) rather than a row number, so a trip that is listed or delisted between two
     * pages neither repeats nor hides one.
     */
    public record Cursor(String primary, LocalDate start, String id) {
    }

    /** The query for a request with no parameters: everything listed, soonest departure first. */
    public static DiscoverQuery all() {
        return parse(Map.of());
    }

    /**
     * Parses a request's query parameters (name to every value given for it).
     *
     * @throws IllegalArgumentException with a message fit for the caller when a parameter is malformed
     */
    public static DiscoverQuery parse(final Map<String, List<String>> params) {
        final LocalDate from = date(params, "from");
        final LocalDate to = date(params, "to");
        if (from != null && to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("to must not be before from.");
        }
        final When when = first(params, "when") == null ? When.UPCOMING : When.parse(first(params, "when").trim());
        final Sort fallback = when == When.PAST ? Sort.DEPARTURE_DESC : Sort.DEPARTURE;
        final Sort sort = first(params, "sort") == null ? fallback : Sort.parse(first(params, "sort").trim());
        return new DiscoverQuery(tokens(first(params, "q")), values(params, "country"), values(params, "tag"),
                values(params, "language"), values(params, "kind"), values(params, "org"), states(params),
                months(params), from, to, sort, when, limit(first(params, "limit")),
                cursor(first(params, "cursor"), scopeOf(sort, when)));
    }

    /** Whether this query asks for the past list. */
    public boolean past() {
        return when == When.PAST;
    }

    /**
     * What a client sends as {@code cursor} to continue after a trip at this place in this query's order and
     * list: a cursor is good only for the same {@code sort} and the same {@code when}.
     */
    public String encodeCursor(final Cursor cursor) {
        final String start = cursor.start() == null ? "" : cursor.start().toString();
        final String plain = String.join(CURSOR_SEPARATOR, scopeOf(sort, when), cursor.primary(), start,
                cursor.id());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The order and list a cursor belongs to, as its first line. The upcoming list's is the bare order, as every
     * cursor was before {@code when} existed; the past list's carries a prefix, so the two never accept each
     * other's cursors.
     */
    private static String scopeOf(final Sort sort, final When when) {
        return when == When.PAST ? when.wire() + " " + sort.wire() : sort.wire();
    }

    /**
     * Text as search compares it: lowercase, accents removed ({@code Perú} is {@code peru}, {@code Međugorje} is
     * {@code medugorje}), and {@code &} read as the word "and" (the JDK names "Bosnia & Herzegovina", people
     * type "and"; owner decision 2026-11-17, the same fold the apps and the trip editor's filter make). Both the
     * words typed and the text searched go through here, so the two can never disagree.
     */
    public static String normalize(final String text) {
        if (text == null) {
            return "";
        }
        final String folded = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        // The stroked d has no decomposition, so NFD leaves it: fold it by hand, or the town's own spelling
        // would not find the trips that go there.
        return folded.replace('đ', 'd').replace('Đ', 'D').replace("&", " and ").toLowerCase(Locale.ROOT).trim();
    }

    private static List<String> tokens(final String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        final String read = raw.length() > MAX_QUERY_CHARS ? raw.substring(0, MAX_QUERY_CHARS) : raw;
        return Arrays.stream(normalize(read).split("[\\s,]+")).filter(token -> !token.isEmpty()).distinct()
                .limit(MAX_TOKENS).toList();
    }

    private static SortedSet<String> values(final Map<String, List<String>> params, final String name) {
        final List<String> given = params.get(name);
        if (given == null || given.isEmpty()) {
            return Collections.emptySortedSet();
        }
        if (given.size() > MAX_VALUES) {
            throw new IllegalArgumentException("Too many values for " + name + ".");
        }
        final SortedSet<String> values = new TreeSet<>();
        for (final String value : given) {
            if (value != null && !value.isBlank()) {
                values.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableSortedSet(values);
    }

    private static SortedSet<String> states(final Map<String, List<String>> params) {
        final SortedSet<String> states = values(params, "state");
        if (!STATES.containsAll(states)) {
            throw new IllegalArgumentException("Unknown state; expected open, full, closed or notOpen.");
        }
        return states;
    }

    private static SortedSet<String> months(final Map<String, List<String>> params) {
        final SortedSet<String> months = new TreeSet<>();
        for (final String raw : values(params, "month")) {
            months.add(month(raw).toString());
        }
        return Collections.unmodifiableSortedSet(months);
    }

    private static YearMonth month(final String raw) {
        try {
            return YearMonth.parse(raw);
        } catch (final DateTimeException ex) {
            throw new IllegalArgumentException("month must be yyyy-MM.", ex);
        }
    }

    private static LocalDate date(final Map<String, List<String>> params, final String name) {
        final String raw = first(params, name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (final DateTimeException ex) {
            throw new IllegalArgumentException(name + " must be a date, yyyy-MM-dd.", ex);
        }
    }

    private static int limit(final String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LIMIT;
        }
        try {
            return Math.min(Math.max(Integer.parseInt(raw.trim()), 1), MAX_LIMIT);
        } catch (final NumberFormatException ex) {
            throw new IllegalArgumentException("limit must be a number.", ex);
        }
    }

    /**
     * The cursor a page was continued with, or null for the first page; one minted for another order or list is
     * refused.
     */
    private static Cursor cursor(final String raw, final String scope) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            final String plain = new String(Base64.getUrlDecoder().decode(raw.trim()), StandardCharsets.UTF_8);
            final String[] parts = plain.split(CURSOR_SEPARATOR, -1);
            if (parts.length == 4 && scope.equals(parts[0]) && !parts[3].isEmpty()) {
                return new Cursor(parts[1], parts[2].isEmpty() ? null : LocalDate.parse(parts[2]), parts[3]);
            }
        } catch (final IllegalArgumentException | DateTimeException ex) {
            // Not base64, or not a date inside: the same answer as a cursor from another order, below.
            throw new IllegalArgumentException(BAD_CURSOR, ex);
        }
        throw new IllegalArgumentException(BAD_CURSOR);
    }

    private static String first(final Map<String, List<String>> params, final String name) {
        final List<String> given = params.get(name);
        return (given == null || given.isEmpty()) ? null : given.get(0);
    }
}
