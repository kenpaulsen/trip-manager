package org.paulsens.trip.action;

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
    public void theListIsEveryCountrySortedByName() {
        Assert.assertTrue(IsoCountries.all().size() > 240, "Every ISO alpha-2 country");
        Assert.assertTrue(IsoCountries.all().stream().anyMatch(c -> "US".equals(c.code())
                && "United States".equals(c.name())));
        for (int i = 1; i < IsoCountries.all().size(); i++) {
            Assert.assertTrue(IsoCountries.all().get(i - 1).name().compareTo(IsoCountries.all().get(i).name()) <= 0);
        }
    }
}
