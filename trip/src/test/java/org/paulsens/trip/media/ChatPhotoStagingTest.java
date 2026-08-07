package org.paulsens.trip.media;

import java.time.Instant;
import java.util.List;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ChatPhotoStagingTest {

    private static ChatPhotoStaging.Staged staged(final String key, final Instant at) {
        return new ChatPhotoStaging.Staged("trip1", "person1", key, key + "-small.jpg",
                "image/jpeg", 100, 800, 600, at);
    }

    @Test
    public void peekAnswersOnlyTheStagerForTheStagedTrip() {
        final ChatPhotoStaging staging = new ChatPhotoStaging();
        staging.put(staged("k1", Instant.now()));

        Assert.assertTrue(staging.peek("k1", "trip1", "person1").isPresent());
        Assert.assertTrue(staging.peek("k1", "trip2", "person1").isEmpty(), "wrong trip");
        Assert.assertTrue(staging.peek("k1", "trip1", "person2").isEmpty(), "wrong uploader");
        Assert.assertTrue(staging.peek("nope", "trip1", "person1").isEmpty(), "never staged");
    }

    @Test
    public void anExpiredEntryStopsAnsweringEvenBeforeTheSweep() {
        final ChatPhotoStaging staging = new ChatPhotoStaging();
        staging.put(staged("old", Instant.now().minus(ChatPhotoStaging.TTL).minusSeconds(60)));

        Assert.assertTrue(staging.peek("old", "trip1", "person1").isEmpty(),
                "expiry must not depend on the sweep having run");
    }

    @Test
    public void consumeIsSingleUse() {
        final ChatPhotoStaging staging = new ChatPhotoStaging();
        staging.put(staged("once", Instant.now()));
        staging.consume("once");

        Assert.assertTrue(staging.peek("once", "trip1", "person1").isEmpty());
        Assert.assertEquals(staging.size(), 0);
    }

    @Test
    public void drainExpiredRemovesAndReturnsOnlyTheExpired() {
        final ChatPhotoStaging staging = new ChatPhotoStaging();
        staging.put(staged("fresh", Instant.now()));
        staging.put(staged("stale", Instant.now().minus(ChatPhotoStaging.TTL).minusSeconds(60)));

        final List<ChatPhotoStaging.Staged> drained = staging.drainExpired();

        Assert.assertEquals(drained.size(), 1);
        Assert.assertEquals(drained.get(0).key(), "stale");
        Assert.assertEquals(staging.size(), 1, "the fresh entry stays");
        Assert.assertTrue(staging.drainExpired().isEmpty(), "a drained entry drains exactly once");
    }
}
