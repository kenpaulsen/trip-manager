package org.paulsens.trip.action;

import java.util.Map;
import org.paulsens.trip.model.Organization;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * {@link BrandCommands#lookOf}: the host-neutral look the REST edge hands to native clients. What is
 * pinned is that it applies the same screening as the page getters (declared palettes only, safe URLs,
 * normalized hex colors) and that an unbranded organization reads as "nothing chosen", never as blanks.
 */
public class BrandCommandsLookTest {

    private static Organization org(final Map<String, String> overrides) {
        final Organization org = new Organization();
        org.setName("Look Org");
        org.getSettingsOverrides().putAll(overrides);
        return org;
    }

    @Test
    public void aBrandedOrgAnswersItsScreenedChoices() {
        final BrandCommands brand = new BrandCommands();
        final BrandCommands.OrgLook look = brand.lookOf(org(Map.of(
                "site.theme.palette", "purple",
                "site.theme.dark", "true",
                "site.layout", "full-width",
                "site.logo.url", "https://cdn.example/logo.png",
                "site.donate.url", "javascript:alert(1)",
                "site.background.color", "#ABCDEF",
                "site.contact.name", "Office")));

        Assert.assertEquals(look.palette(), "purple");
        Assert.assertTrue(look.dark());
        Assert.assertEquals(look.layout(), "full-width");
        Assert.assertEquals(look.logoUrl(), "https://cdn.example/logo.png");
        Assert.assertNull(look.donateUrl(), "a non-http URL is dropped, the same as on the page");
        Assert.assertEquals(look.backgroundColor(), "#abcdef");
        Assert.assertEquals(look.contactName(), "Office");
        Assert.assertEquals(brand.themeNameOf(look), "freya-purple-dark");
    }

    @Test
    public void anUnknownPaletteAndAnUnbrandedOrgReadAsNothingChosen() {
        final BrandCommands brand = new BrandCommands();
        final BrandCommands.OrgLook shipped = brand.lookOf(org(Map.of("site.theme.palette", "chartreuse")));
        Assert.assertNull(shipped.palette(), "a palette that is not shipped would be a stylesheet 404");
        Assert.assertEquals(brand.themeNameOf(shipped), "freya-medj-l");

        final BrandCommands.OrgLook plain = brand.lookOf(org(Map.of()));
        Assert.assertNull(plain.palette());
        Assert.assertFalse(plain.dark());
        Assert.assertNull(plain.logoUrl());
        Assert.assertNull(plain.backgroundColor());

        Assert.assertNull(brand.lookOf(null).palette(), "a missing org is the neutral look, not an NPE");
    }
}
