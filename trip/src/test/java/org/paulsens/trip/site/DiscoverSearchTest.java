package org.paulsens.trip.site;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.api.dto.DiscoverFacetDto;
import org.paulsens.trip.api.dto.DiscoverTripDto;
import org.paulsens.trip.site.DiscoverIndex.Entry;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * {@link DiscoverSearch} and {@link DiscoverQuery}: words, filters (alternatives within one, all of them across),
 * date edges, every order, the cursor and the facet counts. Pure: the entries are built here, so each case says
 * exactly which trips exist.
 */
public class DiscoverSearchTest {

    private static final String PARISH = "org-parish";
    private static final String TREKS = "org-treks";

    private static final DiscoverTripDto MEDJUGORJE = trip("t-medj", "Medjugorje Pilgrimage", "2026-12-17",
            "2026-12-27", "Međugorje, Bosnia and Herzegovina", "BA", List.of("pilgrimage"), "PILGRIMAGE", "English",
            PARISH, "Holy Redeemer Parish", "open");
    /** The trip that crosses a border: Portugal, then France. */
    private static final DiscoverTripDto FATIMA = trip("t-fatima", "Fatima and Lourdes Retreat", "2027-01-10",
            "2027-01-18", "Fatima, Portugal and Lourdes, France", "PT,FR", List.of("retreat", "pilgrimage"),
            "PILGRIMAGE", "English", PARISH, "Holy Redeemer Parish", "full");
    private static final DiscoverTripDto PERU = trip("t-peru", "Aventura en Perú", "2027-01-05", "2027-01-16",
            "Cusco, Perú", "PE", List.of("family", "tour"), "PILGRIMAGE", "Spanish", TREKS, "Wayfarer Treks", "open");
    private static final DiscoverTripDto CONFERENCE = trip("t-conf", "Peace Conference", "2027-03-05", "2027-03-07",
            "Portland, Oregon", "US", List.of(), "CONFERENCE", "English", PARISH, "Holy Redeemer Parish", "open");
    private static final DiscoverTripDto NOWHERE = trip("t-nowhere", "Board Retreat", "2026-11-20", "2026-11-21",
            null, null, List.of("retreat"), "PILGRIMAGE", "English", TREKS, "Wayfarer Treks", "closed");

    private static final List<Entry> ALL = entries(MEDJUGORJE, FATIMA, PERU, CONFERENCE, NOWHERE);

    private static DiscoverTripDto trip(final String id, final String title, final String start, final String end,
            final String destination, final String countries, final List<String> tags, final String kind,
            final String language, final String orgId, final String orgName, final String registration) {
        return new DiscoverTripDto(id, title, LocalDate.parse(start), LocalDate.parse(end), destination,
                countries == null ? List.of() : List.of(countries.split(",")),
                tags, tags.stream().map(DiscoverSearchTest::labelOf).toList(), kind, language, null, List.of(), orgId,
                orgName, null, TREKS.equals(orgId) ? "https://cdn.example/treks-logo.png" : null,
                "https://example.org/trip/tripPage.jsf?trip=" + id, registration, null, false);
    }

    /** A tag's label as the setting would spell it: capitalized. */
    private static String labelOf(final String tagId) {
        return Character.toUpperCase(tagId.charAt(0)) + tagId.substring(1);
    }

    private static List<Entry> entries(final DiscoverTripDto... trips) {
        final List<Entry> entries = new ArrayList<>();
        for (final DiscoverTripDto trip : trips) {
            entries.add(Entry.of(trip));
        }
        return entries;
    }

    /** A query from name/value pairs; a name may repeat. */
    private static DiscoverQuery query(final String... pairs) {
        final Map<String, List<String>> params = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            params.computeIfAbsent(pairs[i], name -> new ArrayList<>()).add(pairs[i + 1]);
        }
        return DiscoverQuery.parse(params);
    }

    private static List<String> ids(final String... pairs) {
        return ids(DiscoverSearch.search(ALL, query(pairs)));
    }

    private static List<String> ids(final DiscoverSearch.Result result) {
        return result.items().stream().map(DiscoverTripDto::id).toList();
    }

    private static Map<String, Integer> counts(final List<DiscoverFacetDto> facets) {
        final Map<String, Integer> counts = new HashMap<>();
        facets.forEach(facet -> counts.put(facet.value(), facet.count()));
        return counts;
    }

    // ------------------------------------------------------------------ the default list

    @Test
    public void noParametersListsEverythingSoonestDepartureFirst() {
        final DiscoverSearch.Result all = DiscoverSearch.search(ALL, DiscoverQuery.all());

        Assert.assertEquals(ids(all), List.of("t-nowhere", "t-medj", "t-peru", "t-fatima", "t-conf"));
        Assert.assertEquals(all.total(), 5);
        Assert.assertNull(all.nextCursor(), "one page holds them all");
        Assert.assertEquals(DiscoverQuery.all().limit(), DiscoverQuery.DEFAULT_LIMIT);
        Assert.assertEquals(DiscoverQuery.all().sort(), DiscoverQuery.Sort.DEPARTURE);
    }

    /** Search returns entries it was handed and nothing else: what the index left out cannot come back. */
    @Test
    public void searchCannotSurfaceATripThatIsNotInTheEntries() {
        final List<Entry> withoutFatima = entries(MEDJUGORJE, PERU);

        for (final DiscoverQuery query : List.of(DiscoverQuery.all(), query("q", "fatima"), query("country", "PT"),
                query("tag", "retreat"), query("org", PARISH), query("month", "2027-01"), query("state", "full"),
                query("from", "2027-01-10", "to", "2027-01-18"), query("sort", "title"))) {
            final DiscoverSearch.Result found = DiscoverSearch.search(withoutFatima, query);
            Assert.assertFalse(ids(found).contains("t-fatima"), "listed by " + query);
            Assert.assertFalse(found.facets().countries().stream()
                    .anyMatch(facet -> "PT".equals(facet.value()) || "FR".equals(facet.value())),
                    "and neither of its countries is offered as a filter by " + query);
            Assert.assertFalse(found.facets().tags().stream().anyMatch(facet -> "retreat".equals(facet.value())),
                    "nor its tag by " + query);
        }
        Assert.assertEquals(DiscoverSearch.search(List.of(), DiscoverQuery.all()).total(), 0, "nothing listed");
    }

    // ------------------------------------------------------------------ words

    @Test
    public void everyWordMustMatchAcrossTitlePlaceCountryOrganizationAndTags() {
        Assert.assertEquals(ids("q", "fatima"), List.of("t-fatima"), "the title");
        Assert.assertEquals(ids("q", "cusco"), List.of("t-peru"), "the destination");
        Assert.assertEquals(ids("q", "portugal"), List.of("t-fatima"), "the country by name");
        Assert.assertEquals(ids("q", "france"), List.of("t-fatima"), "every country of a trip that visits several");
        // The owner's English search names (2026-11-17): matched, never shown.
        Assert.assertEquals(ids("q", "usa"), List.of("t-conf"), "an English alias of the country");
        Assert.assertEquals(ids("q", "america"), List.of("t-conf"));
        Assert.assertEquals(ids("q", "france portugal"), List.of("t-fatima"));
        Assert.assertEquals(ids("q", "bosnia"), List.of("t-medj"));
        Assert.assertEquals(ids("q", "wayfarer"), List.of("t-nowhere", "t-peru"), "the organization");
        Assert.assertEquals(ids("q", "family"), List.of("t-peru"), "a tag's label");
        Assert.assertEquals(ids("q", "retreat parish"), List.of("t-fatima"), "both words, wherever each is found");
        Assert.assertEquals(ids("q", "retreat cusco"), List.of(), "one word missing: no match");
        Assert.assertEquals(ids("q", "  "), ids(), "blank search is no search");
    }

    @Test
    public void wordsIgnoreCaseAndAccents() {
        Assert.assertEquals(ids("q", "PERU"), List.of("t-peru"), "Perú without its accent");
        Assert.assertEquals(ids("q", "perú"), List.of("t-peru"), "and with it");
        Assert.assertEquals(ids("q", "medugorje"), List.of("t-medj"), "the stroked d of Međugorje typed plain");
        Assert.assertEquals(ids("q", "Međugorje"), List.of("t-medj"));
        Assert.assertEquals(DiscoverQuery.normalize("  Ça  Va, Señor "), "ca  va, senor");
        Assert.assertEquals(DiscoverQuery.normalize(null), "");
        // "&" reads as "and" on both sides, so "bosnia and" finds the JDK's "Bosnia & Herzegovina".
        Assert.assertEquals(DiscoverQuery.normalize("Bosnia & Herzegovina"), "bosnia  and  herzegovina");
        Assert.assertEquals(DiscoverQuery.normalize("&"), "and");
    }

    @Test
    public void onlyTheFirstFiveWordsAreUsedAndRepeatsCountOnce() {
        final DiscoverQuery many = query("q", "one two two three, four five six seven");

        Assert.assertEquals(many.tokens(), List.of("one", "two", "three", "four", "five"));
        Assert.assertEquals(query("q", "x".repeat(500)).tokens().get(0).length(), 200, "an over-long search is cut");
    }

    // ------------------------------------------------------------------ filters

    @Test
    public void valuesOfOneFilterAreAlternativesAndDifferentFiltersMustAllHold() {
        Assert.assertEquals(ids("country", "BA", "country", "PT"), List.of("t-medj", "t-fatima"), "either country");
        Assert.assertEquals(ids("country", "ba"), List.of("t-medj"), "a filter value's case does not matter");
        Assert.assertEquals(ids("country", "FR"), List.of("t-fatima"), "ANY of a trip's countries finds it");
        Assert.assertEquals(ids("country", "PT"), List.of("t-fatima"));
        Assert.assertEquals(ids("country", "FR", "country", "PT"), List.of("t-fatima"), "and it is listed once");
        Assert.assertEquals(DiscoverSearch.search(ALL, query("country", "FR", "country", "PT")).total(), 1);
        Assert.assertEquals(ids("tag", "retreat"), List.of("t-nowhere", "t-fatima"));
        Assert.assertEquals(ids("tag", "retreat", "country", "PT"), List.of("t-fatima"), "both filters hold");
        Assert.assertEquals(ids("tag", "retreat", "country", "PE"), List.of(), "no trip passes both");
        Assert.assertEquals(ids("language", "spanish"), List.of("t-peru"));
        Assert.assertEquals(ids("kind", "conference"), List.of("t-conf"));
        Assert.assertEquals(ids("org", TREKS), List.of("t-nowhere", "t-peru"));
        Assert.assertEquals(ids("state", "full", "state", "closed"), List.of("t-nowhere", "t-fatima"));
        Assert.assertEquals(ids("month", "2027-01"), List.of("t-peru", "t-fatima"), "departing that month");
        Assert.assertEquals(ids("country", "ZZ"), List.of(), "an unknown value matches nothing; it is no error");
        Assert.assertEquals(ids("q", "retreat", "org", PARISH), List.of("t-fatima"), "words and a filter together");
        Assert.assertEquals(ids("country", " "), ids(), "a blank value is no filter");
    }

    /** A date range keeps a trip whose days touch it at all, edges included. */
    @Test
    public void theDateRangeIsAnOverlapWithInclusiveEdges() {
        Assert.assertEquals(ids("from", "2026-12-27"), List.of("t-medj", "t-peru", "t-fatima", "t-conf"),
                "from the Medjugorje trip's LAST day: it still counts");
        Assert.assertEquals(ids("from", "2026-12-28"), List.of("t-peru", "t-fatima", "t-conf"), "the day after: gone");
        Assert.assertEquals(ids("to", "2026-12-17"), List.of("t-nowhere", "t-medj"),
                "up to the Medjugorje trip's FIRST day: it counts");
        Assert.assertEquals(ids("to", "2026-12-16"), List.of("t-nowhere"));
        Assert.assertEquals(ids("from", "2027-01-17", "to", "2027-01-17"), List.of("t-fatima"),
                "one day in the middle of a trip");
        Assert.assertEquals(ids("from", "2027-02-01", "to", "2027-02-28"), List.of(), "a month nobody travels in");
    }

    /** {@code notOpen} is a state of its own: its filter finds only such a trip, in any spelling of its case. */
    @Test
    public void theNotOpenStateIsItsOwnFilterValue() {
        final DiscoverTripDto soon = trip("t-soon", "Opens Soon", "2027-06-01", "2027-06-08", null, "IT",
                List.of(), "PILGRIMAGE", "English", PARISH, "Holy Redeemer Parish", "notOpen");
        final List<Entry> listed = entries(MEDJUGORJE, NOWHERE, soon);
        Assert.assertEquals(ids(DiscoverSearch.search(listed, query("state", "notOpen"))), List.of("t-soon"));
        Assert.assertEquals(ids(DiscoverSearch.search(listed, query("state", "NOTOPEN"))), List.of("t-soon"));
        Assert.assertEquals(ids(DiscoverSearch.search(listed, query("state", "closed"))), List.of("t-nowhere"),
                "not open yet is not closed");
        Assert.assertEquals(ids(DiscoverSearch.search(listed, query("state", "open", "state", "notOpen"))),
                List.of("t-medj", "t-soon"));
    }

    /** A trip with only one date is a one-day trip; one with none (never listed) is in no range. */
    @Test
    public void aMissingDateFallsBackToTheOther() {
        final DiscoverTripDto noEnd = new DiscoverTripDto("t-open-end", "Open End", LocalDate.parse("2027-05-01"),
                null, null, List.of(), List.of(), List.of(), "PILGRIMAGE", "English", null, List.of(), PARISH,
                "Parish", null, null, "u", "open", null, false);
        final DiscoverTripDto noDates = new DiscoverTripDto("t-undated", "Undated", null, null, null, List.of(),
                List.of(), List.of(), "PILGRIMAGE", "English", null, List.of(), PARISH, "Parish", null, null, "u",
                "open", null, false);
        final List<Entry> odd = entries(noEnd, noDates);

        Assert.assertEquals(ids(DiscoverSearch.search(odd, query("from", "2027-05-01", "to", "2027-05-01"))),
                List.of("t-open-end"));
        Assert.assertEquals(ids(DiscoverSearch.search(odd, query("from", "2027-05-02"))), List.of());
        Assert.assertEquals(ids(DiscoverSearch.search(odd, DiscoverQuery.all())), List.of("t-open-end", "t-undated"),
                "no range asked: both list, the undated one last");
        Assert.assertEquals(DiscoverSearch.search(odd, DiscoverQuery.all()).facets().months().size(), 1,
                "an undated trip departs in no month");
    }

    // ------------------------------------------------------------------ orders and the cursor

    @Test
    public void everyOrderIsTotalAndEndsOnDeparture() {
        Assert.assertEquals(ids("sort", "departure"), List.of("t-nowhere", "t-medj", "t-peru", "t-fatima", "t-conf"));
        Assert.assertEquals(ids("sort", "-departure"), List.of("t-conf", "t-fatima", "t-peru", "t-medj", "t-nowhere"));
        Assert.assertEquals(ids("sort", "title"), List.of("t-peru", "t-nowhere", "t-fatima", "t-medj", "t-conf"),
                "Aventura, Board, Fatima, Medjugorje, Peace");
        Assert.assertEquals(ids("sort", "organization"),
                List.of("t-medj", "t-fatima", "t-conf", "t-nowhere", "t-peru"),
                "Holy Redeemer before Wayfarer, each by departure");
        Assert.assertEquals(ids("sort", "country"), List.of("t-medj", "t-fatima", "t-peru", "t-conf", "t-nowhere"),
                "Bosnia, France (the Portugal and France trip sorts by the alphabetically first of its countries, "
                        + "whichever it lists first), Peru, United States, and the trip with no country last");
        Assert.assertEquals(ids("sort", "language"), List.of("t-nowhere", "t-medj", "t-fatima", "t-conf", "t-peru"),
                "English by departure, then Spanish");
    }

    @Test
    public void theCursorWalksEveryOrderWithoutRepeatingOrSkipping() {
        for (final DiscoverQuery.Sort sort : DiscoverQuery.Sort.values()) {
            final List<String> whole = ids("sort", sort.wire());
            final List<String> paged = new ArrayList<>();
            String cursor = null;
            int pages = 0;
            do {
                final DiscoverSearch.Result page = cursor == null
                        ? DiscoverSearch.search(ALL, query("sort", sort.wire(), "limit", "2"))
                        : DiscoverSearch.search(ALL, query("sort", sort.wire(), "limit", "2", "cursor", cursor));
                Assert.assertEquals(page.total(), 5, "the total is of the whole list, on every page");
                paged.addAll(ids(page));
                cursor = page.nextCursor();
                pages++;
            } while (cursor != null);
            Assert.assertEquals(paged, whole, "paging " + sort + " two at a time");
            Assert.assertEquals(pages, 3);
        }
    }

    /** A cursor is a place in the order, not a row number: a trip delisted between two pages costs nothing. */
    @Test
    public void aCursorSurvivesTheTripItPointsAtBeingDelisted() {
        final DiscoverSearch.Result first = DiscoverSearch.search(ALL, query("limit", "2"));
        Assert.assertEquals(ids(first), List.of("t-nowhere", "t-medj"));

        final List<Entry> medjugorjeGone = entries(FATIMA, PERU, CONFERENCE, NOWHERE);
        final DiscoverSearch.Result second = DiscoverSearch.search(medjugorjeGone,
                query("limit", "2", "cursor", first.nextCursor()));

        Assert.assertEquals(ids(second), List.of("t-peru", "t-fatima"), "the list goes on after where it stood");
    }

    @Test
    public void theLimitIsClampedNotRefused() {
        Assert.assertEquals(query("limit", "1000").limit(), DiscoverQuery.MAX_LIMIT);
        Assert.assertEquals(query("limit", "0").limit(), 1);
        Assert.assertEquals(query("limit", " 7 ").limit(), 7);
        Assert.assertEquals(ids("limit", "1"), List.of("t-nowhere"));
    }

    // ------------------------------------------------------------------ facets

    @Test
    public void facetsCountEveryValueOfTheWholeList() {
        final DiscoverSearch.Result all = DiscoverSearch.search(ALL, DiscoverQuery.all());

        Assert.assertEquals(counts(all.facets().countries()), Map.of("BA", 1, "PT", 1, "FR", 1, "PE", 1, "US", 1),
                "a trip is counted once under EACH of its countries");
        Assert.assertEquals(counts(all.facets().tags()), Map.of("pilgrimage", 2, "retreat", 2, "family", 1, "tour", 1));
        Assert.assertEquals(counts(all.facets().languages()), Map.of("English", 4, "Spanish", 1));
        Assert.assertEquals(counts(all.facets().kinds()), Map.of("PILGRIMAGE", 4, "CONFERENCE", 1));
        Assert.assertEquals(counts(all.facets().organizations()), Map.of(PARISH, 3, TREKS, 2));
        Assert.assertEquals(all.facets().months().stream().map(DiscoverFacetDto::value).toList(),
                List.of("2026-11", "2026-12", "2027-01", "2027-03"), "months oldest first");
        Assert.assertEquals(counts(all.facets().months()).get("2027-01"), Integer.valueOf(2));
    }

    @Test
    public void facetsCarryLabelsPeopleReadAndTheOrganizationsLogo() {
        final DiscoverSearch.Result all = DiscoverSearch.search(ALL, DiscoverQuery.all());

        Assert.assertEquals(labelOf(all.facets().countries(), "BA"), "Bosnia & Herzegovina");
        Assert.assertEquals(labelOf(all.facets().countries(), "FR"), "France");
        Assert.assertEquals(labelOf(all.facets().tags(), "retreat"), "Retreat");
        Assert.assertEquals(labelOf(all.facets().languages(), "Spanish"), "Español", "a language in its own words");
        Assert.assertEquals(labelOf(all.facets().kinds(), "CONFERENCE"), "Conference");
        Assert.assertEquals(labelOf(all.facets().months(), "2026-12"), "December 2026");
        final DiscoverFacetDto treks = facet(all.facets().organizations(), TREKS);
        Assert.assertEquals(treks.label(), "Wayfarer Treks");
        Assert.assertEquals(treks.logoUrl(), "https://cdn.example/treks-logo.png");
        Assert.assertNull(facet(all.facets().organizations(), PARISH).logoUrl(), "no uploaded logo, no field");
        Assert.assertEquals(all.facets().organizations().get(0).value(), PARISH, "most trips first");
        Assert.assertEquals(all.facets().languages().get(0).value(), "English");
    }

    /**
     * A filter's own facet is counted WITHOUT that filter, every other one with it: choosing Bosnia must not
     * hide Portugal's chip beside it, and must cut the tags down to Bosnia's.
     */
    @Test
    public void aFacetIsCountedWithEveryOtherFilterApplied() {
        final DiscoverSearch.Result bosnia = DiscoverSearch.search(ALL, query("country", "BA"));

        Assert.assertEquals(counts(bosnia.facets().countries()), Map.of("BA", 1, "PT", 1, "FR", 1, "PE", 1, "US", 1),
                "the country chips keep their own counts");
        Assert.assertEquals(counts(bosnia.facets().tags()), Map.of("pilgrimage", 1), "only Bosnia's tags are offered");
        Assert.assertEquals(counts(bosnia.facets().organizations()), Map.of(PARISH, 1));

        final DiscoverSearch.Result retreatsOfParish = DiscoverSearch.search(ALL,
                query("tag", "retreat", "org", PARISH));
        Assert.assertEquals(ids(retreatsOfParish), List.of("t-fatima"));
        Assert.assertEquals(counts(retreatsOfParish.facets().organizations()), Map.of(PARISH, 1, TREKS, 1),
                "organizations among the retreats, whichever organization is chosen");
        Assert.assertEquals(counts(retreatsOfParish.facets().tags()), Map.of("pilgrimage", 2, "retreat", 1),
                "tags among the parish's trips, whichever tag is chosen");
        Assert.assertEquals(counts(retreatsOfParish.facets().countries()), Map.of("PT", 1, "FR", 1),
                "both filters applied: the one trip left, under each of its two countries");
    }

    /** Words, dates and the state filter have no facet of their own, so they narrow every facet. */
    @Test
    public void wordsDatesAndStateNarrowEveryFacet() {
        Assert.assertEquals(counts(DiscoverSearch.search(ALL, query("q", "wayfarer")).facets().organizations()),
                Map.of(TREKS, 2));
        Assert.assertEquals(counts(DiscoverSearch.search(ALL, query("from", "2027-03-01")).facets().countries()),
                Map.of("US", 1));
        Assert.assertEquals(counts(DiscoverSearch.search(ALL, query("state", "full")).facets().countries()),
                Map.of("PT", 1, "FR", 1));
        Assert.assertEquals(DiscoverSearch.search(ALL, query("q", "nothing-matches-this")).facets().countries(),
                List.of(), "no results, so no value is offered at all");
    }

    private static DiscoverFacetDto facet(final List<DiscoverFacetDto> facets, final String value) {
        return facets.stream().filter(facet -> value.equals(facet.value())).findFirst().orElseThrow();
    }

    private static String labelOf(final List<DiscoverFacetDto> facets, final String value) {
        return facet(facets, value).label();
    }

    // ------------------------------------------------------------------ malformed parameters

    @Test
    public void malformedParametersAreRefusedWithAMessage() {
        assertRefused("month must be yyyy-MM.", "month", "December");
        assertRefused("from must be a date, yyyy-MM-dd.", "from", "12/17/2026");
        assertRefused("to must be a date, yyyy-MM-dd.", "to", "soon");
        assertRefused("to must not be before from.", "from", "2027-01-02", "to", "2027-01-01");
        assertRefused("limit must be a number.", "limit", "many");
        assertRefused("Unknown state; expected open, full, closed or notOpen.", "state", "waitlist");
        assertRefused("cursor does not belong to this list.", "cursor", "!!not base64!!");
        assertRefused("cursor does not belong to this list.", "cursor", "bm90IGEgY3Vyc29y");
        try {
            query("sort", "price");
            Assert.fail("an unknown sort must be refused");
        } catch (final IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().startsWith("Unknown sort"), expected.getMessage());
        }
    }

    @Test
    public void aCursorFromAnotherOrderIsRefused() {
        final String byTitle = DiscoverSearch.search(ALL, query("sort", "title", "limit", "1")).nextCursor();

        assertRefused("cursor does not belong to this list.", "sort", "departure", "cursor", byTitle);
        Assert.assertEquals(ids("sort", "title", "limit", "1", "cursor", byTitle), List.of("t-nowhere"));
    }

    @Test
    public void tooManyValuesOnOneFilterAreRefused() {
        final Map<String, List<String>> params = new HashMap<>();
        final List<String> countries = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            countries.add("C" + i);
        }
        params.put("country", countries);

        try {
            DiscoverQuery.parse(params);
            Assert.fail("51 values must be refused");
        } catch (final IllegalArgumentException expected) {
            Assert.assertEquals(expected.getMessage(), "Too many values for country.");
        }
    }

    /** Two requests that differ only in parameter order are one question: the ETag is derived from the query. */
    @Test
    public void parameterOrderDoesNotChangeTheQuery() {
        Assert.assertEquals(query("country", "PT", "country", "BA", "tag", "retreat").toString(),
                query("tag", "retreat", "country", "ba", "country", "pt").toString());
        Assert.assertNotEquals(query("country", "PT").toString(), query("country", "BA").toString());
    }

    private static void assertRefused(final String message, final String... pairs) {
        try {
            query(pairs);
            Assert.fail("expected a refusal: " + message);
        } catch (final IllegalArgumentException expected) {
            Assert.assertEquals(expected.getMessage(), message);
        }
    }
}
