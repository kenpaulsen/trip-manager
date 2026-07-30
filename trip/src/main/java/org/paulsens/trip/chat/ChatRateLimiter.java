package org.paulsens.trip.chat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatSettings;

/**
 * Two-tier fixed-window rate limits (burst + sustained) plus a global per-user tier, with slow mode and an
 * escalating auto-mute on top.
 *
 * <p><b>This class holds no per-user state.</b> Every counter — the three limit windows, the auto-mute hit count
 * and the escalation tier — lives in the shared cache with the window length in the key, so it is correct across
 * tasks and cannot be reset by constructing a new instance. That matters twice over: the bean is rebuilt per
 * request on the REST edge, and a second ECS task would otherwise get its own private idea of who is misbehaving.
 *
 * <p>The single exception is {@link #FALLBACK_BUCKETS}, used only when the cache is unreachable. It is
 * unavoidably process-local — with no shared counter there is nowhere else to count — and it is {@code static} so
 * that at least it survives instance churn. Treat its numbers as a ceiling, not a measurement.
 *
 * <p><b>Fail direction, decided once:</b> a <em>rate limit</em> fails <b>open</b>, because failing closed would
 * silence an entire trip's chat over a cache blip. <em>Slow mode</em> fails <b>closed</b>, because
 * {@code tryAcquireLock} cannot distinguish "someone holds the lock" from "the cache errored", and slow mode is an
 * explicit admin action whose enforced behaviour ("wait N seconds") is also exactly the right thing to show if we
 * guess wrong.
 */
@Slf4j
public class ChatRateLimiter {

    // TODO(config-store): config.getInt("chat.autoMute.triggerCount", 3)
    static final int AUTO_MUTE_TRIGGER_COUNT = 3;
    // TODO(config-store): config.getInt("chat.autoMute.triggerWindowSeconds", 600)
    static final int AUTO_MUTE_TRIGGER_WINDOW_SECONDS = 600;
    // TODO(config-store): config.getInt("chat.global.limit", 200)
    public static final int DEFAULT_GLOBAL_LIMIT = 200;
    // TODO(config-store): config.getInt("chat.global.windowSeconds", 300)
    public static final int DEFAULT_GLOBAL_WINDOW_SECONDS = 300;

    /** Escalation ladder, indexed by tier - 1: 5 min, then 30 min, then 24 h. */
    // TODO(config-store): config.getString("chat.autoMute.ladderMinutes", "5,30,1440")
    private static final int[] AUTO_MUTE_MINUTES = {5, 30, 24 * 60};

    /**
     * Degraded-mode ceiling, used only when the cache returns empty. Static so instance churn does not hand a
     * sender a fresh allowance on every request; still per-task, which is the honest limit of a local fallback.
     */
    private static final ConcurrentHashMap<String, AtomicInteger> FALLBACK_BUCKETS = new ConcurrentHashMap<>();

    private final CacheClient cacheClient;
    private final int globalLimit;
    private final int globalWindowSeconds;

    public ChatRateLimiter(final CacheClient cacheClient) {
        this(cacheClient, DEFAULT_GLOBAL_LIMIT, DEFAULT_GLOBAL_WINDOW_SECONDS);
    }

    /**
     * Clears the degraded-mode buckets. Only for tests: because the map is static (deliberately, so instance churn
     * cannot reset a limit in production), it would otherwise carry counts between test methods.
     */
    static void clearFallbackBucketsForTest() {
        FALLBACK_BUCKETS.clear();
    }

    public ChatRateLimiter(final CacheClient cacheClient, final int globalLimit, final int globalWindowSeconds) {
        this.cacheClient = cacheClient;
        this.globalLimit = Math.max(1, globalLimit);
        this.globalWindowSeconds = Math.max(1, globalWindowSeconds);
    }

    /**
     * Decides whether this person may post now. When denied, the {@link Decision} carries what the user should be
     * told and, if the escalation threshold was crossed, the {@code autoMuteUntil} the caller must persist.
     */
    public Decision check(final ChatChannel channel, final Person.Id personId, final Instant now) {
        if (channel == null || personId == null || now == null) {
            return Decision.deny("forbidden", 1);
        }
        final ChatSettings settings = channel.getSettings() == null
                ? ChatSettings.defaults() : channel.getSettings();
        final String channelId = channel.getId().getValue();
        final String person = personId.getValue();
        final long epochSec = now.getEpochSecond();

        if (settings.getSlowModeSeconds() > 0 && !acquireSlowModeSlot(channelId, person, settings)) {
            return Decision.deny("slow_mode", settings.getSlowModeSeconds());
        }

        final long burst = count(CacheKeys.chatRateLimitKey(
                channelId, person, "b", settings.getBurstWindowSeconds(), epochSec),
                settings.getBurstWindowSeconds());
        if (burst > settings.getBurstLimit()) {
            return onLimitHit(channel, personId, now, "burst", burst,
                    settings.getBurstLimit(), settings.getBurstWindowSeconds());
        }
        final long sustained = count(CacheKeys.chatRateLimitKey(
                channelId, person, "s", settings.getSustainedWindowSeconds(), epochSec),
                settings.getSustainedWindowSeconds());
        if (sustained > settings.getSustainedLimit()) {
            return onLimitHit(channel, personId, now, "sustained", sustained,
                    settings.getSustainedLimit(), settings.getSustainedWindowSeconds());
        }
        final long global = count(
                CacheKeys.chatRateLimitKey(null, person, "g", globalWindowSeconds, epochSec),
                globalWindowSeconds);
        if (global > globalLimit) {
            return onLimitHit(channel, personId, now, "global", global, globalLimit, globalWindowSeconds);
        }
        return Decision.allow();
    }

    /**
     * {@code SET NX EX} as a per-user cooldown: acquiring means this is the first message of the window. Returns
     * false both when the cooldown is active and when the cache errored, which we deliberately treat the same way
     * (see the class comment).
     */
    private boolean acquireSlowModeSlot(
            final String channelId, final String person, final ChatSettings settings) {
        return cacheClient.tryAcquireLock(CacheKeys.chatSlowModeKey(channelId, person),
                Duration.ofSeconds(settings.getSlowModeSeconds())).join();
    }

    /**
     * The count in the current window. Two windows' worth of TTL so a key outlives its own window (the window
     * index is in the key, so an expired window is simply a key nobody reads again).
     */
    private long count(final String key, final int windowSeconds) {
        final Duration ttl = Duration.ofSeconds(Math.max(1L, windowSeconds * 2L));
        return cacheClient.increment(key, 1, ttl).join()
                .orElseGet(() -> (long) FALLBACK_BUCKETS
                        .computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet());
    }

    private Decision onLimitHit(
            final ChatChannel channel,
            final Person.Id personId,
            final Instant now,
            final String tier,
            final long count,
            final int limit,
            final int windowSeconds) {
        final String channelId = channel.getId().getValue();
        final String person = personId.getValue();
        // Cheap stdout marker for ad-hoc CloudWatch Logs Insights searches; no metric filter behind it.
        log.info("CHAT-RATE-LIMIT channel={} person={} tier={}", channelId, person, tier);

        maybeAlarm(channelId, person, tier, count, limit, now);
        final Optional<Instant> autoMute = maybeAutoMute(channel, personId, now);

        final int retry = Math.max(1, windowSeconds / 2);
        return Decision.deny(tier, retry, limit, windowSeconds, autoMute.orElse(null));
    }

    /**
     * One {@code ALARM} record per person per channel per dedupe window. Without the gate a script hammering send
     * would write thousands of rows into an append-only, never-expiring trail — an amplification attack through
     * the audit system.
     */
    private void maybeAlarm(
            final String channelId, final String person, final String tier,
            final long count, final int limit, final Instant now) {
        final boolean first = cacheClient.tryAcquireLock(
                CacheKeys.chatAlarmLockKey(channelId, person, now.getEpochSecond()),
                Duration.ofSeconds(CacheKeys.CHAT_ALARM_DEDUPE_WINDOW_SECONDS)).join();
        if (!first) {
            return;
        }
        Audit.log(Audit.builder(AuditAction.ALARM, AuditOutcome.FAILURE)
                .actor(person, person)
                .target(AuditEventBuilder.TARGET_CHAT_CHANNEL, channelId)
                .message("chat.rateLimit: person=" + person + " channel=" + channelId
                        + " tier=" + tier + " count=" + count + "/" + limit)
                .build());
    }

    /**
     * Escalating auto-mute: {@value #AUTO_MUTE_TRIGGER_COUNT} limit hits inside the trigger window earn a mute,
     * lengthening on repeat offences until the tier decays. Returns the instant the caller must store on the
     * membership row — this method deliberately does not write it, so the mute and its audit record stay with the
     * code that owns membership.
     */
    private Optional<Instant> maybeAutoMute(
            final ChatChannel channel, final Person.Id personId, final Instant now) {
        final String channelId = channel.getId().getValue();
        final String person = personId.getValue();
        final String hitsKey = CacheKeys.chatAutoMuteHitsKey(
                channelId, person, AUTO_MUTE_TRIGGER_WINDOW_SECONDS, now.getEpochSecond());
        final long hits = count(hitsKey, AUTO_MUTE_TRIGGER_WINDOW_SECONDS);
        if (hits < AUTO_MUTE_TRIGGER_COUNT) {
            return Optional.empty();
        }
        // Reset the counter now that it has been spent. Without this, EVERY subsequent hit is still >= the
        // threshold, so each one escalates a tier and the ladder jumps to its maximum within a few messages
        // instead of requiring a fresh set of offences per step.
        cacheClient.removeKey(hitsKey).join();
        FALLBACK_BUCKETS.remove(hitsKey);
        final int tier = nextTier(channelId, person);
        final int minutes = AUTO_MUTE_MINUTES[Math.min(tier, AUTO_MUTE_MINUTES.length) - 1];
        final Instant until = now.plus(Duration.ofMinutes(minutes));

        Audit.log(Audit.builder(AuditAction.ALARM, AuditOutcome.FAILURE)
                .actor(person, person)
                .target(AuditEventBuilder.TARGET_CHAT_CHANNEL, channelId)
                .message("chat.autoMute: person=" + person + " channel=" + channelId
                        + " tier=" + tier + " duration=" + minutes + "m")
                .build());
        Audit.log(Audit.builder(AuditAction.CHAT_ADMIN, AuditOutcome.SUCCESS)
                .actor("system", "system")
                .target(AuditEventBuilder.TARGET_CHAT_CHANNEL, channelId)
                .message("automatic mute applied to " + person + "; tier=" + tier
                        + " duration=" + minutes + "m")
                .build());
        return Optional.of(until);
    }

    /**
     * The next escalation tier, incremented in the cache with a decay TTL so good behaviour resets it without a
     * sweeper. Falls back to tier 1 when the cache is unavailable: with no shared counter, the base penalty is
     * the honest answer rather than a guessed escalation.
     */
    private int nextTier(final String channelId, final String person) {
        return cacheClient.increment(
                        CacheKeys.chatAutoMuteTierKey(channelId, person), 1,
                        Duration.ofHours(CacheKeys.CHAT_AUTO_MUTE_TIER_DECAY_HOURS))
                .join()
                .map(Long::intValue)
                .orElse(1);
    }

    @Value
    public static class Decision {
        boolean allowed;
        String reason;
        int retryAfterSeconds;
        int limit;
        int windowSeconds;
        /**
         * Set when this denial crossed the auto-mute threshold. The caller must persist it on the membership row;
         * returning it here rather than stashing it in a map is what keeps this class stateless.
         */
        Instant autoMuteUntil;

        public static Decision allow() {
            return new Decision(true, null, 0, 0, 0, null);
        }

        public static Decision deny(final String reason, final int retryAfterSeconds) {
            return new Decision(false, reason, retryAfterSeconds, 0, 0, null);
        }

        public static Decision deny(
                final String reason, final int retryAfterSeconds, final int limit,
                final int windowSeconds, final Instant autoMuteUntil) {
            return new Decision(false, reason, retryAfterSeconds, limit, windowSeconds, autoMuteUntil);
        }

        public String userMessage() {
            if (allowed) {
                return null;
            }
            if ("slow_mode".equals(reason)) {
                return "Slow mode is on — wait " + retryAfterSeconds + " seconds between messages.";
            }
            if (limit > 0 && windowSeconds > 0) {
                return "Slow down — you can send " + limit + " messages every " + windowSeconds
                        + " seconds. Try again in " + retryAfterSeconds + " seconds.";
            }
            return "Slow down — try again in " + retryAfterSeconds + " seconds.";
        }
    }
}
