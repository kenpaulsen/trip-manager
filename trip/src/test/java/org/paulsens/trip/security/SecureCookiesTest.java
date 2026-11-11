package org.paulsens.trip.security;

import jakarta.servlet.http.HttpServletRequest;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.Test;

/** The one rule for a cookie's Secure attribute: always in production, the request's scheme in local mode. */
public class SecureCookiesTest {

    @Test
    public void productionCookiesAreSecureWhateverTheRequestSays() {
        // The case this class exists for: a request that reads as plain http in production (a load-balancer
        // header not applied) must not turn the flag off.
        Assert.assertTrue(SecureCookies.flagFor(false, false));
        Assert.assertTrue(SecureCookies.flagFor(true, false));
    }

    @Test
    public void localModeFollowsTheRequestSoPlainHttpLocalhostStillWorks() {
        Assert.assertFalse(SecureCookies.flagFor(false, true));
        Assert.assertTrue(SecureCookies.flagFor(true, true));
    }

    @Test
    public void theRequestFormReadsTheSchemeAndTheMode() {
        // The suite runs in local mode, so the request's scheme is what decides here.
        final HttpServletRequest plain = Mockito.mock(HttpServletRequest.class);
        final HttpServletRequest tls = Mockito.mock(HttpServletRequest.class);
        Mockito.when(tls.isSecure()).thenReturn(true);

        Assert.assertFalse(SecureCookies.flagFor(plain));
        Assert.assertTrue(SecureCookies.flagFor(tls));
    }
}
