package org.paulsens.trip.web;

import java.util.List;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The policy's shape: every directive present once and in order, the foreign sources each page actually needs
 * (Stripe, PayPal, the analytics tag, the microsite embeds, the media CDN) and nothing broader than the
 * documented reason for it, with the media origin folded in only where the CDN serves that kind of resource.
 */
public class ContentSecurityPolicyTest {

    private static final String MEDIA = "https://files.example.org";

    @Test
    public void everyDirectiveAppearsOnceInOrder() {
        final String policy = ContentSecurityPolicy.build(MEDIA, "/csp-report");
        Assert.assertEquals(ContentSecurityPolicy.directiveNames(policy), List.of("default-src", "base-uri",
                "frame-ancestors", "form-action", "script-src", "style-src", "img-src", "font-src", "connect-src",
                "frame-src", "object-src", "media-src", "worker-src", "manifest-src", "report-uri", "report-to"));
    }

    @Test
    public void scriptsComeFromUsStripeAndTheAnalyticsTagOnly() {
        final String policy = ContentSecurityPolicy.build(MEDIA, "/csp-report");
        Assert.assertTrue(policy.contains("script-src 'self' 'unsafe-inline' 'unsafe-eval' https://js.stripe.com"
                + " https://*.js.stripe.com https://www.googletagmanager.com;"), policy);
        // The CDN serves images, stylesheets, fonts and PDFs; it must never be a script source.
        Assert.assertFalse(policy.contains("script-src 'self' 'unsafe-inline' 'unsafe-eval' " + MEDIA), policy);
    }

    @Test
    public void anyHttpsSiteMayBeFramedOrPlayedButNeverRunScript() {
        final String policy = ContentSecurityPolicy.build(MEDIA, "/csp-report");
        Assert.assertTrue(policy.contains("frame-src 'self' https:;"), policy);
        Assert.assertTrue(policy.contains("media-src 'self' blob: https:;"), policy);
        Assert.assertTrue(policy.contains("frame-ancestors 'self';"), policy);
        // The widening stops at frames and media: a script source of https: would be the whole policy's undoing.
        Assert.assertFalse(policy.contains("'unsafe-eval' https:;"), policy);
        Assert.assertTrue(policy.contains("'unsafe-eval' https://js.stripe.com"), policy);
    }

    @Test
    public void formsPostToUsOrToPayPal() {
        final String policy = ContentSecurityPolicy.build(MEDIA, "/csp-report");
        Assert.assertTrue(policy.contains(
                "form-action 'self' https://www.paypal.com https://www.sandbox.paypal.com;"), policy);
    }

    @Test
    public void stylesAndFontsComeFromAnyHttpsSource() {
        // A trip stylesheet on the CDN @imports a font service, and the service is the org's choice.
        final String policy = ContentSecurityPolicy.build(MEDIA, "/csp-report");
        Assert.assertTrue(policy.contains("style-src 'self' 'unsafe-inline' https:;"), policy);
        Assert.assertTrue(policy.contains("font-src 'self' data: https:;"), policy);
    }

    @Test
    public void theMediaOriginJoinsConnectionsObjectsAndMedia() {
        final String policy = ContentSecurityPolicy.build(MEDIA, "/csp-report");
        Assert.assertTrue(policy.contains(" https://www.googletagmanager.com " + MEDIA + "; frame-src"), policy);
        Assert.assertTrue(policy.contains("object-src 'self' " + MEDIA + ";"), policy);
        // Images are any https source already, so the origin adds nothing there.
        Assert.assertTrue(policy.contains("img-src 'self' data: blob: https:;"), policy);
    }

    @Test
    public void withoutAMediaOriginTheDirectivesStandAlone() {
        final String policy = ContentSecurityPolicy.build(null, "/csp-report");
        Assert.assertTrue(policy.contains("style-src 'self' 'unsafe-inline' https:; img-src"), policy);
        Assert.assertTrue(policy.contains("object-src 'self'; media-src 'self' blob: https:; worker-src"), policy);
        Assert.assertFalse(policy.contains("null"), policy);
    }

    @Test
    public void bothReportMechanismsNameTheSameEndpoint() {
        final String policy = ContentSecurityPolicy.build(null, "/violations");
        Assert.assertTrue(policy.endsWith("report-uri /violations; report-to csp-endpoint"), policy);
        Assert.assertEquals(ContentSecurityPolicy.reportingEndpoints("/violations"), "csp-endpoint=\"/violations\"");
    }

    @Test
    public void theOriginOfABaseUrlDropsAnyPath() {
        Assert.assertEquals(ContentSecurityPolicy.originOf("https://files.example.org"), "https://files.example.org");
        Assert.assertEquals(ContentSecurityPolicy.originOf(" https://files.example.org/ "),
                "https://files.example.org");
        Assert.assertEquals(ContentSecurityPolicy.originOf("https://files.example.org/media/x"),
                "https://files.example.org");
        Assert.assertEquals(ContentSecurityPolicy.originOf("http://localhost:8080/"), "http://localhost:8080");
        Assert.assertNull(ContentSecurityPolicy.originOf(null));
        Assert.assertNull(ContentSecurityPolicy.originOf("   "));
        Assert.assertNull(ContentSecurityPolicy.originOf("files.example.org"), "no scheme, no origin");
        Assert.assertNull(ContentSecurityPolicy.originOf("https://"), "a scheme alone is not an origin");
    }
}
