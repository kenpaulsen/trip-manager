package org.paulsens.trip.action;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * {@code #{legacyPages}}: off a request, and with no data loaded, the menu keeps its old addresses. The link-out
 * rule itself is {@code LegacyPagesTest}'s.
 */
public class LegacyPageCommandsTest {

    @Test
    public void withNothingLoadedTheMenuKeepsItsAddresses() {
        Assert.assertEquals(new LegacyPageCommands().href("/dm"), "/dm");
        Assert.assertNull(new LegacyPageCommands().href(null));
    }
}
