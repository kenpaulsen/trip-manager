package org.paulsens.trip.site;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedSet;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.paulsens.trip.api.dto.DiscoverFacetDto;
import org.paulsens.trip.api.dto.DiscoverListDto;
import org.paulsens.trip.api.dto.DiscoverTripDto;
import org.paulsens.trip.i18n.Formats;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.TripKind;
import org.paulsens.trip.site.DiscoverIndex.Entry;
import org.paulsens.trip.site.DiscoverQuery.Cursor;
import org.paulsens.trip.site.DiscoverQuery.Sort;

/**
 * Search, filters, order, paging and facet counts over the listed trips: a pure function of the entries it is
 * handed, a parsed {@link DiscoverQuery} and the reader's language. It reads no cache, no setting and no clock, and
 * it can only ever
 * return entries it was given, so whatever {@link DiscoverIndex} left out (an organization with Shared sites off,
 * a trip off its homepage) cannot come back through a clever query.
 *
 * <p>Words ({@code q}) must ALL appear in the entry's searchable text. Each filter admits an entry carrying any
 * one of its chosen values (a trip that visits several countries passes the country filter for any of them, and
 * is counted under each); an entry must pass every filter. A filter's facet is counted over the entries that
 * pass the words, the dates and every OTHER filter, so the counts beside a filter's own chips do not collapse to
 * the one already chosen, and a value that would return nothing is never offered.
 *
 * <p>A facet's LABEL (a country's name, a month, a trip kind) is worded here, per request, in the reader's
 * language: the index is built once for every reader, so nothing language-specific may be stored in it. Search
 * text stays the index's (English names and aliases beside the trip's own language), whatever the reader's.
 */
public final class DiscoverSearch {

    /** Sorts an entry with no value for an order's key (no country) after every entry that has one. */
    private static final String LAST = "\uffff";

    private DiscoverSearch() {
    }

    /** One answer: the page's trips, how many matched in all, where the next page starts, and the facets. */
    public record Result(List<DiscoverTripDto> items, int total, String nextCursor, DiscoverListDto.Facets facets) {
    }

    /** One value an entry carries for a filter: what the parameter takes, what a person reads, and a logo. */
    private record Choice(String value, String label, String logoUrl) {

        Choice(final String value, final String label) {
            this(value, label, null);
        }
    }

    /** The filters that have a set of chosen values; {@code STATE} is a filter with no facet. */
    private enum Dimension {
        COUNTRY(DiscoverQuery::countries, DiscoverSearch::countryOf),
        TAG(DiscoverQuery::tags, DiscoverSearch::tagsOf),
        LANGUAGE(DiscoverQuery::languages, DiscoverSearch::languageOf),
        KIND(DiscoverQuery::kinds, DiscoverSearch::kindOf),
        ORGANIZATION(DiscoverQuery::orgs, DiscoverSearch::organizationOf),
        MONTH(DiscoverQuery::months, DiscoverSearch::monthOf),
        STATE(DiscoverQuery::states, DiscoverSearch::stateOf);

        private final Function<DiscoverQuery, SortedSet<String>> chosen;
        /** The choices an entry carries, labeled in the given language. */
        private final BiFunction<Entry, Locale, List<Choice>> carried;

        Dimension(final Function<DiscoverQuery, SortedSet<String>> chosen,
                final BiFunction<Entry, Locale, List<Choice>> carried) {
            this.chosen = chosen;
            this.carried = carried;
        }

        boolean admits(final Entry entry, final DiscoverQuery query) {
            final SortedSet<String> wanted = chosen.apply(query);
            // Only the values are compared, so the label's language does not matter here.
            return wanted.isEmpty() || carried.apply(entry, Locale.ENGLISH).stream()
                    .anyMatch(choice -> wanted.contains(choice.value().toLowerCase(Locale.ROOT)));
        }
    }

    /** {@link #search(List, DiscoverQuery, Locale)} with the facets worded in English. */
    public static Result search(final List<Entry> entries, final DiscoverQuery query) {
        return search(entries, query, Locale.ENGLISH);
    }

    /**
     * Runs {@code query} over {@code entries} (already narrowed to what this host may list), the facets' labels
     * worded in {@code language}.
     */
    public static Result search(final List<Entry> entries, final DiscoverQuery query, final Locale language) {
        final List<Entry> worded = entries.stream().filter(entry -> matchesWordsAndDates(entry, query)).toList();
        final Comparator<Entry> order = orderOf(query.sort());
        final List<Entry> matched = worded.stream().filter(entry -> passes(entry, query, null)).sorted(order)
                .toList();
        final List<Entry> rest = query.cursor() == null ? matched
                : matched.stream().filter(entry -> isAfter(entry, query.cursor(), query.sort())).toList();
        final List<Entry> page = rest.stream().limit(query.limit()).toList();
        final String next = rest.size() > page.size() ? cursorAfter(page.getLast(), query) : null;
        return new Result(page.stream().map(Entry::trip).toList(), matched.size(), next,
                facetsOf(worded, query, language));
    }

    private static boolean matchesWordsAndDates(final Entry entry, final DiscoverQuery query) {
        return query.tokens().stream().allMatch(entry.haystack()::contains) && overlaps(entry.trip(), query);
    }

    /** Whether the trip's days touch the asked range at all; a missing end counts as a one-day trip. */
    private static boolean overlaps(final DiscoverTripDto trip, final DiscoverQuery query) {
        final LocalDate start = trip.startDate() != null ? trip.startDate() : trip.endDate();
        final LocalDate end = trip.endDate() != null ? trip.endDate() : trip.startDate();
        if (start == null) {
            // No dates at all (the index lists no such trip): it is in no range anybody asks for.
            return query.from() == null && query.to() == null;
        }
        return (query.to() == null || !start.isAfter(query.to()))
                && (query.from() == null || !end.isBefore(query.from()));
    }

    /** Whether the entry passes every filter, leaving out {@code except} (null for none): a facet's own. */
    private static boolean passes(final Entry entry, final DiscoverQuery query, final Dimension except) {
        for (final Dimension dimension : Dimension.values()) {
            if (dimension != except && !dimension.admits(entry, query)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ what an entry carries

    /**
     * One choice per country the trip visits: it passes the filter for ANY of them and counts under EACH. Named as
     * the trip editor's menu names it (the index's English) for an English reader, else in the reader's language.
     */
    private static List<Choice> countryOf(final Entry entry, final Locale language) {
        final List<String> codes = entry.trip().countries();
        final Locale other = otherThanEnglish(language);
        final List<Choice> countries = new ArrayList<>();
        for (int i = 0; i < codes.size(); i++) {
            countries.add(new Choice(codes.get(i), other == null ? entry.countryLabels().get(i)
                    : countryName(codes.get(i), other, entry.countryLabels().get(i))));
        }
        return countries;
    }

    /** The reader's language when it is one the product speaks other than English; null for English. */
    private static Locale otherThanEnglish(final Locale language) {
        return Language.forTag(language == null ? null : language.getLanguage())
                .filter(known -> known != Language.English)
                .map(Language::getLocale)
                .orElse(null);
    }

    private static String countryName(final String code, final Locale in, final String english) {
        final String name = Locale.of("", code).getDisplayCountry(in);
        return name.isBlank() || name.equals(code) ? english : name;
    }

    private static List<Choice> tagsOf(final Entry entry, final Locale language) {
        final List<String> ids = entry.trip().tags();
        final List<String> labels = entry.trip().tagLabels();
        final List<Choice> tags = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            tags.add(new Choice(ids.get(i), i < labels.size() ? labels.get(i) : ids.get(i)));
        }
        return tags;
    }

    /** The language in its OWN words, the same for every reader (docs/i18n.md: language names are never keyed). */
    private static List<Choice> languageOf(final Entry entry, final Locale language) {
        final String name = entry.trip().language();
        return List.of(new Choice(name, languageLabel(name)));
    }

    /** The language in its own words ("Español"), or its name when this build does not know it. */
    private static String languageLabel(final String name) {
        return Language.named(name).map(Language::getDisplayName).orElse(name);
    }

    private static List<Choice> kindOf(final Entry entry, final Locale language) {
        final String name = entry.trip().kind();
        return List.of(new Choice(name, kindLabel(name, language)));
    }

    private static String kindLabel(final String name, final Locale language) {
        return TripKind.named(name).map(kind -> kind.getDisplayValue(language)).orElse(name);
    }

    private static List<Choice> organizationOf(final Entry entry, final Locale language) {
        return List.of(new Choice(entry.trip().orgId(), entry.trip().orgName(), entry.trip().orgLogoUrl()));
    }

    /**
     * The month the trip DEPARTS in: one month per trip, so the months' counts add up to the list. Labeled with the
     * reader's own month-and-year pattern ("January 2027", "enero de 2027"), never a month name glued to a year.
     */
    private static List<Choice> monthOf(final Entry entry, final Locale language) {
        final YearMonth month = entry.month();
        return month == null ? List.of() : List.of(new Choice(month.toString(),
                Formats.format(month, "yMMMM", language)));
    }

    private static List<Choice> stateOf(final Entry entry, final Locale language) {
        return List.of(new Choice(entry.trip().registration(), entry.trip().registration()));
    }

    // ------------------------------------------------------------------ facets

    private static DiscoverListDto.Facets facetsOf(final List<Entry> worded, final DiscoverQuery query,
            final Locale language) {
        return new DiscoverListDto.Facets(
                byCount(facet(worded, query, Dimension.COUNTRY, language)),
                byCount(facet(worded, query, Dimension.TAG, language)),
                byCount(facet(worded, query, Dimension.LANGUAGE, language)),
                byCount(facet(worded, query, Dimension.KIND, language)),
                byCount(facet(worded, query, Dimension.ORGANIZATION, language)),
                facet(worded, query, Dimension.MONTH, language).stream()
                        .sorted(Comparator.comparing(DiscoverFacetDto::value)).toList());
    }

    /** {@code dimension}'s values among the entries passing everything BUT {@code dimension}, with counts. */
    private static List<DiscoverFacetDto> facet(final List<Entry> worded, final DiscoverQuery query,
            final Dimension dimension, final Locale language) {
        final Map<String, DiscoverFacetDto> counted = new LinkedHashMap<>();
        for (final Entry entry : worded) {
            if (passes(entry, query, dimension)) {
                dimension.carried.apply(entry, language).forEach(choice -> counted.merge(choice.value(),
                        new DiscoverFacetDto(choice.value(), choice.label(), 1, choice.logoUrl()),
                        DiscoverSearch::plusOne));
            }
        }
        return List.copyOf(counted.values());
    }

    private static DiscoverFacetDto plusOne(final DiscoverFacetDto seen, final DiscoverFacetDto ignored) {
        return new DiscoverFacetDto(seen.value(), seen.label(), seen.count() + 1, seen.logoUrl());
    }

    private static List<DiscoverFacetDto> byCount(final List<DiscoverFacetDto> facets) {
        return facets.stream().sorted(Comparator.comparingInt(DiscoverFacetDto::count).reversed()
                .thenComparing(DiscoverFacetDto::label, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(DiscoverFacetDto::value)).toList();
    }

    // ------------------------------------------------------------------ order and cursor

    /** An order's own key for an entry; empty for the departure orders, whose key is the date itself. */
    private static String primaryOf(final Entry entry, final Sort sort) {
        return switch (sort) {
            case DEPARTURE, DEPARTURE_DESC -> "";
            case TITLE -> entry.titleKey();
            case ORGANIZATION -> entry.orgKey();
            case COUNTRY -> entry.countryKey() == null ? LAST : entry.countryKey();
            case LANGUAGE -> entry.trip().language().toLowerCase(Locale.ROOT);
        };
    }

    private static Cursor placeOf(final Entry entry, final Sort sort) {
        return new Cursor(primaryOf(entry, sort), entry.trip().startDate(), entry.trip().id());
    }

    /**
     * The total order of places for {@code sort}: its own key, then departure (latest first only for
     * {@code -departure}; a trip with no start date last), then the id, so no two trips ever tie and a cursor
     * names exactly one position.
     */
    private static Comparator<Cursor> placeOrder(final Sort sort) {
        final Comparator<LocalDate> dates = sort == Sort.DEPARTURE_DESC
                ? Comparator.<LocalDate>reverseOrder() : Comparator.<LocalDate>naturalOrder();
        return Comparator.comparing(Cursor::primary)
                .thenComparing(Cursor::start, Comparator.nullsLast(dates))
                .thenComparing(Cursor::id);
    }

    private static Comparator<Entry> orderOf(final Sort sort) {
        return Comparator.comparing(entry -> placeOf(entry, sort), placeOrder(sort));
    }

    private static boolean isAfter(final Entry entry, final Cursor cursor, final Sort sort) {
        return placeOrder(sort).compare(placeOf(entry, sort), cursor) > 0;
    }

    private static String cursorAfter(final Entry last, final DiscoverQuery query) {
        return query.encodeCursor(placeOf(last, query.sort()));
    }
}
