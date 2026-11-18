package org.paulsens.trip.api;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.HexFormat;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.WindowCounter;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;

/**
 * The fixed-window limit on the endpoints that answer anyone, counted per client address: Discover has no
 * account to count against, so the address is all there is. On the {@code ChatRateLimiter} pattern, and for its
 * reasons: <b>this class holds no per-client state</b> (the counter lives in the shared cache with the window
 * length in its key, so it is right across servers and survives this object, which is built per request), and a
 * limit <b>fails open</b>: when the cache cannot count, requests are served and a per-server fallback keeps a
 * ceiling, because failing closed would take a public list down over a cache blip.
 *
 * <p>The address is {@code getRemoteAddr()}, which the container's {@code RemoteIpValve} has already replaced
 * with the client's own (the load balancer's {@code X-Forwarded-For}); the header is never read here, since a
 * caller can write anything into it. An IPv6 address is counted by its /64: one subscriber is handed a whole
 * /64, and counting single addresses would give a script 2^64 allowances. The default limit is generous
 * ({@link KnownSettings#DISCOVER_RATE_LIMIT}) because a mobile carrier's NAT puts many phones behind one IPv4
 * address.
 */
@Slf4j
public class PublicRateLimiter {

    /** The one public surface counted today; a second would count separately under its own name. */
    static final String DISCOVER = "discover";

    /** The fallback forgets everything at this size: a ceiling on memory, since its keys are caller-chosen. */
    static final int FALLBACK_MAX_KEYS = 10_000;

    /**
     * The window counter shared with the chat limiter's shape ({@link WindowCounter}), its fallback bounded because a
     * caller chooses its keys (its address). Static so the per-request instances share it.
     */
    private static final WindowCounter COUNTER = new WindowCounter(FALLBACK_MAX_KEYS);

    private final CacheClient cacheClient;
    private final ConfigCommands config;
    /** Set only by the test constructor; null reads the settings on each check, so a change needs no restart. */
    private final Integer fixedLimit;
    private final Integer fixedWindowSeconds;

    /** What a check decided: allowed, or how long until the window turns over. */
    public record Decision(boolean allowed, int retryAfterSeconds) {
    }

    public PublicRateLimiter() {
        this(DAO.getInstance().getCacheClient(), new ConfigCommands(), null, null);
    }

    /** Test constructor: pins the limit and window so a case does not depend on the config table. */
    PublicRateLimiter(final CacheClient cacheClient, final int limit, final int windowSeconds) {
        this(cacheClient, new ConfigCommands(), Math.max(1, limit), Math.max(1, windowSeconds));
    }

    private PublicRateLimiter(final CacheClient cacheClient, final ConfigCommands config, final Integer fixedLimit,
            final Integer fixedWindowSeconds) {
        this.cacheClient = cacheClient;
        this.config = config;
        this.fixedLimit = fixedLimit;
        this.fixedWindowSeconds = fixedWindowSeconds;
    }

    /** Clears the degraded-mode counters. Only for tests: the map is static, so it would carry counts across them. */
    static void clearFallbackBucketsForTest() {
        COUNTER.clearForTest();
    }

    /** Counts one Discover request from {@code remoteAddr} and says whether it may be served. */
    public Decision check(final String remoteAddr, final Instant now) {
        final int window = windowSeconds();
        final long epochSec = now.getEpochSecond();
        final long count = count(CacheKeys.publicRateLimitKey(DISCOVER, clientKey(remoteAddr), window, epochSec),
                window);
        if (count <= limit()) {
            return new Decision(true, 0);
        }
        // Cheap stdout marker for ad-hoc log searches; deliberately no audit row: an anonymous script hammering a
        // public URL must not be able to write to an append-only trail.
        log.info("PUBLIC-RATE-LIMIT surface={} client={}", DISCOVER, clientKey(remoteAddr));
        return new Decision(false, (int) Math.max(1L, window - (epochSec % window)));
    }

    /**
     * What a client is counted as: its IPv4 address, or the /64 of its IPv6 address. Text that is no address at
     * all (which the container never hands over) is counted together under one key rather than escaping the limit.
     */
    static String clientKey(final String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return "unknown";
        }
        // Without its zone id (fe80::1%en0): the zone names a local interface, not the client.
        final String addr = remoteAddr.trim().replaceFirst("%.*$", "");
        // Parsed only when it is spelled like an address literal, so getByName can never become a DNS lookup.
        if (!addr.matches("[0-9a-fA-F:.]+") || addr.indexOf(':') < 0) {
            return addr.matches("[0-9.]+") ? addr : "unknown";
        }
        try {
            final InetAddress parsed = InetAddress.getByName(addr);
            return parsed instanceof Inet6Address ? HexFormat.of().formatHex(parsed.getAddress(), 0, 8) + "/64"
                    : parsed.getHostAddress();
        } catch (final UnknownHostException ex) {
            return "unknown";
        }
    }

    private int limit() {
        return fixedLimit != null ? fixedLimit
                : config.getInt(KnownSettings.DISCOVER_RATE_LIMIT, 1, Integer.MAX_VALUE);
    }

    private int windowSeconds() {
        return fixedWindowSeconds != null ? fixedWindowSeconds
                : config.getInt(KnownSettings.DISCOVER_RATE_WINDOW_SECONDS, 1, Integer.MAX_VALUE);
    }

    private long count(final String key, final int windowSeconds) {
        return COUNTER.count(cacheClient, key, windowSeconds);
    }
}
