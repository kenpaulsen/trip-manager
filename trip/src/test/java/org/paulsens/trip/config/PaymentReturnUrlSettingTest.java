package org.paulsens.trip.config;

import org.paulsens.trip.api.RedirectAllowlist;
import org.paulsens.trip.model.Config;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The payment return-address allowlist.
 *
 * <p>{@code PaymentsResource} lets a native client choose where PayPal returns the payer, because a mobile app
 * has no server-rendered page to come back to. Unchecked, that is an open redirect with an unusually good
 * disguise: the payment is genuine, it really does start on this site and really does go to PayPal, so a payer
 * returned to an attacker's page has every reason to believe they are still where they started.
 *
 * <p>These pin the prefix matching itself. The rule has to reject on the whole prefix, not on a substring or a
 * suffix, because {@code https://visitqueenofpeace.com.evil.example} and
 * {@code https://evil.example/?x=https://visitqueenofpeace.com} both contain a legitimate address.
 */
public class PaymentReturnUrlSettingTest {

    @Test
    public void theAllowlistIsDeclaredInTheRegistrySoItIsEditableWithoutADeploy() {
        // The convention: one declaration drives both the code and admin/settings.xhtml. A literal key and
        // default in the resource would leave the setting invisible on that page and unchangeable at runtime --
        // which matters here, because an app's custom scheme has to be added at release time.
        Assert.assertTrue(KnownSettings.isKnown(KnownSettings.PAYMENT_RETURN_URL_PREFIXES.getName()));
        Assert.assertEquals(KnownSettings.PAYMENT_RETURN_URL_PREFIXES.getType(), Config.Type.STRING);
        Assert.assertTrue(KnownSettings.PAYMENT_RETURN_URL_PREFIXES.getDefaultValue().contains("https://"),
                "The shipped default must be usable as-is, or the web flow's own addresses stop matching.");
    }

    @Test
    public void anAddressUnderAnAllowedPrefixIsAccepted() {
        Assert.assertTrue(allowed("https://my.centermirmedjugorje.com/trip/pay.jsf?trip=abc"));
    }

    @Test
    public void aLookalikeDomainThatMerelyStartsWithTheSameLettersIsRefused() {
        // The classic bypass: prefix matching on a bare host lets evil.example register a longer name.
        Assert.assertFalse(allowed("https://my.centermirmedjugorje.com.evil.example/steal"));
    }

    @Test
    public void anAllowedAddressAppearingLaterInTheUrlIsRefused() {
        // Substring matching instead of prefix matching would accept this.
        Assert.assertFalse(allowed("https://evil.example/?next=https://my.centermirmedjugorje.com"));
    }

    @Test
    public void aBlankOrMissingAddressIsRefusedRatherThanTreatedAsUnset() {
        // Failing open here would mean "send no returnUrl" is the way to bypass the check entirely.
        Assert.assertFalse(allowed(null));
        Assert.assertFalse(allowed("   "));
    }

    @Test
    public void anUnrelatedSchemeIsRefusedUntilSomebodyAddsIt() {
        // A mobile app's custom scheme is expected to be added to the setting before shipping -- deliberately
        // not accepted by default, since "any custom scheme" would accept every attacker's too.
        Assert.assertFalse(allowed("trip://payment/done"));
    }

    @Test
    public void anExactAllowedOriginWithNoPathIsAccepted() {
        Assert.assertTrue(allowed("https://visitqueenofpeace.com"));
    }

    /**
     * The real rule against the shipped default -- deliberately NOT a copy of it.
     *
     * <p>An earlier version of this test reimplemented the matching, and passed while the resource itself had
     * the prefix-bypass below. A test that mirrors the implementation only ever proves the mirror is right.
     */
    private static boolean allowed(final String url) {
        return RedirectAllowlist.allows(url, KnownSettings.PAYMENT_RETURN_URL_PREFIXES.getDefaultValue());
    }

    // ------------------------------------------------------------------ organization sites

    /** The org-site rule with a stand-in resolver: exactly {@code acme.unitetrip.com} is an org host. */
    private static boolean orgSite(final String url) {
        return RedirectAllowlist.allowsOrgSite(url, "acme.unitetrip.com"::equals);
    }

    @Test
    public void anOrganizationsOwnSiteIsAcceptedWithoutBeingListed() {
        Assert.assertTrue(orgSite("https://acme.unitetrip.com/trip/payment.jsf?trip=x"));
        Assert.assertTrue(orgSite("https://acme.unitetrip.com"));
        Assert.assertTrue(orgSite("HTTPS://ACME.unitetrip.com/pay"), "scheme and host are case-insensitive");
    }

    @Test
    public void onlyKnownSlugsOverHttpsCount() {
        Assert.assertFalse(orgSite("https://typo.unitetrip.com/pay"), "an unknown label is not a site");
        Assert.assertFalse(orgSite("http://acme.unitetrip.com/pay"), "cleartext is refused outright");
        Assert.assertFalse(orgSite("https://acme.unitetrip.com.evil.example/pay"), "the whole host, never a prefix");
        Assert.assertFalse(orgSite("https://evil.example/?next=https://acme.unitetrip.com"));
        Assert.assertFalse(orgSite("trip://acme.unitetrip.com/done"));
        Assert.assertFalse(orgSite("https:///no-host"));
        Assert.assertFalse(orgSite("https://bad host/"), "unparseable is refused, not thrown");
        Assert.assertFalse(orgSite(null));
        Assert.assertFalse(orgSite("  "));
    }
}
