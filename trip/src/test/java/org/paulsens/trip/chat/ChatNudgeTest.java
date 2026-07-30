package org.paulsens.trip.chat;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Decoding must be total. A nudge arrives on a pub/sub callback where a thrown exception has nowhere useful to go,
 * and during a blue/green deploy two builds share the channel — so an unrecognised payload has to degrade to
 * "wake up and read your cursor" rather than fail.
 */
public class ChatNudgeTest {

    @Test
    public void encodeThenDecodeRoundTrips() {
        final String payload = ChatNudge.encode("trip:abc", 1769558400123L);
        Assert.assertEquals(payload, "{\"c\":\"trip:abc\",\"upTo\":1769558400123}");
        Assert.assertEquals(ChatNudge.upTo(payload), 1769558400123L);
    }

    @Test
    public void unparseablePayloadsDecodeToZeroRatherThanThrowing() {
        // Zero is safe: the reader falls back to its own cursor, which is the authority regardless.
        Assert.assertEquals(ChatNudge.upTo(null), 0L);
        Assert.assertEquals(ChatNudge.upTo(""), 0L);
        Assert.assertEquals(ChatNudge.upTo("not json at all"), 0L);
        Assert.assertEquals(ChatNudge.upTo("{\"c\":\"trip:abc\"}"), 0L);
        Assert.assertEquals(ChatNudge.upTo("{\"upTo\":}"), 0L);
        Assert.assertEquals(ChatNudge.upTo("{\"upTo\":\"abc\"}"), 0L);
        Assert.assertEquals(ChatNudge.upTo("{\"upTo\":99999999999999999999}"), 0L, "overflow must not throw");
    }

    @Test
    public void toleratesShapeChangesAFutureBuildMightMake() {
        // Extra fields, reordering and whitespace are all things a later version could introduce.
        Assert.assertEquals(ChatNudge.upTo("{\"v\":2,\"c\":\"trip:abc\",\"upTo\": 42,\"extra\":true}"), 42L);
        Assert.assertEquals(ChatNudge.upTo("{\"upTo\":7,\"c\":\"trip:abc\"}"), 7L);
    }

    @Test
    public void zeroIsAValidWatermark() {
        Assert.assertEquals(ChatNudge.upTo(ChatNudge.encode("trip:abc", 0L)), 0L);
    }
}
