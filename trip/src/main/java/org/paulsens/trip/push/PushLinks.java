package org.paulsens.trip.push;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.paulsens.trip.action.BrandCommands;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.SettingDef;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.site.SiteUrls;

/**
 * The links a push carries: the app deep links ({@code unitetrip://...}), the website URLs (absolute, on the
 * site the trip lives on -- the mail route's rule, {@code SiteUrls.baseUrlForTrip}), and the web
 * notification icon (the org's logo when it has one, else the UniteTrip logo).
 */
public final class PushLinks {

    static final String SCHEME = "unitetrip://";
    static final String SITE_LOGO = "/resources/images/UniteTripLogo.png";

    private PushLinks() {
    }

    static String chatLink(final String tripId) {
        return SCHEME + "chat/" + safe(tripId);
    }

    static String photosLink(final String tripId) {
        return SCHEME + "trip/" + safe(tripId) + "/photos";
    }

    static String paymentsLink(final String tripId) {
        return SCHEME + "trip/" + safe(tripId) + "/payments";
    }

    static String chatUrl(final Trip trip, final String tripId, final ConfigCommands config) {
        return base(trip, config) + "/trip/chat.jsf?trip=" + safe(tripId);
    }

    static String photoUrl(final Trip trip, final String tripId, final String photoKey, final ConfigCommands config) {
        return base(trip, config) + "/trip/tripMedia.jsf?trip=" + safe(tripId)
                + "&photo=" + URLEncoder.encode(photoKey == null ? "" : photoKey, StandardCharsets.UTF_8);
    }

    static String tripUrl(final Trip trip, final SettingDef siteSetting, final ConfigCommands config) {
        return SiteUrls.baseUrlForTrip(trip, siteSetting, config) + "/trip/tripDetails.jsf?trip="
                + safe(trip == null ? null : trip.getId());
    }

    static String paymentsUrl(final Trip trip, final String tripId, final ConfigCommands config) {
        return base(trip, config) + "/trip/pay.jsf?trip=" + safe(tripId);
    }

    static String supportUrl(final ConfigCommands config) {
        return base(null, config) + "/admin/support.jsf";
    }

    /** The site a trip's links belong on (its org's site, else the shared site setting). */
    static String base(final Trip trip, final ConfigCommands config) {
        return SiteUrls.baseUrlForTrip(trip, org.paulsens.trip.config.KnownSettings.CHAT_MAIL_BASE_URL, config);
    }

    /**
     * The web notification icon: the owning organization's logo when it has one (made absolute against
     * the trip's site when stored context-relative), else the UniteTrip logo on that site.
     */
    static String iconFor(final Trip trip, final ConfigCommands config) {
        final String base = base(trip, config);
        final String logo = orgLogo(trip);
        if (logo == null) {
            return base + SITE_LOGO;
        }
        return logo.startsWith("/") ? base + logo : logo;
    }

    private static String orgLogo(final Trip trip) {
        if (trip == null || trip.getOrgId() == null || trip.getOrgId().isBlank()) {
            return null;
        }
        final Organization org = DAO.getInstance()
                .getOrganization(Organization.Id.from(trip.getOrgId()), Cached.YES).orElse(null);
        if (org == null) {
            return null;
        }
        final String logo = new BrandCommands().lookOf(org).logoUrl();
        return logo == null || logo.isBlank() ? null : logo;
    }

    /** The image base for a chat photo key: the CDN when photos are remote, else the local servlet. */
    public static String imageUrl(final String key, final Trip trip, final ConfigCommands config) {
        if (key == null || key.isBlank()) {
            return null;
        }
        final String publicBase = org.paulsens.trip.action.ChatPhotos.getChatPhotos().getPublicBase();
        return (publicBase != null ? publicBase : base(trip, config) + "/chat-photos/") + key;
    }

    private static String safe(final String id) {
        return id == null ? "" : id;
    }
}
