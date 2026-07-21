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
import io.lettuce.core.KeyValue;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;

/**
 * Lettuce-backed {@link CacheClient} for ElastiCache Serverless Valkey ({@code rediss://}, cluster protocol) or a
 * local single-node Valkey ({@code redis://}, standalone) -- see {@link CacheConfig}.
 *
 * <p>Two invariants of the {@link CacheClient} contract are enforced here:
 * every returned future completes on {@link PersistenceExecutors} (never on a Lettuce/Netty event loop, which
 * callers would deadlock by joining), and cache errors never propagate -- reads resolve to a miss and writes to
 * {@code false}, so the data layer always falls back to DynamoDB when the cache is unavailable.</p>
 */
@Slf4j
public final class ValkeyCacheClient implements CacheClient {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(3);
    private static final long ADMIN_TIMEOUT_SECONDS = 30;
    private static final int SCAN_BATCH = 500;

    private final AutoCloseable client;
    private final AutoCloseable connection;
    private final RedisClusterAsyncCommands<String, String> commands;

    public ValkeyCacheClient(final CacheConfig config) {
        final RedisURI uri = RedisURI.create(config.getValkeyUri());
        if (config.isCluster()) {
            final RedisClusterClient clusterClient = RedisClusterClient.create(uri);
            clusterClient.setOptions(ClusterClientOptions.builder()
                    .timeoutOptions(TimeoutOptions.enabled(COMMAND_TIMEOUT))
                    .topologyRefreshOptions(ClusterTopologyRefreshOptions.builder()
                            .enablePeriodicRefresh(Duration.ofMinutes(15))
                            .enableAllAdaptiveRefreshTriggers()
                            .build())
                    .build());
            final StatefulRedisClusterConnection<String, String> conn = clusterClient.connect();
            this.client = clusterClient;
            this.connection = conn;
            this.commands = conn.async();
        } else {
            final RedisClient redisClient = RedisClient.create(uri);
            redisClient.setOptions(ClientOptions.builder()
                    .timeoutOptions(TimeoutOptions.enabled(COMMAND_TIMEOUT))
                    .build());
            final StatefulRedisConnection<String, String> conn = redisClient.connect();
            this.client = redisClient;
            this.connection = conn;
            this.commands = conn.async();
        }
        // Startup connectivity check -- log only, the app must come up even if the cache is down.
        final String mode = config.isCluster() ? "cluster" : "standalone";
        final String uriLabel = config.getValkeyUri();
        commands.ping()
                .thenAccept(pong -> logConnected(mode, uriLabel))
                .exceptionally(ex -> logNotReachable(uriLabel, ex));
    }

    private static void logConnected(final String mode, final String uriLabel) {
        log.info("Valkey cache connected ({} mode): {}", mode, uriLabel);
    }

    private static Void logNotReachable(final String uriLabel, final Throwable ex) {
        log.error("Valkey cache NOT reachable at {}: {}", uriLabel, ex.toString());
        return null;
    }

    @Override
    public CompletableFuture<Optional<String>> getValue(final String key) {
        return guard("GET " + key, commands.get(key), Optional::ofNullable, Optional.empty());
    }

    @Override
    public CompletableFuture<Boolean> putValue(final String key, final String value, final Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return guard("SET " + key, commands.set(key, value), "OK"::equals, false);
        }
        return guard("SETEX " + key, commands.setex(key, ttl.toSeconds(), value), "OK"::equals, false);
    }

    @Override
    public CompletableFuture<Boolean> removeKey(final String key) {
        return guard("UNLINK " + key, commands.unlink(key), ignored -> true, false);
    }

    @Override
    public CompletableFuture<Map<String, String>> getHash(final String key) {
        return guard("HGETALL " + key, commands.hgetall(key), Function.identity(), Map.of());
    }

    @Override
    public CompletableFuture<Map<String, String>> getHashFields(final String key, final Collection<String> fields) {
        return guard("HMGET " + key, commands.hmget(key, fields.toArray(String[]::new)),
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
    public CompletableFuture<Boolean> putHashField(final String key, final String field, final String value) {
        return guard("HSET " + key, commands.hset(key, field, value), ignored -> true, false);
    }

    @Override
    public CompletableFuture<Boolean> putHashFields(final String key, final Map<String, String> fields) {
        return guard("HSET(multi) " + key, commands.hset(key, fields), ignored -> true, false);
    }

    @Override
    public CompletableFuture<Boolean> removeHashField(final String key, final String field) {
        return guard("HDEL " + key, commands.hdel(key, field), ignored -> true, false);
    }

    @Override
    public CompletableFuture<Boolean> addSetMembers(final String key, final Collection<String> members) {
        return guard("SADD " + key, commands.sadd(key, members.toArray(String[]::new)), ignored -> true, false);
    }

    @Override
    public CompletableFuture<Boolean> addSortedSetEntries(final String key, final Collection<String> entries) {
        if (entries.isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }
        final ScoredValue<String>[] scored = entries.stream()
                .map(entry -> ScoredValue.just(0.0d, entry))
                .toArray(ScoredValue[]::new);
        return guard("ZADD " + key, commands.zadd(key, scored), ignored -> true, false);
    }

    @Override
    public CompletableFuture<Boolean> removeSortedSetEntries(final String key, final Collection<String> entries) {
        if (entries.isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }
        return guard("ZREM " + key, commands.zrem(key, entries.toArray(String[]::new)), ignored -> true, false);
    }

    @Override
    public CompletableFuture<List<String>> getSortedSetByPrefix(final String key, final String prefix, final int limit) {
        final Range<String> range = Range.from(
                Range.Boundary.including(prefix), Range.Boundary.including(prefix + Character.MAX_VALUE));
        return guard("ZRANGEBYLEX " + key, commands.zrangebylex(key, range, Limit.create(0, limit)),
                Function.identity(), List.of());
    }

    @Override
    public CompletableFuture<Boolean> removeSetMember(final String key, final String member) {
        return guard("SREM " + key, commands.srem(key, member), ignored -> true, false);
    }

    @Override
    public CompletableFuture<Set<String>> getSetMembers(final String key) {
        return guard("SMEMBERS " + key, commands.smembers(key), Function.identity(), Set.of());
    }

    @Override
    public CompletableFuture<Boolean> expire(final String key, final Duration ttl) {
        return guard("EXPIRE " + key, commands.expire(key, ttl.toSeconds()), ignored -> true, false);
    }

    @Override
    public CompletableFuture<Boolean> tryAcquireLock(final String key, final Duration ttl) {
        final long seconds = Math.max(1L, ttl == null ? 5L : ttl.toSeconds());
        return guard("SET NX EX " + key,
                commands.set(key, "1", SetArgs.Builder.nx().ex(seconds)),
                "OK"::equals,
                false);
    }

    @Override
    public CompletableFuture<Boolean> releaseLock(final String key) {
        return removeKey(key);
    }

    @Override
    public CompletableFuture<Boolean> clearNamespace(final String prefix) {
        // Admin/test path only: SCAN (never KEYS -- restricted on ElastiCache Serverless) + UNLINK, blocking a
        // worker thread for simplicity.
        return CompletableFuture.supplyAsync(() -> scanAndUnlink(prefix), PersistenceExecutors.pool());
    }

    private boolean scanAndUnlink(final String prefix) {
        try {
            final ScanArgs args = ScanArgs.Builder.matches(prefix + "*").limit(SCAN_BATCH);
            KeyScanCursor<String> cursor = commands.scan(args).get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            while (true) {
                if (!cursor.getKeys().isEmpty()) {
                    commands.unlink(cursor.getKeys().toArray(String[]::new))
                            .get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                }
                if (cursor.isFinished()) {
                    return true;
                }
                cursor = commands.scan(cursor, args).get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } catch (final Exception ex) {
            log.error("Valkey clearNamespace('{}') failed", prefix, ex);
            return false;
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
            client.close();
        } catch (final Exception ex) {
            log.warn("Error closing Valkey client", ex);
        }
    }

    private <T, R> CompletableFuture<R> guard(final String op, final RedisFuture<T> future,
            final Function<T, R> mapper, final R fallback) {
        return future.handleAsync(
                (value, ex) -> mapGuardResult(op, value, ex, mapper, fallback),
                PersistenceExecutors.pool()).toCompletableFuture();
    }

    private static <T, R> R mapGuardResult(
            final String op,
            final T value,
            final Throwable ex,
            final Function<T, R> mapper,
            final R fallback) {
        if (ex != null) {
            log.error("Valkey {} failed: {}", op, ex.toString());
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
