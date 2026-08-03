package org.paulsens.trip.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.paulsens.trip.model.Config;
import org.paulsens.trip.model.SettingDef;
import org.paulsens.trip.model.SettingSection;

/**
 * Every runtime setting the application reads, declared once.
 *
 * <p>This is the registry the admin Settings page renders and the code reads through, so the two cannot drift.
 * A setting that is not here is not read by anything -- the page still shows such rows, under "Other settings",
 * precisely so a row written by hand or left over from an earlier version stays visible and editable rather
 * than becoming invisible the moment this class exists.
 *
 * <p><b>Adding a setting means adding it here and reading it through the constant</b>, never
 * {@code config.getInt("some.key", 3)} at a call site. The literal-plus-default form is what this replaces: it
 * put the default in one place and the key in another, so the admin page could offer a key nothing read, and a
 * misspelling looked exactly like a working setting.
 *
 * <p>Deployment identity -- account, VPC, endpoints, DNS -- deliberately does not belong here; that is resolved
 * at CDK synth time from {@code deploy-config.json}, long before this table is readable. This is for values a
 * human changes while the site is running.
 */
public final class KnownSettings {

    // --- home page ---

    public static final SettingDef HOME_BANNER_ENABLED = new SettingDef(
            "home.banner.enabled", Config.Type.BOOLEAN, "false", "Show the banner",
            "Shows a notice at the top of the public home page. The banner also needs text below.");

    public static final SettingDef HOME_BANNER_TEXT = new SettingDef(
            "home.banner.text", Config.Type.STRING, "", "Banner text",
            "The notice itself. Blank hides the banner even when it is enabled.");

    // --- chat email ---

    public static final SettingDef CHAT_MAIL_ENABLED = new SettingDef(
            "chat.mail.enabled", Config.Type.BOOLEAN, "false", "Send chat email",
            "The master switch for every chat email, including mentions and the daily digest. Off means chat "
                    + "sends no mail at all, whatever anyone's own preferences say.");

    public static final SettingDef CHAT_MAIL_FROM = new SettingDef(
            "chat.mail.from", Config.Type.STRING, "Trip Chat <no-reply@visitqueenofpeace.com>", "From address",
            "Must be an address SES is verified to send from, or every chat email fails.");

    public static final SettingDef CHAT_MAIL_REPLY_TO = new SettingDef(
            "chat.mail.replyTo", Config.Type.STRING, "no-reply@visitqueenofpeace.com", "Reply-to address",
            "Where a reply goes. Chat email is not a mailing list, so replies are not delivered to the chat.");

    public static final SettingDef CHAT_MAIL_BASE_URL = new SettingDef(
            "chat.mail.baseUrl", Config.Type.STRING, "https://my.centermirmedjugorje.com", "Site address in links",
            "Prefixes the links in chat email. Getting this wrong sends people to the wrong site, or nowhere.");

    // --- chat digest ---

    public static final SettingDef CHAT_DIGEST_ENABLED = new SettingDef(
            "chat.digest.enabled", Config.Type.BOOLEAN, "false", "Send the daily digest",
            "Runs the once-a-day summary. Each person still has to opt in for themselves, and nobody with "
                    + "nothing new is emailed.");

    public static final SettingDef CHAT_DIGEST_HOUR = new SettingDef(
            "chat.digest.hour", Config.Type.INT, "8", "Hour of day to send",
            "0-23, in the timezone below. Values outside that range are clamped rather than refused.");

    public static final SettingDef CHAT_DIGEST_ZONE = new SettingDef(
            "chat.digest.zone", Config.Type.STRING, "America/Los_Angeles", "Timezone",
            "An IANA zone id, such as America/Los_Angeles. An unrecognised zone falls back to the default.");

    // --- chat limits ---

    public static final SettingDef CHAT_GLOBAL_LIMIT = new SettingDef(
            "chat.global.limit", Config.Type.INT, "200", "Messages per person, all chats",
            "The ceiling across every chat a person is in, on top of each chat's own burst and sustained "
                    + "limits. Raising it does not loosen the per-chat limits.");

    public static final SettingDef CHAT_GLOBAL_WINDOW_SECONDS = new SettingDef(
            "chat.global.windowSeconds", Config.Type.INT, "300", "...within this many seconds",
            "The window the limit above is counted over. Changing it starts a fresh count rather than "
                    + "reinterpreting the one in progress.");

    public static final SettingDef CHAT_EDIT_WINDOW_MINUTES = new SettingDef(
            "chat.edit.windowMinutes", Config.Type.INT, "15", "Author edit window (minutes)",
            "How long someone may correct their own message. Short on purpose: an edit rewrites what others "
                    + "have already read, so this is for fixing a typo, not revising history.");

    // --- chat moderation ---

    public static final SettingDef CHAT_AUTO_MUTE_TRIGGER_COUNT = new SettingDef(
            "chat.autoMute.triggerCount", Config.Type.INT, "3", "Rate-limit hits before an automatic mute",
            "How many times someone may hit a limit before being muted automatically.");

    public static final SettingDef CHAT_AUTO_MUTE_TRIGGER_WINDOW_SECONDS = new SettingDef(
            "chat.autoMute.triggerWindowSeconds", Config.Type.INT, "600", "...within this many seconds",
            "The window those hits are counted over.");

    public static final SettingDef CHAT_AUTO_MUTE_LADDER_MINUTES = new SettingDef(
            "chat.autoMute.ladderMinutes", Config.Type.STRING, "5,30,1440", "Mute lengths, in minutes",
            "Comma-separated, escalating: the first automatic mute uses the first value, the next the second, "
                    + "and so on, with the last repeating. Malformed lists fall back to the default.");

    public static final SettingDef CHAT_AUTO_MUTE_TIER_DECAY_HOURS = new SettingDef(
            "chat.autoMute.tierDecayHours", Config.Type.INT, "24", "Escalation forgotten after (hours)",
            "How long good behaviour takes to drop someone back down the ladder above.");

    public static final SettingDef CHAT_ALARM_DEDUPE_WINDOW_SECONDS = new SettingDef(
            "chat.alarm.dedupeWindowSeconds", Config.Type.INT, "300", "Alarm records at most one per (seconds)",
            "Abuse alarms go to the audit trail, which is append-only and never expires, so they are deduped "
                    + "per person per chat over this window. Lowering it lets a script fill the trail.");

    // --- chat appearance ---

    public static final SettingDef CHAT_BACKGROUND_COLORS = new SettingDef(
            "chat.background.colors", Config.Type.STRING,
            "#eef2f7,#f5efe6,#eaf3ec,#f3eaf3,#fdf4e3,#e8eef5,#ffffff",
            "Background colours offered",
            "Comma-separated, in the order they are offered. People pick from this list rather than typing a "
                    + "colour, so an entry that is not a plain #rrggbb or a CSS colour keyword is dropped -- the "
                    + "value ends up inside a style attribute, where anything else is CSS injection.");

    public static final SettingDef CHAT_BACKGROUND_IMAGE = new SettingDef(
            "chat.background.image", Config.Type.STRING,
            "https://files.visitqueenofpeace.com/images/mary-link.jpg",
            "Default background image",
            "Shown faded behind the messages when neither the trip nor the person has chosen one. Blank means "
                    + "no image at all. Must be an http(s) URL.");


    public static final SettingDef CHAT_REACTIONS_PALETTE = new SettingDef(
            "chat.reactions.palette", Config.Type.STRING, "👍,❤️,😂,🙏,"
                    + "📿,🎉,😮,😢,😊,✅",
            "Reaction emoji",
            "Comma-separated, in display order. Matched exactly, so an emoji written with a different "
                    + "variation selector is a different entry. Reactions already stored with a removed emoji "
                    + "stay in the data but stop being offered.");

    // --- payments ---

    public static final SettingDef PAYMENT_RETURN_URL_PREFIXES = new SettingDef(
            "payment.returnUrl.allowedPrefixes", Config.Type.STRING,
            "https://my.centermirmedjugorje.com,https://www.visitqueenofpeace.com,https://visitqueenofpeace.com",
            "Allowed payment return addresses",
            "Comma-separated. A mobile app supplies its own return and cancel address for a PayPal payment, and "
                    + "anything not starting with one of these is refused. Without the check the payment "
                    + "endpoint is an open redirect: an attacker borrows our PayPal flow to make their own page "
                    + "look like part of this site. Add an app's custom scheme (e.g. trip://) here before "
                    + "shipping it, not after.");

    private static final List<SettingSection> SECTIONS = List.of(
            new SettingSection("Home page", null,
                    List.of(HOME_BANNER_ENABLED, HOME_BANNER_TEXT)),
            new SettingSection("Chat email",
                    "Nothing here sends mail on its own. \"Send chat email\" is the master switch; with it off "
                            + "the rest is inert.",
                    List.of(CHAT_MAIL_ENABLED, CHAT_MAIL_FROM, CHAT_MAIL_REPLY_TO, CHAT_MAIL_BASE_URL)),
            new SettingSection("Chat daily digest", null,
                    List.of(CHAT_DIGEST_ENABLED, CHAT_DIGEST_HOUR, CHAT_DIGEST_ZONE)),
            new SettingSection("Chat limits",
                    "Per-chat burst and sustained limits live on each trip's own Chat settings page. These are "
                            + "the site-wide ones.",
                    List.of(CHAT_GLOBAL_LIMIT, CHAT_GLOBAL_WINDOW_SECONDS, CHAT_EDIT_WINDOW_MINUTES)),
            new SettingSection("Chat moderation", null,
                    List.of(CHAT_AUTO_MUTE_TRIGGER_COUNT, CHAT_AUTO_MUTE_TRIGGER_WINDOW_SECONDS,
                            CHAT_AUTO_MUTE_LADDER_MINUTES, CHAT_AUTO_MUTE_TIER_DECAY_HOURS,
                            CHAT_ALARM_DEDUPE_WINDOW_SECONDS)),
            new SettingSection("Chat appearance", null,
                    List.of(CHAT_REACTIONS_PALETTE, CHAT_BACKGROUND_COLORS, CHAT_BACKGROUND_IMAGE)),
            new SettingSection("Payments",
                    "Only applies to payments started through the API. The web checkout derives its return "
                            + "address from the page the payer was on and never consults this.",
                    List.of(PAYMENT_RETURN_URL_PREFIXES)));

    private static final Map<String, SettingDef> BY_NAME = index();

    private KnownSettings() {
    }

    /** The registry grouped for display, in the order the page shows it. */
    public static List<SettingSection> sections() {
        return SECTIONS;
    }

    /** Every declared setting, flattened. */
    public static List<SettingDef> all() {
        return SECTIONS.stream().flatMap(section -> section.getSettings().stream()).toList();
    }

    /** @return whether this key is one the code actually reads. */
    public static boolean isKnown(final String name) {
        // Null-guarded because BY_NAME is an immutable map, and containsKey(null) on one throws rather than
        // returning false. The caller here is a filter over stored rows, where a null name is possible.
        return name != null && BY_NAME.containsKey(name);
    }

    /** The declaration for a key, or empty when nothing declares it. */
    public static Optional<SettingDef> find(final String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(BY_NAME.get(name));
    }

    private static Map<String, SettingDef> index() {
        final Map<String, SettingDef> byName = new LinkedHashMap<>();
        for (final SettingSection section : SECTIONS) {
            for (final SettingDef def : section.getSettings()) {
                // A duplicate key would silently shadow one of the two on the page while both call sites kept
                // reading the same row -- so fail at class-load, not in production.
                if (byName.put(def.getName(), def) != null) {
                    throw new IllegalStateException("Duplicate setting declared: " + def.getName());
                }
            }
        }
        return Map.copyOf(byName);
    }
}
