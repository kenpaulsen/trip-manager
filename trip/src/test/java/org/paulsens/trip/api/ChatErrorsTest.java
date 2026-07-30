package org.paulsens.trip.api;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The wire contract, pinned. These strings are behaviour: the client keys "show a Rejoin button", "stop polling
 * forever" and "keep the user's text" off them, so a rename here is a silent UI bug at the far end.
 */
public class ChatErrorsTest {

    @Test
    public void leavingAndBeingRemovedNeverCollapseIntoOneCode() {
        // The whole point of keeping them apart: one offers Rejoin, the other must not.
        Assert.assertNotEquals(ChatErrors.LEFT_CHANNEL, ChatErrors.REMOVED_FROM_CHANNEL);
    }

    @Test
    public void sendResultCodesMapOntoTheDocumentedWireCodes() {
        Assert.assertEquals(ChatErrors.forSendResult("muted"), ChatErrors.MUTED);
        Assert.assertEquals(ChatErrors.forSendResult("archived"), ChatErrors.CHANNEL_ARCHIVED);
        Assert.assertEquals(ChatErrors.forSendResult("empty"), ChatErrors.MESSAGE_EMPTY);
        Assert.assertEquals(ChatErrors.forSendResult("too_long"), ChatErrors.MESSAGE_TOO_LONG);
        Assert.assertEquals(ChatErrors.forSendResult("forbidden"), ChatErrors.FORBIDDEN);
        Assert.assertEquals(ChatErrors.forSendResult("store"), ChatErrors.STORE_FAILED);
    }

    @Test
    public void everyRateLimitTierReportsAsRateLimited() {
        // The limiter names the tier that tripped (burst/sustained/global); the client only needs "429, keep text".
        Assert.assertEquals(ChatErrors.forSendResult("rate_limit"), ChatErrors.RATE_LIMITED);
        Assert.assertEquals(ChatErrors.forSendResult("burst"), ChatErrors.RATE_LIMITED);
        Assert.assertEquals(ChatErrors.forSendResult("sustained"), ChatErrors.RATE_LIMITED);
        Assert.assertEquals(ChatErrors.forSendResult("global"), ChatErrors.RATE_LIMITED);
    }

    @Test
    public void slowModeIsNotReportedAsAMuteOrARateLimit() {
        // A mute and a rate limit must stay distinguishable to the user; so must slow mode.
        Assert.assertEquals(ChatErrors.forSendResult("slow_mode"), ChatErrors.SLOW_MODE);
        Assert.assertNotEquals(ChatErrors.SLOW_MODE, ChatErrors.MUTED);
        Assert.assertNotEquals(ChatErrors.SLOW_MODE, ChatErrors.RATE_LIMITED);
    }

    @Test
    public void anUnknownCodeBecomesInternalRatherThanLeakingItself() {
        Assert.assertEquals(ChatErrors.forSendResult(null), ChatErrors.INTERNAL);
        Assert.assertEquals(ChatErrors.forSendResult("something-new"), ChatErrors.INTERNAL);
    }
}
