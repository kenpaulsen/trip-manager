package org.paulsens.trip.site;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.SettingDef;
import org.paulsens.trip.model.Trip;

/**
 * The public base URL that links ABOUT an organization should carry -- in registration and chat email, in
 * an org invite -- derived, never stored: an org with a subdomain site is addressed at
 * {@code https://{slug}.{org-site base domain}}, and every other org at the site-wide setting the caller
 * names ({@code reg.mail.baseUrl}, {@code chat.mail.baseUrl}). The stored setting is thus the shared-site
 * rung only; a tier-2 custom domain will become a third source here without touching any sender.
 *
 * <p>Every entry point takes the organization (or the entity it hangs off) EXPLICITLY. The senders that need
 * these run under the system request context -- the digest scheduler, notification fan-out -- where no host
 * is bound, and a mail link must name the site the recipient uses, not the site the sender happened to be
 * on. The production shape is answered even on a laptop: mail is not sent in local mode, and an admin-page
 * link is a different concern ({@code SiteCommands.orgSiteUrl} answers {@code *.localhost} there).
 */
@Slf4j
public final class SiteUrls {

    private SiteUrls() {
    }

    /**
     * The base URL (no trailing slash) for links about {@code org}: its own site when it has a subdomain,
     * else the value of {@code siteSetting}. A null org is the site rung.
     */
    public static String baseUrl(final Organization org, final SettingDef siteSetting, final ConfigCommands config) {
        final String slug = (org == null) ? null : org.getSlug();
        if (slug != null && !slug.isBlank()) {
            return orgSiteUrl(slug, config);
        }
        return withoutTrailingSlash(config.getString(siteSetting));
    }

    /** {@link #baseUrl} for an org id (blank = site rung); an unreadable org row degrades to the site rung. */
    public static String baseUrlForOrgId(final String orgId, final SettingDef siteSetting,
            final ConfigCommands config) {
        return baseUrlForOrgId(orgId, siteSetting, config, id -> DAO.getInstance().getOrganization(id, Cached.YES));
    }

    /** {@link #baseUrlForOrgId(String, SettingDef, ConfigCommands)} against an explicit lookup (the testable half). */
    static String baseUrlForOrgId(final String orgId, final SettingDef siteSetting, final ConfigCommands config,
            final Function<Organization.Id, Optional<Organization>> lookup) {
        return baseUrl(orgOf(orgId, lookup), siteSetting, config);
    }

    /** {@link #baseUrl} for the organization owning {@code trip} (null or org-less trip = site rung). */
    public static String baseUrlForTrip(final Trip trip, final SettingDef siteSetting, final ConfigCommands config) {
        return baseUrlForOrgId(trip == null ? null : trip.getOrgId(), siteSetting, config);
    }

    /**
     * {@code https://{slug}.{base}} -- the production address of an org's site. The base domain falls back
     * to its compiled default when the setting cannot be read (a mocked config in a sender's test, an
     * outage): a mail link with a null host is worse than one on the shipped domain.
     */
    public static String orgSiteUrl(final String slug, final ConfigCommands config) {
        String base = config.getString(KnownSettings.SITE_ORGSITES_BASE_DOMAIN);
        if (base == null || base.isBlank()) {
            base = KnownSettings.SITE_ORGSITES_BASE_DOMAIN.getDefaultValue();
        }
        return "https://" + slug.trim().toLowerCase(Locale.ROOT) + "." + base.trim().toLowerCase(Locale.ROOT);
    }

    /** The host of a base URL ({@code acme.unitetrip.com} from {@code https://acme.unitetrip.com}), for mail copy. */
    public static String hostOf(final String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        try {
            final String host = new URI(baseUrl.trim()).getHost();
            return host == null ? baseUrl.trim() : host;
        } catch (final URISyntaxException ex) {
            return baseUrl.trim();
        }
    }

    private static String withoutTrailingSlash(final String url) {
        if (url == null) {
            return "";
        }
        final String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.replaceAll("/+$", "") : trimmed;
    }

    private static Organization orgOf(final String orgId,
            final Function<Organization.Id, Optional<Organization>> lookup) {
        if (orgId == null || orgId.isBlank()) {
            return null;
        }
        try {
            return lookup.apply(Organization.Id.from(orgId.trim())).orElse(null);
        } catch (final RuntimeException ex) {
            log.warn("Unable to read organization {} for a site URL; using the site-wide address", orgId, ex);
            return null;
        }
    }
}
