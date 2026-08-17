package org.paulsens.trip.cache;

/**
 * A caller's freshness tolerance, stated explicitly on every DAL read.
 *
 * <p>Every public read method on {@code DAO} requires one of these. That is deliberate policy, not
 * convenience: whether a read may be served from the in-JVM near-cache ({@link NearCacheClient}) is a
 * per-call-site decision, and an implicit default would let new call sites inherit whichever behavior the
 * author never thought about. The compiler forcing the parameter is the review.
 *
 * <p>{@link #YES} means "I tolerate near-cached data": the value may be up to the near-cache TTL old,
 * refreshed by the read-triggered background health check, and is exactly right for render paths whose
 * content the user accepts as eventually consistent (the public landing page was the motivating case).
 * {@link #NO} means "serve me what the shared cache / store answers right now" -- required for anything
 * that authenticates, authorizes, moves money, or reads data it is about to modify and save.
 *
 * <p>The flag states <em>tolerance</em>, not a guarantee of caching: domains whose DAOs are deliberately
 * uncached (credentials, remember-me, invites, audit) stay uncached whatever the caller passes, and keys
 * outside the data namespace are never near-cached. Passing {@code YES} there is a statement about the
 * call site, and becomes effective if the domain ever gains a cache.
 */
public enum Cached {
    /** Near-cached data is acceptable for this read (eventual consistency, bounded by the health check). */
    YES,
    /** This read must bypass the near-cache and see the shared cache / source of truth directly. */
    NO
}
