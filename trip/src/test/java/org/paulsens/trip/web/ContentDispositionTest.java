package org.paulsens.trip.web;

import org.testng.Assert;
import org.testng.annotations.Test;

/** The one spelling of a download's file name: an ASCII fallback every client reads, plus the real name. */
public class ContentDispositionTest {

    @Test
    public void aNonAsciiFileNameKeepsBothForms() {
        Assert.assertEquals(ContentDisposition.attachment("Međugorje \"A\".pdf"),
                "attachment; filename=\"Me_ugorje 'A'.pdf\"; filename*=UTF-8''Me%C4%91ugorje%20%22A%22.pdf");
    }

    @Test
    public void anAsciiNameIsSentBothWaysToo() {
        // filename* is not optional decoration: a client that prefers it must find it on every download.
        Assert.assertEquals(ContentDisposition.attachment("Budget.pdf"),
                "attachment; filename=\"Budget.pdf\"; filename*=UTF-8''Budget.pdf");
    }

    @Test
    public void inlineDiffersOnlyInItsDispositionType() {
        Assert.assertEquals(ContentDisposition.inline("récu 1.jpg"),
                "inline; filename=\"r_cu 1.jpg\"; filename*=UTF-8''r%C3%A9cu%201.jpg");
    }

    @Test
    public void aHeaderCannotBeSplitByTheName() {
        // CR and LF are outside the printable range, so they never reach the quoted form; the starred form encodes.
        final String header = ContentDisposition.attachment("a\r\nSet-Cookie: x=1.pdf");
        Assert.assertFalse(header.contains("\r") || header.contains("\n"), header);
    }
}
