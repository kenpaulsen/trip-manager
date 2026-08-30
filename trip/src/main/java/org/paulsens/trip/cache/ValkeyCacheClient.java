package org.paulsens.trip.cache;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.SetArgs;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.KeyValue;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import org.paulsens.trip.util.CacheLane;
import org.paulsens.trip.util.TripThreads;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Lettuce-backed {@link CacheClient} for ElastiCache Serverless Valkey ({@code rediss://}, cluster protocol) or a
 * local single-node Valkey ({@code redis://}, standalone) -- see {@link CacheConfig}.
 *
 * <p>Two invariants of the {@link CacheClient} contract are enforced here:
 * every operation blocks its calling (virtual) thread on the Lettuce future via an interruptible await --
 * no caller code ever runs on a Lettuce/Netty event loop -- and cache errors never propagate: reads resolve
 * to a miss and writes to {@code false}, so the data layer always falls back to DynamoDB when the cache is
 * unavailable.</p>
 */
@Slf4j
public final class ValkeyCacheClient implements CacheClient {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(3);
    private static final long ADMIN_TIMEOUT_SECONDS = 30;
    private static final int SCAN_BATCH = 500;
    /**
     * Finite request queue (Lettuce's default is effectively unbounded). During the 2026-08-04 incident,
     * thousands of commands piled up behind a slow cache, each burning its full 3s timeout and holding
     * Netty buffer memory -- shed load fast instead: overflow fails the future immediately and
     * {@code guard()} maps it to the usual fallback (miss / false), which DynamoDB absorbs.
     */
    private static final int REQUEST_QUEUE_SIZE = 5_000;
    /**
     * The background lane's much smaller queue: spawned work (soft revalidates, health checks, index
     * rebuilds) tolerates shed commands everywhere, so it should shed EARLY rather than compete with
     * request-path reads for queue slots. During the 2026-08-18 incident background probes filled the one
     * shared queue and every foreground command was rejected; two queues make that starvation structurally
     * impossible. Queue size is a client-level option, hence the second client below.
     */
    private static final int BACKGROUND_QUEUE_SIZE = 500;
    /**
     * Backstop for the blocking await, over and above Lettuce's own {@link #COMMAND_TIMEOUT}: the client
     * fails the future at 3s, so this only fires if a completion is lost outright. It must exist -- a
     * virtual thread parked forever on a lost future is a leaked request.
     */
    private static final long AWAIT_TIMEOUT_SECONDS = COMMAND_TIMEOUT.toSeconds() + 2;

    private final AutoCloseable fgClient;
    private final AutoCloseable bgClient;
    private final AutoCloseable fgConnection;
    private final AutoCloseable bgConnection;
    private final RedisClusterAsyncCommands<String, String> fgCommands;
    private final RedisClusterAsyncCommands<String, String> bgCommands;
    /**
     * Opens a pub/sub connection on demand. Separate from the command connections because a subscribed
     * connection cannot serve ordinary commands -- issuing a GET on it is a protocol error -- so they cannot
     * be shared. Lazy so a deployment that never subscribes never opens one.
     */
    private final Supplier<StatefulRedisPubSubConnection<String, String>> pubSubFactory;

    public ValkeyCacheClient(final CacheConfig config) {
        final RedisURI uri = RedisURI.create(config.getValkeyUri());
        if (config.isCluster()) {
            final RedisClusterClient fg = clusterClient(uri, REQUEST_QUEUE_SIZE);
            final RedisClusterClient bg = clusterClient(uri, BACKGROUND_QUEUE_SIZE);
            final StatefulRedisClusterConnection<String, String> fgConn = fg.connect();
            final StatefulRedisClusterConnection<String, String> bgConn = bg.connect();
            this.fgClient = fg;
            this.bgClient = bg;
            this.fgConnection = fgConn;
            this.bgConnection = bgConn;
            this.fgCommands = fgConn.async();
            this.bgCommands = bgConn.async();
            this.pubSubFactory = fg::connectPubSub;
        } else {
            final RedisClient fg = standaloneClient(uri, REQUEST_QUEUE_SIZE);
            final RedisClient bg = standaloneClient(uri, BACKGROUND_QUEUE_SIZE);
            final StatefulRedisConnection<String, String> fgConn = fg.connect();
            final StatefulRedisConnection<String, String> bgConn = bg.connect();
            this.fgClient = fg;
            this.bgClient = bg;
            this.fgConnection = fgConn;
            this.bgConnection = bgConn;
            this.fgCommands = fgConn.async();
            this.bgCommands = bgConn.async();
            this.pubSubFactory = fg::connectPubSub;
        }
        // Startup connectivity check -- log only, the app must come up even if the cache is down.
        final String mode = config.isCluster() ? "cluster" : "standalone";
        final String uriLabel = config.getValkeyUri();
        fgCommands.ping()
                .thenAccept(pong -> logConnected(mode, uriLabel))
                .exceptionally(ex -> logNotReachable(uriLabel, ex));
    }

    private static RedisClusterClient clusterClient(final RedisURI uri, final int queueSize) {
        final RedisClusterClient client = RedisClusterClient.create(uri);
        client.setOptions(ClusterClientOptions.builder()
                .timeoutOptions(TimeoutOptions.enabled(COMMAND_TIMEOUT))
                .requestQueueSize(queueSize)
                .topologyRefreshOptions(ClusterTopologyRefreshOptions.builder()
                        .enablePeriodicRefresh(Duration.ofMinutes(15))
                        .enableAllAdaptiveRefreshTriggers()
                        .build())
                .build());
        return client;
    }

    private static RedisClient standaloneClient(final RedisURI uri, final int queueSize) {
        final RedisClient client = RedisClient.create(uri);
        client.setOptions(ClientOptions.builder()
                .timeoutOptions(TimeoutOptions.enabled(COMMAND_TIMEOUT))
                .requestQueueSize(queueSize)
                .build());
        return client;
    }

    /**
     * Routes by lane: background-spawned work ({@link org.paulsens.trip.util.TripThreads} binds the lane)
     * gets the small-queue connection so a background flood sheds early instead of starving request-path
     * reads. Unbound threads -- request threads and their StructuredTaskScope forks -- stay foreground.
     */
    private RedisClusterAsyncCommands<String, String> commands() {
        return CacheLane.isBackground() ? bgCommands : fgCommands;
    }

    /** Test seam: whether a command issued right now would ride the background connection. */
    boolean routesToBackground() {
        return commands() == bgCommands;
    }

    private static void logConnected(final String mode, final String uriLabel) {
        log.info("Valkey cache connected ({} mode): {}", mode, uriLabel);
    }

    private static Void logNotReachable(final String uriLabel, final Throwable ex) {
        log.error("Valkey cache NOT reachable at {}: {}", uriLabel, ex.toString());
        return null;
    }

    @Override
    public Optional<String> getValue(final String key) {
        return await("GET " + key, commands().get(key), Optional::ofNullable, Optional.empty());
    }

    @Override
    public boolean putValue(final String key, final String value, final Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return await("SET " + key, commands().set(key, value), "OK"::equals, false);
        }
        return await("SETEX " + key, commands().setex(key, ttl.toSeconds(), value), "OK"::equals, false);
    }

    @Override
    public boolean removeKey(final String key) {
        return await("UNLINK " + key, commands().unlink(key), ignored -> true, false);
    }

    @Override
    public Map<String, String> getHash(final String key) {
        return await("HGETALL " + key, commands().hgetall(key), Function.identity(), Map.of());
    }

    @Override
    public Map<String, String> getHashFields(final String key, final Collection<String> fields) {
        return await("HMGET " + key, commands().hmget(key, fields.toArray(String[]::new)),
                ValkeyCacheClient::toHashFieldMap, Map.of());
    }

    private static Map<String, String> toHashFieldMap(final List<KeyValue<String, String>> keyValues) {
        final Map<String, String> result = new HashMap<>();
        for (final KeyValue<String, String> kv : keyValues) {
            if (kv.hasValue()) {
                result.put(kv.getKey(), kv.getValue());
            }
        }
        return result;
    }

    @Override
    public boolean putHashField(final String key, final String field, final String value) {
        return await("HSET " + key, commands().hset(key, field, value), ignored -> true, false);
    }

    @Override
    public boolean putHashFields(final String key, final Map<String, String> fields) {
        return await("HSET(multi) " + key, commands().hset(key, fields), ignored -> true, false);
    }

    @Override
    public boolean removeHashField(final String key, final String field) {
        return await("HDEL " + key, commands().hdel(key, field), ignored -> true, false);
    }

    @Override
    public boolean addSetMembers(final String key, final Collection<String> members) {
        return await("SADD " + key, commands().sadd(key, members.toArray(String[]::new)), ignored -> true, false);
    }

    @Override
    public boolean addSortedSetEntries(final String key, final Collection<String> entries) {
        if (entries.isEmpty()) {
            return true;
        }
        final ScoredValue<String>[] scored = entries.stream()
                .map(entry -> ScoredValue.just(0.0d, entry))
                .toArray(ScoredValue[]::new);
        return await("ZADD " + key, commands().zadd(key, scored), ignored -> true, false);
    }

    @Override
    public boolean removeSortedSetEntries(final String key, final Collection<String> entries) {
        if (entries.isEmpty()) {
            return true;
        }
        return await("ZREM " + key, commands().zrem(key, entries.toArray(String[]::new)), ignored -> true, false);
    }

    @Override
    public List<String> getSortedSetByPrefix(final String key, final String prefix, final int limit) {
        final Range<String> range = Range.from(
                Range.Boundary.including(prefix), Range.Boundary.including(prefix + Character.MAX_VALUE));
        final Limit lim = (limit > 0) ? Limit.create(0, limit) : Limit.unlimited();
        return await("ZRANGEBYLEX " + key, commands().zrangebylex(key, range, lim), Function.identity(), List.of());
    }

    @Override
    public boolean addScoredEntries(final String key, final Map<String, Double> memberScores) {
        if (memberScores.isEmpty()) {
            return true;
        }
        final ScoredValue<String>[] scored = memberScores.entrySet().stream()
                .map(e -> ScoredValue.just(e.getValue(), e.getKey()))
                .toArray(ScoredValue[]::new);
        return await("ZADD(scored) " + key, commands().zadd(key, scored), ignored -> true, false);
    }

    @Override
    public List<String> getRangeByScore(
            final String key, final double minScore, final double maxScore, final boolean reverse, final int limit) {
        final Range<Double> range = Range.from(scoreBoundary(minScore, true), scoreBoundary(maxScore, false));
        final Limit lim = (limit > 0) ? Limit.create(0, limit) : Limit.unlimited();
        final RedisFuture<List<String>> future = reverse
                ? commands().zrevrangebyscore(key, range, lim)
                : commands().zrangebyscore(key, range, lim);
        return await((reverse ? "ZREVRANGEBYSCORE " : "ZRANGEBYSCORE ") + key, future, Function.identity(), List.of());
    }

    private static Range.Boundary<Double> scoreBoundary(final double score, final boolean lower) {
        if ((lower && score == Double.NEGATIVE_INFINITY) || (!lower && score == Double.POSITIVE_INFINITY)) {
            return Range.Boundary.unbounded();
        }
        return Range.Boundary.including(score);
    }

    @Override
    public boolean removeSetMember(final String key, final String member) {
        return await("SREM " + key, commands().srem(key, member), ignored -> true, false);
    }

    @Override
    public Set<String> getSetMembers(final String key) {
        return await("SMEMBERS " + key, commands().smembers(key), Function.identity(), Set.of());
    }

    @Override
    public boolean expire(final String key, final Duration ttl) {
        return await("EXPIRE " + key, commands().expire(key, ttl.toSeconds()), ignored -> true, false);
    }

    @Override
    public Optional<Long> increment(final String key, final long delta, final Duration ttl) {
        return await("INCRBY " + key, commands().incrby(key, delta),
                value -> countedWithTtl(key, delta, ttl, value),
                Optional.empty());
    }

    /**
     * Applies the TTL only when the key was <em>just</em> created ({@code value == delta}), so a rolling window
     * does not keep pushing its own expiry out on every hit.
     */
    private Optional<Long> countedWithTtl(
            final String key, final long delta, final Duration ttl, final Long value) {
        if (ttl != null && !ttl.isZero() && !ttl.isNegative() && value != null && value == delta) {
            // Fire and forget, but observed: an unhandled failure here must not surface as an exception on the
            // caller's future -- the counter itself already succeeded, and a missing TTL costs memory hygiene,
            // not correctness.
            commands().expire(key, ttl.toSeconds()).exceptionally(ex -> logTtlFailure(key, ex));
        }
        return Optional.ofNullable(value);
    }

    private Boolean logTtlFailure(final String key, final Throwable ex) {
        log.debug("Unable to set TTL on counter {}", key, ex);
        return null;
    }

    @Override
    public boolean trimSortedSet(final String key, final int maxSize) {
        if (maxSize <= 0) {
            return true;
        }
        // Keep the highest-ranked (newest by score) maxSize members; drop the oldest.
        return await("ZREMRANGEBYRANK " + key,
                commands().zremrangebyrank(key, 0, -(maxSize + 1L)),
                ignored -> true,
                false);
    }

    @Override
    public boolean tryAcquireLock(final String key, final Duration ttl) {
        final long seconds = Math.max(1L, ttl == null ? 5L : ttl.toSeconds());
        return await("SET NX EX " + key,
                commands().set(key, "1", SetArgs.Builder.nx().ex(seconds)),
                "OK"::equals,
                false);
    }

    @Override
    public boolean releaseLock(final String key) {
        return removeKey(key);
    }

    @Override
    public boolean clearNamespace(final String prefix) {
        // Admin/test path only: SCAN (never KEYS -- restricted on ElastiCache Serverless) + UNLINK, blocking
        // the calling thread for simplicity.
        return scanAndUnlink(prefix);
    }

    private boolean scanAndUnlink(final String prefix) {
        try {
            final ScanArgs args = ScanArgs.Builder.matches(prefix + "*").limit(SCAN_BATCH);
            KeyScanCursor<String> cursor = commands().scan(args).get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            while (true) {
                if (!cursor.getKeys().isEmpty()) {
                    commands().unlink(cursor.getKeys().toArray(String[]::new))
                            .get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                }
                if (cursor.isFinished()) {
                    return true;
                }
                cursor = commands().scan(cursor, args).get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } catch (final InterruptedException ex) {
            // Same contract as await(): interruption must propagate, so re-assert the flag for the caller.
            Thread.currentThread().interrupt();
            log.warn("Valkey clearNamespace('{}') interrupted; the namespace may be partially cleared", prefix);
            return false;
        } catch (final Exception ex) {
            log.error("Valkey clearNamespace('{}') failed", prefix, ex);
            return false;
        }
    }

    @Override
    public boolean publish(final String channel, final String payload) {
        // Regular PUBLISH, deliberately NOT sharded SPUBLISH. Sharded hashes the channel to a slot and delivers
        // only to the shard owning it, so a subscriber attached elsewhere silently misses events -- and
        // ElastiCache Serverless rebalances shards on its own, invisibly, which turns that into an intermittent
        // message-loss bug nobody can reproduce. Regular PUBLISH broadcasts to every node, so cross-shard
        // delivery is correct by construction with no dependency on routing or topology refresh. The saving from
        // sharded fan-out is unmeasurable at one small nudge per message.
        return await("PUBLISH " + channel, commands().publish(channel, payload), received -> true, false);
    }

    @Override
    public AutoCloseable subscribe(
            final Collection<String> channels, final BiConsumer<String, String> onMessage) {
        if (channels == null || channels.isEmpty() || onMessage == null) {
            return () -> { };
        }
        final String[] names = channels.toArray(String[]::new);
        try {
            final StatefulRedisPubSubConnection<String, String> pubSub = pubSubFactory.get();
            pubSub.addListener(new HandOffListener(onMessage));
            pubSub.async().subscribe(names);
            log.info("Valkey pub/sub subscribed to {} channel(s)", names.length);
            return pubSub::close;
        } catch (final RuntimeException ex) {
            // Same contract as every other operation: a cache problem degrades the feature, never fails a request.
            // Without a nudge the long-poll simply rides its timeout and the client falls back to plain polling.
            log.warn("Unable to subscribe to Valkey pub/sub; real-time delivery is degraded", ex);
            return () -> { };
        }
    }

    /**
     * Lettuce invokes listeners <b>on a Netty event loop</b>. The {@link CacheClient} contract forbids running
     * caller code there, and the reason is concrete rather than stylistic: the callback will do a cursor read,
     * every DAO read blocks on a Lettuce future, and blocking on the event loop that must complete it
     * deadlocks that loop. So this listener does nothing except hand off to a fresh virtual thread.
     */
    private static final class HandOffListener extends RedisPubSubAdapter<String, String> {
        private final BiConsumer<String, String> onMessage;

        private HandOffListener(final BiConsumer<String, String> onMessage) {
            this.onMessage = onMessage;
        }

        @Override
        public void message(final String channel, final String payload) {
            TripThreads.start(() -> deliver(channel, payload));
        }

        private void deliver(final String channel, final String payload) {
            try {
                onMessage.accept(channel, payload);
            } catch (final RuntimeException ex) {
                log.warn("Chat nudge listener failed for channel {}", channel, ex);
            }
        }
    }

    @Override
    public void close() {
        closeQuietly(fgConnection);
        closeQuietly(bgConnection);
        closeQuietly(fgClient);
        closeQuietly(bgClient);
    }

    private static void closeQuietly(final AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (final Exception ex) {
            log.warn("Error closing Valkey client resource", ex);
        }
    }

    /**
     * Blocks the calling (virtual) thread on the Lettuce future -- the ONE place a CompletableFuture is
     * consumed since the virtual-threads port. {@code get}, never {@code join}: join is uninterruptible,
     * and interruption must propagate so structured-concurrency cancellation and servlet aborts can reclaim
     * a parked thread (the future is cancelled and the interrupt flag restored). Every failure maps to the
     * caller-supplied fallback -- cache trouble degrades the feature, never the request.
     */
    private static <T, R> R await(final String op, final RedisFuture<T> future,
            final Function<T, R> mapper, final R fallback) {
        final T value;
        try {
            value = future.get(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (final InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            log.debug("Valkey {} interrupted; command cancelled", op);
            return fallback;
        } catch (final ExecutionException ex) {
            log.error("Valkey {} failed: {}", op, ex.getCause() == null ? ex.toString() : ex.getCause().toString());
            return fallback;
        } catch (final TimeoutException ex) {
            future.cancel(true);
            log.error("Valkey {} failed: no completion within {}s (command timeout is {}s)",
                    op, AWAIT_TIMEOUT_SECONDS, COMMAND_TIMEOUT.toSeconds());
            return fallback;
        }
        try {
            return mapper.apply(value);
        } catch (final RuntimeException rex) {
            log.error("Valkey {} result mapping failed", op, rex);
            return fallback;
        }
    }
}
