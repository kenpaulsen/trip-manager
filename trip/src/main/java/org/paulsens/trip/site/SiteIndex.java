package org.paulsens.trip.site;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Organization;

/**
 * Resolves a request's hostname to a {@link SiteContext}: an in-JVM snapshot of {@code slug -> org},
 * rebuilt from the (already-cached) organizations partition at most every {@link #REFRESH_INTERVAL}, plus
 * an explicit {@link #refresh()} the org save path calls so a slug assignment goes live immediately. The
 * hot path is a map lookup -- resolution runs on EVERY request, including anonymous scanner probes, and a
 * per-request cache or database read here is exactly what the 2026-08-19 NotFound load-shedding incident
 * forbids.
 *
 * <p>Tier changes are ONLINE by design: assigning an org's slug makes {@code {slug}.{base}} serve within one
 * refresh (immediately on the saving instance), and clearing it turns the host back into an UNKNOWN answer,
 * with no deploy, DNS edit, or restart. Wildcard DNS and the wildcard certificate already cover every label.
 *
 * <p>Host grammar, checked against the configured base domain and, for local development, {@code localhost}
 * (Chromium resolves {@code *.localhost} to loopback natively):
 * <ul>
 *   <li>{@code {base}} and {@code www.{base}} &rarr; MARKETING ({@code localhost} itself stays SHARED --
 *       every local page, test, and script depends on it rendering the classic site);</li>
 *   <li>{@code {label}.{base}} &rarr; ORG when the label is some org's slug, else UNKNOWN (multi-level
 *       labels are always UNKNOWN);</li>
 *   <li>anything else &rarr; SHARED, never an error: the shared hosts are deliberately not enumerated here,
 *       and a garbage Host header (direct-IP probes) must not 500.</li>
 * </ul>
 */
@Slf4j
public final class SiteIndex {

    /** How stale the slug snapshot may get before a resolve rebuilds it (cross-instance convergence bound). */
    static final Duration REFRESH_INTERVAL = Duration.ofSeconds(60);
    /** Retry pace after a failed rebuild: quick enough to heal, slow enough not to hammer a down cache. */
    static final Duration FAILURE_RETRY = Duration.ofSeconds(5);

    private static final SiteIndex INSTANCE = new SiteIndex(
            () -> DAO.getInstance().getOrganizations(Cached.YES),
            () -> new ConfigCommands().getString(KnownSettings.SITE_ORGSITES_BASE_DOMAIN),
            System::currentTimeMillis);

    private final Supplier<List<Organization>> orgLoader;
    private final Supplier<String> baseDomainSupplier;
    private final Supplier<Long> clock;
    private volatile Snapshot snapshot;

    /** The application-wide index; construction stores only suppliers, so class load touches no data. */
    public static SiteIndex getInstance() {
        return INSTANCE;
    }

    SiteIndex(final Supplier<List<Organization>> orgLoader, final Supplier<String> baseDomainSupplier,
            final Supplier<Long> clock) {
        this.orgLoader = orgLoader;
        this.baseDomainSupplier = baseDomainSupplier;
        this.clock = clock;
    }

    /** Resolves a hostname (no port) to its site. Never throws and never returns null. */
    public SiteContext resolve(final String rawHost) {
        if (rawHost == null || rawHost.isBlank()) {
            return SiteContext.shared(rawHost);
        }
        final String host = normalize(rawHost);
        final Snapshot snap = current();
        final SiteContext underBase = resolveUnder(host, snap.baseDomain(), false, snap);
        if (underBase != null) {
            return underBase;
        }
        final SiteContext underLocal = resolveUnder(host, "localhost", true, snap);
        if (underLocal != null) {
            return underLocal;
        }
        return SiteContext.shared(host);
    }

    /** Rebuilds the snapshot now -- the org save path calls this so slug changes go live immediately. */
    public void refresh() {
        rebuild(true);
    }

    /** Resolution against one base domain, or null when the host is not that base and not under it. */
    private static SiteContext resolveUnder(final String host, final String base, final boolean localBase,
            final Snapshot snap) {
        if (base == null || base.isBlank()) {
            return null;
        }
        if (host.equals(base)) {
            // The local base stays SHARED: plain localhost must keep rendering the classic site.
            return localBase ? SiteContext.shared(host) : SiteContext.marketing(host);
        }
        if (host.equals("www." + base)) {
            return SiteContext.marketing(host);
        }
        if (!host.endsWith("." + base)) {
            return null;
        }
        final String label = host.substring(0, host.length() - base.length() - 1);
        final Organization.Id orgId = label.indexOf('.') < 0 ? snap.slugs().get(label) : null;
        return orgId == null ? SiteContext.unknown(host) : SiteContext.org(orgId, label, host);
    }

    private static String normalize(final String rawHost) {
        final String lower = rawHost.trim().toLowerCase(Locale.ROOT);
        return lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
    }

    private Snapshot current() {
        final Snapshot snap = snapshot;
        if (snap != null && clock.get() < snap.staleAt()) {
            return snap;
        }
        return rebuild(false);
    }

    /**
     * Synchronized rebuild: the loaders read the already-warm org and config caches, so the occasional
     * in-line rebuild costs one cache read, and concurrent resolves just wait for it. A FAILED rebuild
     * keeps serving the previous snapshot (or an empty one on a cold start) and retries soon -- an org
     * subdomain answering UNKNOWN for a few seconds beats a request-path 500.
     */
    private synchronized Snapshot rebuild(final boolean force) {
        final Snapshot snap = snapshot;
        if (!force && snap != null && clock.get() < snap.staleAt()) {
            // Another resolve won the race and already rebuilt; forced refreshes (an org was just saved)
            // never take this exit -- a fresh-LOOKING snapshot is exactly what they exist to replace.
            return snap;
        }
        final String base = baseDomain(snap);
        try {
            final Map<String, Organization.Id> slugs = new HashMap<>();
            for (final Organization org : orgLoader.get()) {
                final String slug = org.getSlug();
                if (slug != null && !slug.isBlank()) {
                    slugs.put(slug.trim().toLowerCase(Locale.ROOT), org.getId());
                }
            }
            final Snapshot fresh = new Snapshot(Map.copyOf(slugs), base,
                    clock.get() + REFRESH_INTERVAL.toMillis());
            snapshot = fresh;
            return fresh;
        } catch (final RuntimeException ex) {
            log.warn("SiteIndex rebuild failed; serving the previous snapshot", ex);
            // Keep the base either way: with it, an unresolvable org label answers UNKNOWN (a cheap 404)
            // rather than falling through to SHARED and rendering the wrong tenant's site.
            final Snapshot fallback = new Snapshot((snap != null) ? snap.slugs() : Map.of(), base,
                    clock.get() + FAILURE_RETRY.toMillis());
            snapshot = fallback;
            return fallback;
        }
    }

    /** The configured base domain; settings reads fall back to the shipped default rather than throwing. */
    private String baseDomain(final Snapshot previous) {
        try {
            final String base = baseDomainSupplier.get();
            return normalize(base == null ? "" : base);
        } catch (final RuntimeException ex) {
            log.warn("SiteIndex could not read the base-domain setting", ex);
            return previous == null ? "" : previous.baseDomain();
        }
    }

    private record Snapshot(Map<String, Organization.Id> slugs, String baseDomain, long staleAt) {
    }
}
