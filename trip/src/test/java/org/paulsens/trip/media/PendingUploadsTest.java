package org.paulsens.trip.media;

import java.time.Instant;
import java.util.regex.Pattern;
import org.testng.Assert;
import org.testng.annotations.Test;

/** The mid-dialog byte registry: ownership, expiry, and the total-bytes eviction budget. */
public class PendingUploadsTest {

    private static final Pattern URL_SAFE = Pattern.compile("[A-Za-z0-9_-]{16,}");

    private static PhotoProcessor.PreviewImage preview(final int bytes) {
        return new PhotoProcessor.PreviewImage(new byte[bytes], 10, 10, 20, 20);
    }

    @Test
    public void putPeekConsumeRoundTrip() {
        final PendingUploads uploads = new PendingUploads();
        final PendingUploads.Pending pending =
                uploads.put(new byte[100], preview(10), "me", "profile", "p1");

        Assert.assertTrue(URL_SAFE.matcher(pending.token()).matches(), pending.token());
        Assert.assertEquals(uploads.peek(pending.token(), "me").orElseThrow().targetId(), "p1");
        Assert.assertTrue(uploads.peek(pending.token(), "someone-else").isEmpty(),
                "Another person's token must not resolve");
        Assert.assertTrue(uploads.peek("no-such-token", "me").isEmpty());
        Assert.assertTrue(uploads.peek(null, "me").isEmpty());
        Assert.assertTrue(uploads.peek(pending.token(), null).isEmpty());

        uploads.consume(pending.token());
        Assert.assertTrue(uploads.peek(pending.token(), "me").isEmpty());
        uploads.consume(pending.token());   // double-consume is a no-op, not an error
        uploads.consume(null);
    }

    @Test
    public void expiredEntriesAreUnclaimableAndDrainedOnPut() {
        final PendingUploads uploads = new PendingUploads();
        final Instant old = Instant.now().minus(PendingUploads.TTL).minusSeconds(5);
        uploads.put(new PendingUploads.Pending("stale", new byte[10], new byte[1],
                10, 10, 20, 20, "me", "profile", "p1", old));

        Assert.assertTrue(uploads.peek("stale", "me").isEmpty(), "Expired must not resolve");
        uploads.put(new byte[10], preview(1), "me", "profile", "p1");
        Assert.assertEquals(uploads.size(), 1, "The expired entry must be drained by the next put");
    }

    @Test
    public void theBudgetEvictsOldestFirst() {
        final PendingUploads uploads = new PendingUploads();
        uploads.maxTotalBytesForTest(250);
        final PendingUploads.Pending first = uploads.put(new byte[100], preview(10), "me", "profile", "p1");
        final PendingUploads.Pending second = uploads.put(new byte[100], preview(10), "me", "profile", "p1");
        Assert.assertTrue(uploads.peek(first.token(), "me").isPresent(), "Both fit in the budget");

        final PendingUploads.Pending third = uploads.put(new byte[100], preview(10), "me", "profile", "p1");
        Assert.assertTrue(uploads.peek(first.token(), "me").isEmpty(), "Oldest must be evicted");
        Assert.assertTrue(uploads.peek(second.token(), "me").isPresent());
        Assert.assertTrue(uploads.peek(third.token(), "me").isPresent());
    }

    @Test
    public void theSharedInstanceIsOneInstance() {
        Assert.assertSame(PendingUploads.getPendingUploads(), PendingUploads.getPendingUploads());
    }
}
