package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/**
 * Point-read template (trips, trip events, people, families, chat channels). The cached string is an
 * envelope {@code "<epochMillis>|<json>"} carrying its own loaded-at stamp, so staleness is decided inline
 * from the value already in hand: a fresh hit costs zero extra cache commands and spawns nothing. Only a
 * stale hit proceeds into {@link Revalidator}'s gates (per-key dedup, the global {@link RefreshPermits}
 * cap), which run BEFORE any thread is spawned. The 2026-08-18 incident was the previous design's per-hit
 * probe of a sibling {@code :at} key: the probe sat in front of the gates, so probe volume scaled with hit
 * rate and overran the shared Valkey connection once the near-cache made hits heap-speed.
 *
 * <p>A value without an envelope prefix is a legacy entry: served as-is and treated as stale-once, so the
 * background refresh rewrites it enveloped -- no flush, no format-version bump. This relies on serializers
 * emitting JSON: entity JSON starts with <code>'{'</code>, so a real value can never be mistaken for an
 * envelope prefix.</p>
 *
 * <p>Blocking since the virtual-threads port. Background reload with null removes the entry (out-of-band
 * deletes heal). Soft revalidate is read-triggered. {@link CacheKeys#GC_TTL} is hygiene only. Duplicate
 * refreshes are safe by design.</p>
 *
 * @param <V> The entity type.
 */
@Slf4j
@Builder
public class PointCache<V> {
    private final CacheClient cache;
    private final String keyPrefix;
    @Builder.Default
    private final Duration softTtl = CacheKeys.SOFT_TTL;
    @Builder.Default
    private final boolean softRevalidate = true;
    @Builder.Default
    private final Duration gcTtl = CacheKeys.GC_TTL;
    private final Function<V, String> serializer;
    private final Function<String, V> deserializer;
    @Builder.Default
    private final Supplier<Long> clock = System::currentTimeMillis;
    /** Injectable jitter source in [0,1) for tests; production uses {@link Revalidator#randomJitter()}. */
    @Builder.Default
    private final Supplier<Double> ttlJitter = Revalidator::randomJitter;

    private final ConcurrentHashMap<String, Boolean> refreshing = new ConcurrentHashMap<>();

    public Optional<V> get(final String id, final Function<String, V> loader) {
        final String key = keyPrefix + id;
        final Optional<String> cached = cache.getValue(key);
        if (cached.isPresent()) {
            final Envelope env = Envelope.decode(cached.get());
            final V value = deserializer.apply(env.json());
            if (value != null) {
                maybeScheduleRefresh(id, key, env, loader);
                return Optional.of(value);
            }
        }
        final V value = loader.apply(id);
        if (value == null) {
            return Optional.empty();
        }
        put(id, value);
        return Optional.of(value);
    }

    private void maybeScheduleRefresh(
            final String id, final String key, final Envelope env, final Function<String, V> loader) {
        final Revalidator revalidator = revalidator();
        if (softRevalidate && revalidator.isSoftStale(env.loadedAt())) {
            revalidator.schedule(refreshing, id, key, "point", () -> applyBackgroundLoad(id, loader.apply(id)));
        }
    }

    /**
     * Always returns true: callers (DAO save paths) treat this as the operation result, and a cache
     * failure must never fail an operation DynamoDB already accepted -- DynamoDB is the source of truth.
     *
     * <p>On a failed SET the existing entry is left alone: a failed put means the cache itself is
     * unhealthy, and the old {@code remove(id)} here amplified exactly that state -- during the 2026-08-04
     * brown-out every timed-out refresh SET issued an UNLINK against the same drowning cache, evicting
     * healthy entries and turning "slow" into a DynamoDB miss-stampede. A stale-but-present entry heals
     * via soft revalidate.</p>
     */
    public boolean put(final String id, final V value) {
        final String json = serializer.apply(value);
        if (json == null) {
            return remove(id);
        }
        cache.putValue(keyPrefix + id, clock.get() + "|" + json, gcTtl);
        return true;
    }

    public boolean remove(final String id) {
        cache.removeKey(keyPrefix + id);
        return true;
    }

    private void applyBackgroundLoad(final String id, final V value) {
        if (value == null) {
            remove(id);
        } else {
            put(id, value);
        }
    }

    private Revalidator revalidator() {
        return new Revalidator(cache, softTtl, clock, ttlJitter);
    }

    /**
     * Decoded cache value. Legacy values (no {@code digits|} prefix) decode with {@code loadedAt = 0},
     * which {@link Revalidator#isSoftStale(long)} always judges stale -- so pre-envelope entries serve once
     * and are rewritten enveloped by the refresh, healing the format without a flush.
     */
    private record Envelope(long loadedAt, String json) {
        private static Envelope decode(final String raw) {
            final int sep = raw.indexOf('|');
            if (sep < 1) {
                return new Envelope(0L, raw);
            }
            try {
                return new Envelope(Long.parseLong(raw.substring(0, sep)), raw.substring(sep + 1));
            } catch (final NumberFormatException ex) {
                return new Envelope(0L, raw);
            }
        }
    }
}
