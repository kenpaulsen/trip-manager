package org.paulsens.trip.action;

import java.text.Collator;
import java.util.Locale;
import org.testng.Assert;
import org.testng.annotations.Test;

/** The ISO country list a trip's Country menu offers (issue 154). */
public class IsoCountriesTest {

    @Test
    public void codesNormalizeAndNameInEnglish() {
        Assert.assertEquals(IsoCountries.normalize(" ba "), "BA");
        Assert.assertNull(IsoCountries.normalize("XX"), "Not a country");
        Assert.assertNull(IsoCountries.normalize(""));
        Assert.assertNull(IsoCountries.normalize(null));
        Assert.assertTrue(IsoCountries.nameOf("ba").startsWith("Bosnia"), IsoCountries.nameOf("ba"));
        Assert.assertEquals(IsoCountries.nameOf("ZZ"), "");
    }

    @Test
    public void aListOfCodesKeepsRealOnesOnceInOrder() {
        Assert.assertEquals(IsoCountries.normalizeAll(java.util.Arrays.asList(" pt ", "XX", null, "FR", "PT", "")),
                java.util.List.of("PT", "FR"));
        Assert.assertEquals(IsoCountries.normalizeAll(null), java.util.List.of());
        Assert.assertEquals(IsoCountries.normalizeAll(java.util.List.of()), java.util.List.of());
    }

    @Test
    public void theListIsEveryCountrySortedByName() {
        Assert.assertTrue(IsoCountries.all().size() > 240, "Every ISO alpha-2 country");
        Assert.assertTrue(IsoCountries.all().stream().anyMatch(c -> "US".equals(c.code())
                && "United States".equals(c.name())));
        final Collator collator = Collator.getInstance(Locale.US);
        for (int i = 1; i < IsoCountries.all().size(); i++) {
            Assert.assertTrue(collator.compare(IsoCountries.all().get(i - 1).name(),
                    IsoCountries.all().get(i).name()) <= 0);
        }
        Assert.assertNotEquals(IsoCountries.all().getLast().code(), "AX",
                "An accented first letter sorts with its base letter, not after Zimbabwe");
        Assert.assertTrue(IsoCountries.all().stream().limit(10).anyMatch(c -> "AX".equals(c.code())),
                "\u00c5land Islands is among the A's");
    }

    /** The owner's list (2026-11-17), spot-checked: search names beside the shown one, which does not change. */
    @Test
    public void aCountryHasTheEnglishNamesPeopleType() {
        Assert.assertEquals(IsoCountries.aliasesOf("tr"), java.util.List.of("Turkey"));
        Assert.assertEquals(IsoCountries.aliasesOf("GB"), java.util.List.of("UK", "United Kingdom", "Great Britain",
                "Britain", "England", "Scotland", "Wales", "Northern Ireland"));
        Assert.assertEquals(IsoCountries.aliasesOf("CD"), java.util.List.of("Congo", "DR Congo", "DRC"));
        Assert.assertEquals(IsoCountries.aliasesOf("CG"), java.util.List.of("Congo"), "one alias, two countries");
        Assert.assertEquals(IsoCountries.aliasesOf("FR"), java.util.List.of(), "most countries have none");
        Assert.assertEquals(IsoCountries.aliasesOf("ZZ"), java.util.List.of());
        Assert.assertEquals(IsoCountries.aliasesOf(null), java.util.List.of());
        final IsoCountries.Country turkey = IsoCountries.all().stream()
                .filter(country -> "TR".equals(country.code())).findFirst().orElseThrow();
        Assert.assertNotEquals(turkey.name(), "Turkey", "the shown name stays the JDK's");
        Assert.assertEquals(turkey.aliases(), java.util.List.of("Turkey"));
        Assert.assertEquals(IsoCountries.all().stream().filter(country -> !country.aliases().isEmpty()).count(), 33L,
                "the owner's list names 33 countries");
    }

    /** The table is typed, so only this can catch a code that is no country (a mistyped key matches nothing). */
    @Test
    public void everyAliasNamesARealCountry() throws Exception {
        final java.util.Map<?, ?> byName =
                new com.fasterxml.jackson.databind.ObjectMapper().readValue(IsoCountries.aliasesByNameJson(),
                        java.util.Map.class);
        Assert.assertEquals(byName.size(), 33, "every one of the owner's 33 codes is a real country with a name");
        Assert.assertFalse(byName.containsKey(""), "no alias hangs off an unknown code");
    }

    /** What the trip editor's filter reads: shown names to their aliases, and nothing for the rest. */
    @Test
    public void theFilterReadsTheAliasesByShownName() throws Exception {
        final java.util.Map<?, ?> byName =
                new com.fasterxml.jackson.databind.ObjectMapper().readValue(IsoCountries.aliasesByNameJson(),
                        java.util.Map.class);
        Assert.assertEquals(byName.size(), 33);
        Assert.assertEquals(byName.get(IsoCountries.nameOf("TR")), java.util.List.of("Turkey"));
        Assert.assertEquals(byName.get(IsoCountries.nameOf("BA")), java.util.List.of("Bosnia and Herzegovina",
                "Bosnia"));
        Assert.assertNull(byName.get(IsoCountries.nameOf("FR")));
    }
}
