package org.paulsens.trip.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.model.SettingDef;

/**
 * The tabs of an organization's settings page ({@code admin/orgConfig.xhtml}) and which org-overridable setting
 * each one shows. The tabs are grouped by what an admin is trying to do (who we are, how the site looks, the
 * home page, trips and money, chat, email), not by where a value is stored -- the old Profile / Settings /
 * Appearance split followed storage, which is how contact details ended up on two pages.
 *
 * <p>Every org-overridable setting appears exactly once: either as a generic row on one tab ({@link
 * #defsFor}) or among the Appearance tab's purpose-built controls ({@link #appearanceDefs}). {@code
 * OrgSettingsTabsTest} fails when a new org-overridable setting lands on neither, so nothing an org can
 * override goes uneditable.
 *
 * <p>A tab is addressed by its KEY ({@code ?tab=chat}), never by position, so links survive a reorder.
 */
public final class OrgSettingsTabs {

    public static final String PROFILE = "profile";
    public static final String APPEARANCE = "appearance";
    public static final String HOME = "home";
    public static final String TRIPS = "trips";
    public static final String CHAT = "chat";
    public static final String EMAIL = "email";
    public static final String PRIVILEGES = "privileges";

    /** The tab keys in page order; a {@code p:tabView}'s activeIndex is a position in this list. */
    public static final List<String> KEYS = List.of(PROFILE, APPEARANCE, HOME, TRIPS, CHAT, EMAIL, PRIVILEGES);

    /**
     * The branding settings that moved OFF the Appearance tab onto a tab about what they mean: the contact
     * name and phone sit with the contact email on Profile, and the donate link with the rest of the home page.
     */
    static final List<SettingDef> MOVED_OFF_APPEARANCE = List.of(
            KnownSettings.SITE_CONTACT_NAME, KnownSettings.SITE_CONTACT_PHONE, KnownSettings.SITE_DONATE_URL);

    private static final Map<String, List<SettingDef>> GENERIC_ROWS = Map.of(
            PROFILE, List.of(KnownSettings.SITE_CONTACT_NAME, KnownSettings.SITE_CONTACT_PHONE),
            HOME, List.of(KnownSettings.HOME_PHOTOS_WINDOW_DAYS, KnownSettings.HOME_PHOTOS_MIN_COUNT,
                    KnownSettings.HOME_COUNTDOWN_SOON_DAYS, KnownSettings.SITE_DONATE_URL,
                    KnownSettings.SITE_ANALYTICS_ID),
            TRIPS, List.of(KnownSettings.REG_ALLOW_EDITS, KnownSettings.LODGING_AUTO_RECOMPUTE,
                    KnownSettings.FINANCE_TREASURER_EMAIL),
            CHAT, List.of(KnownSettings.CHAT_BACKGROUND_COLORS, KnownSettings.CHAT_BACKGROUND_IMAGE,
                    KnownSettings.CHAT_REACTIONS_PALETTE));

    private OrgSettingsTabs() {
    }

    /** The position of a tab key; a blank or unknown key (a stale link) opens the first tab. */
    public static int indexOf(final String key) {
        final int index = (key == null) ? -1 : KEYS.indexOf(key.trim());
        return Math.max(index, 0);
    }

    /** The generic setting rows a tab shows, in page order; empty for a tab with only purpose-built controls. */
    public static List<SettingDef> defsFor(final String key) {
        return (key == null) ? List.of() : GENERIC_ROWS.getOrDefault(key.trim(), List.of());
    }

    /** The settings the Appearance tab edits and saves: the branding section minus {@link #MOVED_OFF_APPEARANCE}. */
    public static List<SettingDef> appearanceDefs() {
        return KnownSettings.branding().stream().filter(def -> !MOVED_OFF_APPEARANCE.contains(def)).toList();
    }

    /**
     * The entries of the page's one shared edit map that belong to this tab -- what its Save hands to {@code
     * OrgCommands.saveOrgSettings}, which writes only the keys it is given. That is what makes a per-tab Save
     * safe: an unsaved edit on another tab is never written by this one.
     */
    public static Map<String, String> valuesFor(final String key, final Map<String, String> values) {
        final Map<String, String> out = new LinkedHashMap<>();
        if (values == null) {
            return out;
        }
        for (final SettingDef def : defsFor(key)) {
            if (values.containsKey(def.getName())) {
                out.put(def.getName(), values.get(def.getName()));
            }
        }
        return out;
    }
}
