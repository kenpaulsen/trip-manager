package org.paulsens.trip.util;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * What counts as a mailable address.
 *
 * <p>The case driving this is real data, not a hypothetical: some people on a trip have no email address and the
 * field holds a bare name. SES rejects a request containing any malformed destination, so one such person in a
 * mail merge costs every recipient after them their mail.
 */
public class EmailAddressesTest {

    @DataProvider(name = "unusable")
    public Object[][] unusable() {
        return new Object[][] {
                {null, "null"},
                {"", "empty"},
                {"   ", "blank"},
                {"joe.smith", "a bare name -- the case this exists for: no @ at all"},
                {"joe.smith@", "no domain"},
                {"@example.com", "no local part"},
                {"joe@smith", "domain with no dot"},
                {"joe@.com", "dot immediately after the @, so no domain label"},
                {"joe@example.", "domain ending in a dot"},
                {"joe smith@example.com", "space in the local part"},
                {"joe@exam ple.com", "space in the domain"},
                {"joe\t@example.com", "a tab"},
                {"joe@example.com\u00a0", "a no-break space, which trim() keeps"},
                {"joe\u202f@example.com", "a narrow no-break space"},
                {"joe@exa\u200bmple.com", "a zero-width space pasted in with the address"},
                {"\ufeffjoe@example.com", "a byte-order mark"},
        };
    }

    @Test(dataProvider = "unusable")
    public void unusableAddressesAreRejected(final String candidate, final String why) {
        Assert.assertFalse(EmailAddresses.isValid(candidate), "Should be rejected: " + why);
        Assert.assertNull(EmailAddresses.normalize(candidate), "normalize must agree with isValid: " + why);
    }

    @DataProvider(name = "usable")
    public Object[][] usable() {
        return new Object[][] {
                {"joe@example.com"},
                {"joe.smith@example.com"},
                {"joe+chat@example.co.uk"},
                {"j@a.io"},
        };
    }

    @Test(dataProvider = "usable")
    public void usableAddressesAreAccepted(final String candidate) {
        Assert.assertTrue(EmailAddresses.isValid(candidate), candidate + " should be accepted");
    }

    /** A list piece is held to the same rule: an invisible space in one piece names that piece. */
    @Test
    public void aListPieceWithAnInvisibleSpaceIsTheUnusableOne() {
        Assert.assertEquals(EmailAddresses.firstUnusableInList("a@x.org, b\u00a0@y.org"), "b\u00a0@y.org");
        Assert.assertEquals(EmailAddresses.firstUnusableInList("a@x.org,b@y\u2007.org"), "b@y\u2007.org");
        Assert.assertEquals(EmailAddresses.firstUnusableInList("a@x.org;b@y.org"), "a@x.org;b@y.org");
        Assert.assertNull(EmailAddresses.firstUnusableInList(" a@x.org , b@y.org "));
    }

    @Test
    public void surroundingWhitespaceIsTrimmedNotRejected() {
        // Addresses arrive from a comma-separated list, so padding is normal and must not disqualify anyone.
        Assert.assertEquals(EmailAddresses.normalize("  joe@example.com  "), "joe@example.com");
    }

    @Test
    public void aDisplayNameWithSpecialsIsQuotedAndReadsBack() {
        Assert.assertEquals(EmailAddresses.phrase("  Queen   of Peace "), "Queen of Peace", "plain words stay bare");
        Assert.assertEquals(EmailAddresses.phrase(null), "");
        for (final String name : new String[] {"Parish Office, St. Joseph", "Team: Bob", "O\"Neil \\ Jr.", "a;b"}) {
            final String phrase = EmailAddresses.phrase(name);
            Assert.assertTrue(phrase.startsWith("\"") && phrase.endsWith("\""), "quoted: " + phrase);
            Assert.assertEquals(EmailAddresses.unphrase(phrase), name, "round trip of " + name);
        }
        Assert.assertEquals(EmailAddresses.phrase("O\"Neil \\ Jr."), "\"O\\\"Neil \\\\ Jr.\"");
        Assert.assertEquals(EmailAddresses.unphrase(" Plain Name "), "Plain Name");
        Assert.assertEquals(EmailAddresses.unphrase("\""), "\"", "a lone quote is not a quoted string");
        Assert.assertEquals(EmailAddresses.unphrase(null), "");
    }
}
