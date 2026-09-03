package org.paulsens.trip.content;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToIntFunction;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.site.SiteContext;

/**
 * The page keys of the sites the content engine renders besides the classic shared landing page, and the
 * DEFAULT home page an organization's site starts with.
 *
 * <p>An org's page key carries its UUID, never its slug: a slug is a public label a site admin may rename,
 * and the page must survive that. The starter page is deliberately small ("less is more"): a welcome section
 * whose text is obviously a placeholder, the org's upcoming trips, and its pictures -- enough to show what
 * the page can do and land the org's editors on something that works, with nothing they would have to
 * delete first. Instance ids are minted UUIDs: the shared page's human-readable ids ({@code events},
 * {@code docs}) double as child-section keys and page anchors, so a second page could never reuse them.
 *
 * <p>Seeding runs through {@code OrgCommands.ensureHomePage} (the normal DAO save path, versions assigned
 * on write) exactly once per org -- the org row records when -- so an org that later empties its page is
 * never re-seeded behind its back.
 */
public final class OrgPageBootstrap {

    /**
     * The product's own (marketing) host page. Its sections are seeded by {@code MarketingPageBootstrap}
     * (the {@code bootstrap-marketing-page.sh} script in production, {@code FakeData} locally) and edited in
     * place from then on; until the script has run, the host shows a plain "coming soon" notice.
     */
    public static final String MARKETING_PAGE_KEY = "page:unitetrip-home";

    private static final String ORG_PAGE_PREFIX = "page:org:";
    private static final String ORG_PAGE_SUFFIX = ":home";
    static final String SEED_AUTHOR = "org-site-bootstrap";

    private OrgPageBootstrap() {
    }

    /** The section key of an organization's home page: {@code page:org:{uuid}:home}. */
    public static String pageKey(final Organization.Id orgId) {
        return ORG_PAGE_PREFIX + orgId.getValue() + ORG_PAGE_SUFFIX;
    }

    /**
     * The home-page section key of a SITE: the org's own page on its host, the marketing page on the
     * product host, the classic shared page ({@code V2PageBootstrap.PAGE_KEY}) everywhere else -- the one
     * mapping {@code SiteCommands.getPageKey} and {@code ListingScope}'s curation read both use.
     */
    public static String pageKeyOf(final SiteContext site) {
        // The marketing key first: the platform organization's site is an org site too, but its page is
        // the script-seeded marketing page, keyed independently of whichever org row holds the slug.
        if (site.isMarketing()) {
            return MARKETING_PAGE_KEY;
        }
        return site.isOrg() ? pageKey(site.orgId()) : V2PageBootstrap.PAGE_KEY;
    }

    /** The org an org-page key names, or null for any other key (shared page, marketing page, containers). */
    public static Organization.Id orgOf(final String pageKey) {
        if (pageKey == null || !pageKey.startsWith(ORG_PAGE_PREFIX) || !pageKey.endsWith(ORG_PAGE_SUFFIX)) {
            return null;
        }
        final String id = pageKey.substring(ORG_PAGE_PREFIX.length(),
                pageKey.length() - ORG_PAGE_SUFFIX.length());
        return id.isBlank() ? null : Organization.Id.from(id);
    }

    /**
     * The starter rows for {@code org}'s home page, unsaved (version 0; the save path assigns v1), each
     * pinned to the CURRENT version of its starter template as {@code templateVersion} reports it -- a
     * template edited since installation must not leave new pages rendering its first draft.
     */
    public static List<ContentInstance> rows(final Organization org,
            final ToIntFunction<String> templateVersion) {
        final String key = pageKey(org.getId());
        return List.of(
                row(key, "Welcome", StarterTemplates.TEXT_ONLY_ID, templateVersion, 0,
                        Map.of("body", welcomeBody(org.getName()))),
                row(key, "Upcoming Trips", StarterTemplates.PILGRIMAGES_ID, templateVersion, 1,
                        Map.of("language", "English", "cfpwOnly", "false")),
                row(key, "Pictures", StarterTemplates.PHOTO_ALBUMS_ID, templateVersion, 2, Map.of()));
    }

    /** The welcome section: the org's name as the page heading, then text that is plainly a placeholder. */
    static String welcomeBody(final String orgName) {
        return "<div style=\"text-align:center\"><h1 style=\"margin-bottom:0px;\">Welcome to "
                + escape(orgName) + "</h1></div>"
                + "<p>This is your organization's home page. Replace this introduction with a few words about"
                + " who you are and what you offer. Open the page's edit mode to change any section, add new"
                + " ones, or remove the ones you do not need.</p>";
    }

    private static ContentInstance row(final String section, final String title, final String templateId,
            final ToIntFunction<String> templateVersion, final int position, final Map<String, String> values) {
        return new ContentInstance(UUID.randomUUID().toString(), section, title, templateId,
                templateVersion.applyAsInt(templateId), new HashMap<>(values), null, position, 0, null,
                SEED_AUTHOR);
    }

    /** Minimal HTML escaping for the one authored-by-us string that embeds user data (the org name). */
    static String escape(final String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
