package org.paulsens.trip.cache;

import java.time.Duration;

/**
 * Single authority for shared-cache key names. Every key written by the data layer starts with {@link #FORMAT_VERSION}
 * so an incompatible change to the cached JSON format can be rolled out by bumping the prefix (old instances keep
 * reading their own keys; abandoned keys age out via {@link #GC_TTL} or explicit clear).
 *
 * <p>Entity data uses <strong>soft revalidate</strong>: {@link #LOADED_AT} (or binding marker epoch) records the last
 * full load from Dynamo. After {@link #SOFT_TTL}, reads still return cache hits immediately and a background reload
 * may run (read-triggered; idle partitions can stay soft-stale until next access — admin clear for impatient cases).
 * Write-through does <em>not</em> refresh {@link #LOADED_AT}. Hard {@code EXPIRE} of {@link #GC_TTL} is only a
 * hygiene backstop for abandoned keys / GB-hour billing, not the primary coherence mechanism.</p>
 */
public final class CacheKeys {
    /** Bump (t1: -> t2:) on any incompatible change to the cached value format. */
    public static final String FORMAT_VERSION = "t1:";

    // Privileges are partitioned by trip: one hash per partition (PRIV_PREFIX + partition), field = base priv name,
    // value = Privilege JSON. The partition is the tripId for trip-scoped privileges, or PRIV_GLOBAL_PARTITION.
    // A single loaded marker (PRIV_LOADED) covers all partitions -- one scan rebuilds them together. See
    // PrivilegeIndex.
    public static final String PRIV_PREFIX = FORMAT_VERSION + "priv:";
    public static final String PRIV_GLOBAL_PARTITION = "__global__";
    public static final String PRIV_LOADED = FORMAT_VERSION + "priv_loaded";

    public static String privPartitionKey(final String partition) {
        return PRIV_PREFIX + partition;
    }

    // Trips are point entries (no whole-table hash): TRIP_PREFIX + {tripId}.
    public static final String TRIP_PREFIX = FORMAT_VERSION + "trip:";

    // Trip date index: sorted set, member = tripId, score = endDate epoch millis. Plus a sibling loaded marker.
    public static final String TRIPS_BY_DATE = FORMAT_VERSION + "idx:trips";
    // Person -> trips reverse index: sorted set (lex), member = "userId|tripId". Shares TRIPS_BY_DATE's marker
    // (both are rebuilt by the same trip scan). See TripIndex.
    public static final String TRIPS_BY_PERSON = FORMAT_VERSION + "idx:person_trips";

    // People are point entries (no whole-table hash): PERSON_PREFIX + {personId}.
    public static final String PERSON_PREFIX = FORMAT_VERSION + "person:";

    // Families are point entries: FAMILY_PREFIX + {familyId}. Reverse lookup is Person.familyId, so there is
    // no scan path and no index.
    public static final String FAMILY_PREFIX = FORMAT_VERSION + "family:";

    // Organizations: the whole (small) table in ONE hash, like config/media -- org lists render on ordinary
    // admin pages and pickers, so a read must be one HGETALL. Membership rows are a per-org partition hash
    // (append the orgId); the reverse edge is Person.orgIds, so there is no by-person partition.
    public static final String ORG_PREFIX = FORMAT_VERSION + "org:";
    public static final String ORG_PARTITION = "__all__";
    public static final String ORG_LOADED = FORMAT_VERSION + "org_loaded";
    public static final String ORG_MEMBER_PREFIX = FORMAT_VERSION + "org_member:";
    // Payment-processor configs: per-org partition hash (append the orgId) -- the org IS the tenancy boundary.
    public static final String PROCESSOR_PREFIX = FORMAT_VERSION + "processor:";

    // Email lookup hash: field = lowercased email, value = personId. Lazy cache in front of the email-index GSI
    // (and the authoritative store in local mode, where every save goes through write-through).
    public static final String EMAIL_IDX = FORMAT_VERSION + "email";

    // People search index: lexicographic sorted set of "token|personId" entries plus a sibling loaded marker
    // (value = epoch millis of last full build). See SearchIndex.
    public static final String PEOPLE_SEARCH = FORMAT_VERSION + "idx:people";
    public static final String SEARCH_LOADED_SUFFIX = ":loaded";

    /** Separator between a search token and the entity id in a search index entry. Stripped from tokens. */
    public static final char SEARCH_SEPARATOR = '|';

    /**
     * Soft revalidate age for search indexes: rebuilding means a full table scan, so it is much lazier than
     * {@link #SOFT_TTL}. Out-of-band writes through the DAO keep the index current; this only heals direct
     * database edits.
     */
    public static final Duration INDEX_SOFT_TTL = Duration.ofHours(24);

    // Per-partition hashes (append the partition id).
    public static final String REG_PREFIX = FORMAT_VERSION + "reg:";
    public static final String TX_PREFIX = FORMAT_VERSION + "tx:";
    public static final String TODO_PREFIX = FORMAT_VERSION + "todo:";
    public static final String PDV_PREFIX = FORMAT_VERSION + "pdv:";

    // Point entries (append the entity id). The value is a PointCache envelope ("<epochMillis>|<json>"), so
    // the loaded-at stamp travels with the data and staleness never costs a second cache read.
    public static final String TRIP_EVENT_PREFIX = FORMAT_VERSION + "trip_event:";

    // Runtime settings. The config table is tiny and read on ordinary page renders, so the WHOLE table lives in
    // one hash under a single fixed partition (field = setting name, value = Config JSON): a read is one
    // HGETALL, and a save invalidates it for every instance at once.
    public static final String CONFIG_PREFIX = FORMAT_VERSION + "config:";
    public static final String CONFIG_PARTITION = "__all__";
    public static final String CONFIG_LOADED = FORMAT_VERSION + "config_loaded";

    // Managed media metadata. Same shape as config: small table, wanted whole on ordinary renders, so it is a
    // single hash under one partition (field = item id, value = MediaItem JSON).
    public static final String MEDIA_PREFIX = FORMAT_VERSION + "media:";
    public static final String MEDIA_PARTITION = "__all__";
    public static final String MEDIA_LOADED = FORMAT_VERSION + "media_loaded";

    // Content templates: one hash, but the FIELD is id + "#v" + version -- every retained version of a
    // template is its own cache entry, so content pinned to an old version keeps rendering from cache after
    // the template advances, and a cached blob can never be paired with values authored against a different
    // version.
    public static final String TEMPLATE_PREFIX = FORMAT_VERSION + "tmpl:";
    public static final String TEMPLATE_PARTITION = "__all__";
    public static final String TEMPLATE_LOADED = FORMAT_VERSION + "tmpl_loaded";

    // Template-driven page content, partitioned by section key (field = instance id): rendering a page
    // section is one HGETALL of that section.
    public static final String CONTENT_PREFIX = FORMAT_VERSION + "content:";
    public static final String CONTENT_LOADED = FORMAT_VERSION + "content_loaded";

    /**
     * Soft TTL for templates and content: public-page sections must pick up edits within five minutes.
     * Stale reads still answer from cache; the refresh happens in the background (feature requirement).
     */
    public static final Duration CONTENT_SOFT_TTL = Duration.ofMinutes(5);

    /** The versioned cache field for one template version. */
    public static String templateField(final String id, final int version) {
        return id + "#v" + version;
    }

    // Binding adjacency sets: BIND_PREFIX + {typeAndId} + ":" + {destTypeId}; loaded marker uses BIND_LOADED_SUFFIX.
    public static final String BIND_PREFIX = FORMAT_VERSION + "bind:";
    public static final String BIND_LOADED_SUFFIX = ":loaded";

    /**
     * Chat namespace — deliberately <em>not</em> under {@link #FORMAT_VERSION}, so {@code DAO.clearAllCaches()}
     * (which clears {@code t1:}) cannot wipe a trip's conversation. At short retention Valkey is part of the
     * delivery path and must survive an admin "clear all caches".
     *
     * <p>Pub/sub channel names keep the reserved shape {@code chat:trip:{tripId}} /
     * {@code chat:dm:{lowUserId}:{highUserId}} and sit outside the versioned segment. ElastiCache Serverless
     * does not support PSUBSCRIBE, so channel names must always be enumerable by subscribers.
     */
    public static final String CHAT_CHANNEL_PREFIX = "chat:";

    /** Chat key format lever (independent of entity {@link #FORMAT_VERSION}). */
    public static final String CHAT_FORMAT_VERSION = CHAT_CHANNEL_PREFIX + "v1:";

    public static String chatLogKey(final String channelId) {
        return CHAT_FORMAT_VERSION + "log:" + channelId;
    }

    public static String chatBodyKey(final String channelId) {
        return CHAT_FORMAT_VERSION + "body:" + channelId;
    }

    /** Point-cache prefix for channel metadata (append channelId). */
    public static final String CHAT_CHANNEL_META_PREFIX = CHAT_FORMAT_VERSION + "chan:";

    public static String chatChannelKey(final String channelId) {
        return CHAT_CHANNEL_META_PREFIX + channelId;
    }

    public static String chatMembersKey(final String channelId) {
        return CHAT_FORMAT_VERSION + "mem:" + channelId;
    }

    public static String chatCursorKey(final String channelId, final String personId) {
        return CHAT_FORMAT_VERSION + "cur:" + channelId + ":" + personId;
    }

    /**
     * Rate-limit counter. Window length is part of the key so an admin changing the window cannot collide with
     * a live counter written under the previous length.
     */
    public static String chatRateLimitKey(
            final String channelId, final String personId, final String tier, final int windowSeconds,
            final long epochSec) {
        final long winIndex = windowSeconds <= 0 ? 0L : epochSec / windowSeconds;
        if (channelId == null) {
            return CHAT_FORMAT_VERSION + "rl:" + personId + ":" + tier + ":" + windowSeconds + ":" + winIndex;
        }
        return CHAT_FORMAT_VERSION + "rl:" + channelId + ":" + personId + ":" + tier + ":" + windowSeconds + ":"
                + winIndex;
    }

    public static String chatSlowModeKey(final String channelId, final String personId) {
        return CHAT_FORMAT_VERSION + "slow:" + channelId + ":" + personId;
    }

    public static String chatReactionSummaryKey(final String channelId) {
        return CHAT_FORMAT_VERSION + "rsum:" + channelId;
    }

    public static String chatClientMessageKey(
            final String channelId, final String personId, final String clientMessageId) {
        return CHAT_FORMAT_VERSION + "cmid:" + channelId + ":" + personId + ":" + clientMessageId;
    }

    /** Global last-activity hash: field = channelId, value = epoch millis. */
    public static final String CHAT_LAST_ACTIVITY = CHAT_FORMAT_VERSION + "lastact";

    /**
     * Global per-photo meta hash: field = s3Key, value = {@code PhotoChatMeta} JSON. Rsum-like contract:
     * the field is dropped on every write to that photo's thread and rebuilt on the next read, so staleness
     * self-heals. In the chat namespace deliberately — badge counts must survive {@code clearAllCaches}
     * no more than any other chat summary does.
     */
    public static String chatPhotoMetaKey() {
        return CHAT_FORMAT_VERSION + "pmeta";
    }

    /** Per-person mention-search counter for one minute window {@code win} — the typeahead's harvest brake. */
    public static String chatMentionSearchKey(final String personId, final long win) {
        return CHAT_FORMAT_VERSION + "pms:" + personId + ":" + win;
    }

    public static String chatReactionsVersionKey(final String channelId) {
        return CHAT_FORMAT_VERSION + "rver:" + channelId;
    }

    /**
     * Counts edits and tombstones in a channel.
     *
     * <p>Separate from the reaction counter on purpose: these drive <em>different</em> client refetches (message
     * bodies versus reaction summaries), and sharing one counter would make every reaction refetch a page of
     * messages and every edit refetch every summary.
     *
     * <p>It exists at all because an edit or a tombstone changes a message the client <b>already has</b>, so
     * nothing advances its cursor and the change can never be delivered by the feed. Without this, an admin delete
     * only reached a client that happened to reload.
     */
    public static String chatMutationsVersionKey(final String channelId) {
        return CHAT_FORMAT_VERSION + "mver:" + channelId;
    }

    /**
     * Marks a notification as already delivered, so a retry or a restart mid-run cannot re-send it.
     *
     * <p>A cache marker rather than a durable row: a duplicate email is an annoyance, not a correctness failure, so
     * paying for a Dynamo write per recipient per notification would be the wrong trade. The TTL bounds it.
     */
    public static String chatNotifySentKey(final String dedupeKey) {
        return CHAT_FORMAT_VERSION + "sent:" + dedupeKey;
    }

    /** How long a delivery marker survives. Long enough that a same-day retry cannot double-send. */
    public static final Duration CHAT_NOTIFY_SENT_TTL = Duration.ofHours(24);

    /**
     * The lock one instance must hold to run a digest, keyed by the run it is for.
     *
     * <p>Every task schedules the digest independently, so without this every running task would send it. Keyed by
     * run rather than globally so a stuck lock cannot block tomorrow's digest as well as today's.
     */
    public static String chatDigestLockKey(final String runId) {
        return CHAT_FORMAT_VERSION + "digest:lock:" + runId;
    }

    /**
     * Field-per-person record of who has been dealt with in a run.
     *
     * <p>A person's id is written <b>before</b> their mail is attempted and removed only if the attempt fails. The
     * asymmetry is deliberate: a crash between the write and the send leaves them marked, so they are skipped rather
     * than mailed twice. Missing a summary beats sending it twice.
     */
    public static String chatDigestProgressKey(final String runId) {
        return CHAT_FORMAT_VERSION + "digest:done:" + runId;
    }

    /**
     * The atomic claim on one person's digest within a run.
     *
     * <p>Separate from the progress hash because only this is <b>race-free</b>: it is a SET-if-absent, so exactly
     * one caller can ever win it. The progress hash is read once as a snapshot at the start of a dispatch, and a
     * snapshot cannot see a claim made after it was taken — so with two tasks dispatching at once (which the run
     * lock permits the moment its TTL lapses mid-run) both would believe a person was unclaimed and both would
     * mail them. The hash remains as the durable record of what happened; this is what makes it exclusive.
     */
    public static String chatDigestClaimKey(final String runId, final String progressField) {
        return CHAT_FORMAT_VERSION + "digest:claim:" + runId + ':' + progressField;
    }

    /** Set once a run is finished, so other instances stop retrying instead of re-sending. */
    public static String chatDigestCompleteKey(final String runId) {
        return CHAT_FORMAT_VERSION + "digest:complete:" + runId;
    }

    /**
     * The newest message already summarised to this person.
     *
     * <p>Distinct from their read cursor, and both are needed. The cursor says what they have <em>looked at</em> and
     * must not be advanced by a mail they may never open; this says what they have already been <em>told about</em>.
     * Without it every digest would repeat yesterday's messages to anyone who never opened the chat.
     */
    public static String chatDigestWatermarkKey(final String channelId, final String personId) {
        return CHAT_FORMAT_VERSION + "digest:wm:" + channelId + ":" + personId;
    }

    /**
     * Lifetime of a run's lock, progress hash and completion flag.
     *
     * <p>Comfortably longer than the give-up window, so a late retry still finds the completion flag and stands
     * down. If these expired first, a straggler would see no flag, conclude nothing had run, and send the digest
     * again to everyone.
     */
    public static final Duration CHAT_DIGEST_RUN_TTL = Duration.ofHours(30);

    public static String chatPubSubChannel(final String tripId) {
        return CHAT_CHANNEL_PREFIX + "trip:" + tripId;
    }

    /**
     * The pub/sub channel for an existing {@code ChatChannel.Id}, whose value already carries its kind prefix.
     *
     * <p>Equivalent to {@link #chatPubSubChannel(String)} for a trip channel -- {@code "trip:" + tripId} prefixed
     * with {@code chat:} is exactly {@code chat:trip:{tripId}} -- and it extends to the reserved DM shape
     * ({@code chat:dm:{low}:{high}}) without the caller having to take a channel id apart to find a trip id it does
     * not have. Names stay fully enumerable, which is required: ElastiCache Serverless has no PSUBSCRIBE.
     */
    public static String chatPubSubChannelFor(final String channelId) {
        return CHAT_CHANNEL_PREFIX + channelId;
    }

    /**
     * Counts rate-limit hits inside the auto-mute trigger window. Lives in the cache, not in a JVM map, so the
     * count is shared across tasks and cannot be reset by a new bean instance.
     */
    public static String chatAutoMuteHitsKey(
            final String channelId, final String personId, final int windowSeconds, final long epochSec) {
        final long winIndex = windowSeconds <= 0 ? 0L : epochSec / windowSeconds;
        return CHAT_FORMAT_VERSION + "amhits:" + channelId + ":" + personId + ":" + windowSeconds + ":" + winIndex;
    }

    /**
     * The escalation tier for auto-mute. Deliberately a cache key rather than a column on the membership row: the
     * tier is defined to decay after a period of good behaviour, so a TTL expresses it exactly and needs no
     * sweeper. The <em>mute itself</em> stays durable on the membership row, because that is an enforcement
     * decision; only the counter that chose its length is ephemeral.
     */
    public static String chatAutoMuteTierKey(final String channelId, final String personId) {
        return CHAT_FORMAT_VERSION + "amtier:" + channelId + ":" + personId;
    }

    /**
     * Dedupe gate for the {@code ALARM} audit record: at most one per person per channel per window, so a script
     * hammering send cannot flood an append-only, never-expiring trail.
     *
     * <p>{@code windowSeconds} is <b>in the key</b>, not merely used to compute the index, for the same reason it
     * is in the rate-limit keys: the window is administrator-adjustable, and without it in the key a change from
     * 300 to 600 produces indices that collide with keys already written under the old window, so one person
     * silently inherits another's dedupe state. With it in the key, a change simply starts a fresh set and the
     * old ones age out unread.
     */
    public static String chatAlarmLockKey(
            final String channelId, final String personId, final long epochSec, final int windowSeconds) {
        final long winIndex = windowSeconds <= 0 ? 0L : epochSec / windowSeconds;
        return CHAT_FORMAT_VERSION + "alarm:" + channelId + ":" + personId + ":" + windowSeconds + ":" + winIndex;
    }

    /**
     * Authentication namespace — like chat, deliberately <em>not</em> under {@link #FORMAT_VERSION}:
     * {@code DAO.clearAllCaches()} clears {@code t1:} and must never invalidate a login code someone is about
     * to type, or reset the rate-limit counters that throttle guessing.
     */
    public static final String AUTH_FORMAT_VERSION = "auth:v1:";

    /** Value is the base64 SHA-256 of the code, never the code: a cache dump must not contain live codes. */
    public static String loginCodeKey(final String lowEmail) {
        return AUTH_FORMAT_VERSION + "code:" + lowEmail;
    }

    /** Verify-attempt counter for the code under {@link #loginCodeKey}; deleted when a fresh code is issued. */
    public static String loginCodeAttemptsKey(final String lowEmail) {
        return AUTH_FORMAT_VERSION + "code_att:" + lowEmail;
    }

    /**
     * Send-rate counter. Window length is in the key (chat rate-limit convention) so an admin changing the
     * window cannot collide with a counter written under the previous length.
     */
    public static String loginCodeSendsKey(final String lowEmail, final int windowSeconds, final long epochSec) {
        final long winIndex = windowSeconds <= 0 ? 0L : epochSec / windowSeconds;
        return AUTH_FORMAT_VERSION + "code_sends:" + lowEmail + ":" + windowSeconds + ":" + winIndex;
    }

    /** Failed-password counter behind the login throttle; same windowed shape as {@link #loginCodeSendsKey}. */
    public static String passwordFailsKey(final String lowEmail, final int windowSeconds, final long epochSec) {
        final long winIndex = windowSeconds <= 0 ? 0L : epochSec / windowSeconds;
        return AUTH_FORMAT_VERSION + "pw_fail:" + lowEmail + ":" + windowSeconds + ":" + winIndex;
    }

    /** A pending WebAuthn registration ceremony, keyed by the (hashed) session id that started it. */
    public static String webauthnRegKey(final String sessionKey) {
        return AUTH_FORMAT_VERSION + "webauthn_reg:" + sessionKey;
    }

    /** A pending WebAuthn assertion ceremony; sessionless, so keyed by a random challenge token instead. */
    public static String webauthnAssertKey(final String challengeToken) {
        return AUTH_FORMAT_VERSION + "webauthn_assert:" + challengeToken;
    }

    /**
     * Hash field marking a hash as fully loaded from the database. A hash without this field may still contain
     * valid write-through entries, but only a hash with it may satisfy a "list all" or "not found" answer.
     */
    public static final String LOADED_SENTINEL = "__loaded__";
    public static final String LOADED_VALUE = "1";

    /**
     * Hash field: epoch millis of last <em>full load</em> from Dynamo (only meaningful with {@link #LOADED_SENTINEL}).
     * Write-through does not update this. Missing or unparseable → treat as soft-stale (background revalidate).
     */
    public static final String LOADED_AT = "__loaded_at__";

    /**
     * Soft revalidate age: after this, cache hits still serve immediately but may trigger a background Dynamo
     * reload. Since event-driven invalidation (the {@link #CACHE_INVAL_CHANNEL} broadcast + script hooks)
     * became the primary freshness mechanism, polling is only the heal path for missed events and console
     * edits -- hence hours, not minutes.
     */
    public static final Duration SOFT_TTL = Duration.ofHours(6);

    /**
     * Cache-invalidation broadcast channel. Outside {@link #FORMAT_VERSION} (it must survive
     * {@code DAO.clearAllCaches()}) and outside {@code chat:}. A single static name -- ElastiCache
     * Serverless has no PSUBSCRIBE, so subscribers must be able to enumerate their channels.
     */
    public static final String CACHE_INVAL_CHANNEL = "sys:v1:cache_inval";

    /**
     * Idle hygiene TTL applied after full loads (and refreshed on write-through activity). Bounds abandoned key
     * growth and GB-hours; not used as the primary delete-heal mechanism (that is soft revalidate + reconcile).
     */
    public static final Duration GC_TTL = Duration.ofDays(7);

    /**
     * Crash-safety TTL for distributed refresh locks ({@code SET NX EX}). Long enough for a cold full-table scan;
     * release is best-effort. Overlapping refreshes after expiry are safe by design (overlay merge + snapshot
     * reconcile; {@link #LOADED_AT} is last-write-wins).
     */
    public static final Duration REFRESH_LOCK_TTL = Duration.ofSeconds(45);

    /** Prefix for refresh locks: {@link #refreshLockKey(String)}. */
    public static final String REFRESH_LOCK_PREFIX = FORMAT_VERSION + "refresh:";

    public static String refreshLockKey(final String dataKey) {
        return REFRESH_LOCK_PREFIX + dataKey;
    }

    private CacheKeys() {
    }
}
