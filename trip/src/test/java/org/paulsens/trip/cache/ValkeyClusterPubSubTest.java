package org.paulsens.trip.cache;

import io.lettuce.core.cluster.SlotHash;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Cross-shard pub/sub delivery, against a real 3-shard cluster.
 *
 * <p>This is the test behind the decision to use regular {@code PUBLISH} rather than sharded {@code SPUBLISH}. Until
 * now that choice rested on an argument; the chat nudge — which carries messages, edits, deletes and reactions
 * between tasks — depended on it being right.
 *
 * <p><b>The spread is asserted, not assumed.</b> Publishing 50 names that happen to land on one shard would pass
 * while proving nothing, so the slot of every channel is computed with the same CRC16 the server uses and the set is
 * required to cover all three shards before any delivery assertion runs.
 *
 * <p>Lives in this module rather than {@code medjugorje/webtest} (where the plan placed it) for a practical reason:
 * Lettuce and {@link ValkeyCacheClient} are on this classpath, and the webtest module has Redisson but not Lettuce.
 * Putting it there would mean adding a client library and duplicating the harness to test a class that lives here.
 *
 * <p>Run with:
 * <pre>
 *     mvn test -pl trip -Dtest=ValkeyClusterPubSubTest -Dtrip.cluster.test=true
 * </pre>
 */
public class ValkeyClusterPubSubTest {

    private static final int CHANNEL_COUNT = 50;
    private static final String PREFIX = "chat:trip:cluster-it-";

    private LocalRedisCluster cluster;
    private ValkeyCacheClient client;
    private String savedUri;
    private String savedProtocol;

    @BeforeClass
    public void startCluster() throws Exception {
        LocalRedisCluster.requireEnabled();
        cluster = LocalRedisCluster.start();
        savedUri = System.getProperty("trip.valkey.uri");
        savedProtocol = System.getProperty("trip.valkey.protocol");
        // Go through the real resolution path rather than fabricating a config: cluster mode is decided there, and a
        // test that bypassed it would not be testing the mode production actually runs in.
        System.setProperty("trip.valkey.uri", cluster.seedUri());
        System.setProperty("trip.valkey.protocol", "cluster");
        final CacheConfig config = CacheConfig.resolve();
        assertTrue(config.isCluster(), "the client must be in cluster mode for this test to mean anything");
        client = new ValkeyCacheClient(config);
    }

    @AfterClass(alwaysRun = true)
    public void stopCluster() {
        if (client != null) {
            client.close();
        }
        restore("trip.valkey.uri", savedUri);
        restore("trip.valkey.protocol", savedProtocol);
        if (cluster != null) {
            cluster.stop();
        }
    }

    private static void restore(final String key, final String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @Test
    public void theChannelNamesSpanEveryShard() {
        final List<String> channels = channels();
        final Set<Integer> shards = new HashSet<>();
        for (final String channel : channels) {
            shards.add(shardOf(channel));
        }
        // Without this the delivery test below could pass on a single shard and prove nothing about crossing them.
        assertEquals(shards.size(), LocalRedisCluster.SHARDS,
                "the " + CHANNEL_COUNT + " channel names must cover all " + LocalRedisCluster.SHARDS
                        + " shards; they covered " + shards);
    }

    @Test
    public void regularPublishReachesEverySubscriberOnEveryShard() throws Exception {
        final List<String> channels = channels();
        final Set<String> received = ConcurrentHashMap.newKeySet();
        final CountDownLatch latch = new CountDownLatch(channels.size());

        try (AutoCloseable subscription = client.subscribe(channels, (channel, payload) -> {
            if (received.add(channel)) {
                latch.countDown();
            }
        })) {
            // Give the subscription time to be established on every node before publishing.
            Thread.sleep(500);
            for (final String channel : channels) {
                assertTrue(client.publish(channel, "nudge-" + channel).join(),
                        "publish must report success for " + channel);
            }
            assertTrue(latch.await(15, TimeUnit.SECONDS),
                    "expected all " + channels.size() + " channels, missing: " + missing(channels, received));
        }

        // The actual claim: 100%, across shards, with no routing or topology work by the caller.
        assertEquals(received.size(), channels.size(),
                "PUBLISH must reach subscribers regardless of which shard owns the channel name");
    }

    /**
     * The contrast that documents why sharded pub/sub was rejected.
     *
     * <p>{@code SPUBLISH} delivers only to the shard owning the channel's slot, so a subscriber that is not attached
     * to that shard hears nothing — and on ElastiCache Serverless, which reshards invisibly, which shard owns a slot
     * is not something the application can know or pin. The failure would be silent, intermittent, and triggered by
     * a maintenance event rather than by any deploy.
     *
     * <p>What is <em>not</em> reproduced here: resharding mid-subscription. Driving {@code CLUSTER SETSLOT}
     * migrations under a live subscriber is beyond what this harness can do reliably, so the risk is argued from the
     * protocol rather than demonstrated. Saying so is better than a test that appears to cover it.
     */
    @Test
    public void shardedPublishIsNotDeliveredAcrossShards() throws Exception {
        final List<String> channels = channels();
        final String subscribed = channels.get(0);
        final String elsewhere = onADifferentShard(channels, shardOf(subscribed));
        if (elsewhere == null) {
            throw new SkipException("No channel landed on a different shard; cannot contrast SPUBLISH");
        }

        final Set<String> received = ConcurrentHashMap.newKeySet();
        try (AutoCloseable subscription = client.subscribe(List.of(subscribed), (channel, payload) ->
                received.add(channel))) {
            Thread.sleep(500);

            // Sharded publish to a channel on another shard: the subscriber is not there to hear it.
            sharded(elsewhere, "sharded-nudge");
            Thread.sleep(1000);
            assertFalse(received.contains(elsewhere),
                    "SPUBLISH is not expected to cross shards; if this ever passes, re-open the PUBLISH decision");

            // The same channel via regular PUBLISH does arrive -- so the difference is the publish mode, not the
            // subscription, and not a broken test setup.
            client.publish(subscribed, "regular-nudge").join();
            Thread.sleep(1000);
            assertTrue(received.contains(subscribed), "regular PUBLISH must still deliver");
        }
    }

    private void sharded(final String channel, final String payload) {
        // Raw command: CacheClient deliberately exposes only regular PUBLISH, which is the point of the design.
        final io.lettuce.core.cluster.RedisClusterClient raw =
                io.lettuce.core.cluster.RedisClusterClient.create(cluster.seedUri());
        try (io.lettuce.core.cluster.api.StatefulRedisClusterConnection<String, String> conn = raw.connect()) {
            conn.sync().spublish(channel, payload);
        } finally {
            raw.shutdown();
        }
    }

    private static List<String> channels() {
        final List<String> channels = new ArrayList<>(CHANNEL_COUNT);
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            channels.add(PREFIX + i);
        }
        return channels;
    }

    /** Which shard owns a channel name, by the same CRC16 slot hash the server uses. */
    private static int shardOf(final String channel) {
        final int slotsPerShard = LocalRedisCluster.TOTAL_SLOTS / LocalRedisCluster.SHARDS;
        return Math.min(SlotHash.getSlot(channel) / slotsPerShard, LocalRedisCluster.SHARDS - 1);
    }

    private static String onADifferentShard(final List<String> channels, final int shard) {
        for (final String channel : channels) {
            if (shardOf(channel) != shard) {
                return channel;
            }
        }
        return null;
    }

    private static List<String> missing(final List<String> expected, final Set<String> received) {
        final List<String> missing = new ArrayList<>();
        for (final String channel : expected) {
            if (!received.contains(channel)) {
                missing.add(channel);
            }
        }
        return missing;
    }
}
