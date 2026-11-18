package org.paulsens.trip.site;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedSet;
import java.util.function.Function;
import org.paulsens.trip.api.dto.DiscoverFacetDto;
import org.paulsens.trip.api.dto.DiscoverListDto;
import org.paulsens.trip.api.dto.DiscoverTripDto;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.TripKind;
import org.paulsens.trip.site.DiscoverIndex.Entry;
import org.paulsens.trip.site.DiscoverQuery.Cursor;
import org.paulsens.trip.site.DiscoverQuery.Sort;

/**
 * Search, filters, order, paging and facet counts over the listed trips: a pure function of the entries it is
 * handed and a parsed {@link DiscoverQuery}. It reads no cache, no setting and no clock, and it can only ever
 * return entries it was given, so whatever {@link DiscoverIndex} left out (an organization with Shared sites off,
 * a trip off its homepage) cannot come back through a clever query.
 *
 * <p>Words ({@code q}) must ALL appear in the entry's searchable text. Each filter admits an entry carrying any
 * one of its chosen values (a trip that visits several countries passes the country filter for any of them, and
 * is counted under each); an entry must pass every filter. A filter's facet is counted over the entries that
 * pass the words, the dates and every OTHER filter, so the counts beside a filter's own chips do not collapse to
 * the one already chosen, and a value that would return nothing is never offered.
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
        private final Function<Entry, List<Choice>> carried;

        Dimension(final Function<DiscoverQuery, SortedSet<String>> chosen,
                final Function<Entry, List<Choice>> carried) {
            this.chosen = chosen;
            this.carried = carried;
        }

        boolean admits(final Entry entry, final DiscoverQuery query) {
            final SortedSet<String> wanted = chosen.apply(query);
            return wanted.isEmpty() || carried.apply(entry).stream()
                    .anyMatch(choice -> wanted.contains(choice.value().toLowerCase(Locale.ROOT)));
        }
    }

    /** Runs {@code query} over {@code entries} (already narrowed to what this host may list). */
    public static Result search(final List<Entry> entries, final DiscoverQuery query) {
        final List<Entry> worded = entries.stream().filter(entry -> matchesWordsAndDates(entry, query)).toList();
        final Comparator<Entry> order = orderOf(query.sort());
        final List<Entry> matched = worded.stream().filter(entry -> passes(entry, query, null)).sorted(order)
                .toList();
        final List<Entry> rest = query.cursor() == null ? matched
                : matched.stream().filter(entry -> isAfter(entry, query.cursor(), query.sort())).toList();
        final List<Entry> page = rest.stream().limit(query.limit()).toList();
        final String next = rest.size() > page.size() ? cursorAfter(page.getLast(), query.sort()) : null;
        return new Result(page.stream().map(Entry::trip).toList(), matched.size(), next, facetsOf(worded, query));
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

    /** One choice per country the trip visits: it passes the filter for ANY of them and counts under EACH. */
    private static List<Choice> countryOf(final Entry entry) {
        final List<String> codes = entry.trip().countries();
        final List<Choice> countries = new ArrayList<>();
        for (int i = 0; i < codes.size(); i++) {
            countries.add(new Choice(codes.get(i), entry.countryLabels().get(i)));
        }
        return countries;
    }

    private static List<Choice> tagsOf(final Entry entry) {
        final List<String> ids = entry.trip().tags();
        final List<String> labels = entry.trip().tagLabels();
        final List<Choice> tags = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            tags.add(new Choice(ids.get(i), i < labels.size() ? labels.get(i) : ids.get(i)));
        }
        return tags;
    }

    private static List<Choice> languageOf(final Entry entry) {
        final String name = entry.trip().language();
        return List.of(new Choice(name, languageLabel(name)));
    }

    /** The language in its own words ("Español"), or its name when this build does not know it. */
    private static String languageLabel(final String name) {
        return Language.named(name).map(Language::getDisplayName).orElse(name);
    }

    private static List<Choice> kindOf(final Entry entry) {
        final String name = entry.trip().kind();
        return List.of(new Choice(name, kindLabel(name)));
    }

    private static String kindLabel(final String name) {
        return TripKind.named(name).map(TripKind::getDisplayValue).orElse(name);
    }

    private static List<Choice> organizationOf(final Entry entry) {
        return List.of(new Choice(entry.trip().orgId(), entry.trip().orgName(), entry.trip().orgLogoUrl()));
    }

    /** The month the trip DEPARTS in: one month per trip, so the months' counts add up to the list. */
    private static List<Choice> monthOf(final Entry entry) {
        final YearMonth month = entry.month();
        return month == null ? List.of() : List.of(new Choice(month.toString(),
                month.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + month.getYear()));
    }

    private static List<Choice> stateOf(final Entry entry) {
        return List.of(new Choice(entry.trip().registration(), entry.trip().registration()));
    }

    // ------------------------------------------------------------------ facets

    private static DiscoverListDto.Facets facetsOf(final List<Entry> worded, final DiscoverQuery query) {
        return new DiscoverListDto.Facets(
                byCount(facet(worded, query, Dimension.COUNTRY)),
                byCount(facet(worded, query, Dimension.TAG)),
                byCount(facet(worded, query, Dimension.LANGUAGE)),
                byCount(facet(worded, query, Dimension.KIND)),
                byCount(facet(worded, query, Dimension.ORGANIZATION)),
                facet(worded, query, Dimension.MONTH).stream()
                        .sorted(Comparator.comparing(DiscoverFacetDto::value)).toList());
    }

    /** {@code dimension}'s values among the entries passing everything BUT {@code dimension}, with counts. */
    private static List<DiscoverFacetDto> facet(final List<Entry> worded, final DiscoverQuery query,
            final Dimension dimension) {
        final Map<String, DiscoverFacetDto> counted = new LinkedHashMap<>();
        for (final Entry entry : worded) {
            if (passes(entry, query, dimension)) {
                dimension.carried.apply(entry).forEach(choice -> counted.merge(choice.value(),
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

    private static String cursorAfter(final Entry last, final Sort sort) {
        return DiscoverQuery.encodeCursor(sort, placeOf(last, sort));
    }
}
