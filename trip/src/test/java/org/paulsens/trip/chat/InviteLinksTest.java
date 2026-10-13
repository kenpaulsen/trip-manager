package org.paulsens.trip.chat;

import java.util.Optional;
import org.testng.Assert;
import org.testng.annotations.Test;

/** The short invite link round-trips, and the length it buys is real. */
public class InviteLinksTest {

    private static final String TRIP = "30692184-e927-4398-b8f9-a6e1eda1ea7b";

    @Test
    public void aUuidPacksTo22CharactersAndBack() {
        final String compact = InviteLinks.compactTripId(TRIP);
        Assert.assertEquals(compact.length(), 22);
        Assert.assertFalse(compact.contains("="), "unpadded");
        Assert.assertEquals(InviteLinks.expandTripId(compact), TRIP);
        Assert.assertEquals(InviteLinks.expandTripId(TRIP), TRIP, "a client may send the UUID as it is");
    }

    @Test
    public void aNonUuidIdTravelsAsItself() {
        Assert.assertEquals(InviteLinks.compactTripId("faketrip"), "faketrip");
        Assert.assertEquals(InviteLinks.expandTripId("faketrip"), "faketrip");
        Assert.assertEquals(InviteLinks.compactTripId("odd id/with"), "odd+id%2Fwith");
        Assert.assertEquals(InviteLinks.expandTripId("odd+id%2Fwith"), "odd id/with");
        // 22 characters that are not base64url, or not 16 bytes, are read as a plain id.
        Assert.assertEquals(InviteLinks.expandTripId("twenty-two-chars-long!"), "twenty-two-chars-long!");
        Assert.assertNull(InviteLinks.expandTripId(null));
    }

    @Test
    public void theShortPathIsUnder60CharactersForARealLink() {
        final String path = InviteLinks.shortPath(TRIP, "AbCdEfGh", "0123456789abcdefghijkl");
        Assert.assertTrue(path.startsWith("/i/"));
        Assert.assertTrue(path.length() < 60, path + " is " + path.length());
        final Optional<InviteLinks.Parsed> parsed = InviteLinks.parse(path.substring("/i".length()));
        Assert.assertEquals(parsed.orElseThrow().tripId(), TRIP);
        Assert.assertEquals(parsed.orElseThrow().token(), "AbCdEfGh.0123456789abcdefghijkl");
    }

    @Test
    public void theLandingPathCarriesTheExpandedIdAndTheWholeToken() {
        Assert.assertEquals(InviteLinks.landingPath(TRIP, "sel.val"),
                "/trip/chatInvite.jsf?trip=" + TRIP + "&token=sel.val");
        Assert.assertEquals(InviteLinks.landingPath("a b", "s.v"), "/trip/chatInvite.jsf?trip=a+b&token=s.v");
    }

    @Test
    public void malformedPathsDoNotParse() {
        for (final String bad : new String[] {null, "", "/", "/onlytrip", "/trip/", "/trip/notoken", "/trip/.v",
                "/trip/s.", "/trip/s.v/extra", "//s.v"}) {
            Assert.assertTrue(InviteLinks.parse(bad).isEmpty(), "should not parse: " + bad);
        }
    }
}
