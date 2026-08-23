package org.paulsens.trip.model;

import java.time.LocalDateTime;
import java.util.Locale;
import org.testng.Assert;
import org.testng.annotations.Test;

/** The landing-page Trip helpers: title prefix stripping, external/CFPW derivation, month labels. */
public class TripPublicHelpersTest {

    @Test
    public void shortTitleStripsOneSortPrefix() {
        Assert.assertEquals(titled("2026 Sep: Holy Angels").getShortTitle(), "Holy Angels");
        Assert.assertEquals(titled("2026: Fall Trip").getShortTitle(), "Fall Trip");
        Assert.assertEquals(titled("ago 2026:  Vengan y Vean").getShortTitle(), "Vengan y Vean");
    }

    @Test
    public void shortTitleLeavesUnprefixedTitlesAlone() {
        Assert.assertEquals(titled("Holy Angels").getShortTitle(), "Holy Angels");
        // A colon beyond the prefix window is part of the name, not a sort prefix.
        Assert.assertEquals(titled("A Pilgrimage of Peace: the Return").getShortTitle(),
                "A Pilgrimage of Peace: the Return");
        Assert.assertEquals(titled("x").getShortTitle(), "x");
        Assert.assertEquals(titled("").getShortTitle(), "");
        Assert.assertEquals(Trip.builder().title(null).build().getShortTitle(), "");
    }

    @Test
    public void externalMeansNonHostedUrl() {
        Assert.assertFalse(titled("t").isExternal());
        final Trip blankUrl = titled("t");
        blankUrl.setNonHostedTripUrl("  ");
        Assert.assertFalse(blankUrl.isExternal());
        final Trip external = titled("t");
        external.setNonHostedTripUrl("https://example.com");
        Assert.assertTrue(external.isExternal());
    }

    @Test
    public void shownRegCountPrefersTheReportedNumber() {
        final Trip hosted = titled("t");
        hosted.getPeople().add(Person.Id.newInstance());
        Assert.assertEquals(hosted.getShownRegCount(), 1);
        hosted.setNonHostedRegNumber(35);
        Assert.assertEquals(hosted.getShownRegCount(), 35);
    }

    @Test
    public void cfpwFallsBackToTheProviderStringForOrglessLegacyRows() {
        // No orgId on any of these, so isCfpw exercises the legacy provider-string fallback; the org-keyed
        // primary path (including a provider re-synced to a full org name) is pinned in TripCommandsTest.
        Assert.assertFalse(titled("t").isCfpw());
        final Trip cfpw = titled("t");
        cfpw.setProvider("cfpw");
        Assert.assertTrue(cfpw.isCfpw());
        cfpw.setProvider("Queen of Peace Centre");
        Assert.assertFalse(cfpw.isCfpw());
        // An orgId that resolves to nothing (deleted or unseeded org) also folds to the fallback.
        final Trip ghostOrg = titled("t");
        ghostOrg.setOrgId("no-such-org");
        ghostOrg.setProvider("CFPW");
        Assert.assertTrue(ghostOrg.isCfpw());
    }

    @Test
    public void startMonthAbbrevUsesTheTripsOwnLanguage() {
        final Trip english = titled("t");
        english.setStartDate(LocalDateTime.of(2026, 9, 20, 0, 0));
        english.setLanguage(Language.English);
        Assert.assertEquals(english.getStartMonthAbbrev(), "Sep");

        final Trip spanish = titled("t");
        spanish.setStartDate(LocalDateTime.of(2026, 8, 10, 0, 0));
        spanish.setLanguage(Language.Spanish);
        Assert.assertEquals(spanish.getStartMonthAbbrev().toLowerCase(Locale.ROOT), "ago");

        final Trip nullLanguage = titled("t");
        nullLanguage.setStartDate(LocalDateTime.of(2026, 1, 1, 0, 0));
        Assert.assertEquals(nullLanguage.getStartMonthAbbrev(), "Jan", "null language folds to English");

        final Trip noDate = titled("t");
        noDate.setStartDate(null);
        Assert.assertEquals(noDate.getStartMonthAbbrev(), "");
    }

    @Test
    public void startDateIsoFeedsTheBrowserCountdown() {
        // The sidebar's countdown is computed in the BROWSER from this zone-naive ISO date; the time-of-day
        // part of the stored LocalDateTime must not leak into it.
        final Trip trip = titled("t");
        trip.setStartDate(LocalDateTime.of(2026, 8, 10, 14, 30));
        Assert.assertEquals(trip.getStartDateIso(), "2026-08-10");
        trip.setStartDate(null);
        Assert.assertEquals(trip.getStartDateIso(), "", "no date renders an empty data attribute");
    }

    @Test
    public void languageEnumCarriesDisplayNameAndLocale() {
        Assert.assertEquals(Language.English.getDisplayName(), "English");
        Assert.assertEquals(Language.Spanish.getDisplayName(), "Español");
        Assert.assertEquals(Language.Spanish.getLocale().getLanguage(), "es");
        Assert.assertEquals(Language.values()[0], Language.English, "declaration order is display order");
    }

    private static Trip titled(final String title) {
        return Trip.builder().title(title).build();
    }
}
