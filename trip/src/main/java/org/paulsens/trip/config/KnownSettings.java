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

    // --- site identity ---

    public static final SettingDef SITE_ORG_NAME = new SettingDef(
            "site.org.name", Config.Type.STRING, "Center for Peace West", "Organization name",
            "The organization's display name, used on public pages and in notices such as the photo-upload "
                    + "license text.").withOrgOverride();

    public static final SettingDef SITE_ORGSITES_BASE_DOMAIN = new SettingDef(
            "site.orgsites.baseDomain", Config.Type.STRING, "unitetrip.com", "Org sites base domain",
            "The domain organization sites live under: an org with slug 'acme' serves at "
                    + "acme.<this domain>. Its apex and www are the marketing site. Changing this only works "
                    + "when DNS and the certificate already cover the new domain's wildcard.");

    /**
     * Org-EXPLICIT (see {@link SettingDef#isOrgOnly()}): the shared site's property must never collect an
     * organization site's traffic, so an org host emits a tag only when the org set its own id. The tag
     * itself (and its consent banner) is wired by the branding phase; this declares the value and its rule.
     */
    public static final SettingDef SITE_ANALYTICS_ID = new SettingDef(
            "site.analytics.id", Config.Type.STRING, "", "Analytics property id",
            "The web-analytics property (e.g. a Google Analytics measurement id) this site reports to. Blank "
                    + "means no analytics tag at all. An organization's site never inherits this value: it "
                    + "reports only to an id the organization set for itself.").withOrgOnly();

    // --- branding (org sites only; every def is org-ONLY, see the section note) ---

    /**
     * The color palettes the Freya theme build ships: {@code freya-{palette}-{light|dark}} theme jars and
     * {@code layout-{palette}-{light|dark}.css} layout sheets exist for exactly these names, which is why the
     * value is a menu and the save paths refuse anything else. Adding a palette means adding it here AND
     * to the theme build.
     */
    public static final List<String> THEME_PALETTES = List.of(
            "avocado", "blue", "green", "orange", "purple", "red", "turquoise", "yellow");

    public static final SettingDef SITE_THEME_PALETTE = new SettingDef(
            "site.theme.palette", Config.Type.STRING, "", "Color palette",
            "The accent color of the organization's site (menus, buttons, links) in both light and dark "
                    + "mode. Blank means the platform's default look.")
            .withOrgOnly().withChoices(THEME_PALETTES.toArray(String[]::new));

    /**
     * The organization's own light/dark choice, beside the palette because it selects the other half of the
     * same stylesheet pair ({@code freya-{palette}-{light|dark}}). A VISITOR who has worked the topbar's
     * Dark Mode toggle still overrides this for themselves -- that switch is a personal preference and
     * stays one; this is what everybody else sees.
     */
    public static final SettingDef SITE_THEME_DARK = new SettingDef(
            "site.theme.dark", Config.Type.BOOLEAN, "false", "Dark mode",
            "Whether the organization's site is drawn in its palette's DARK theme: dark page background, "
                    + "dark top bar and menu. A visitor who has chosen light or dark for themselves keeps "
                    + "their own choice.").withOrgOnly();

    public static final SettingDef SITE_LOGO_URL = new SettingDef(
            "site.logo.url", Config.Type.STRING, "", "Logo image URL",
            "An http(s) URL of the logo shown in the site's top bar. Blank shows the organization's name as "
                    + "text instead.").withOrgOnly().withHttpUrl();

    public static final SettingDef SITE_FAVICON_URL = new SettingDef(
            "site.favicon.url", Config.Type.STRING, "", "Favicon URL",
            "An http(s) URL of the browser-tab icon. Blank means no icon at all (the browser shows its "
                    + "generic one).").withOrgOnly().withHttpUrl();

    public static final SettingDef SITE_OG_IMAGE_URL = new SettingDef(
            "site.ogImage.url", Config.Type.STRING, "", "Link preview image URL",
            "An http(s) URL of the image chat apps and social sites show when someone shares a link to the "
                    + "site. Blank uses the logo; with no logo either, links preview without a picture.")
            .withOrgOnly().withHttpUrl();

    public static final SettingDef SITE_BACKGROUND_URL = new SettingDef(
            "site.background.url", Config.Type.STRING, "", "Page background image URL",
            "An http(s) URL of an image shown behind the site's pages. Blank means no background image, so "
                    + "the background color below shows instead.")
            .withOrgOnly().withHttpUrl();

    /**
     * Blank means "follow the palette": {@code BrandCommands.getRootStyle} then paints the chosen theme's
     * own {@code --site-bg-default}, a tint of that palette's primary color, so the page behind the cards
     * takes the palette's HUE as well as the light/dark choice without anybody picking a color that only
     * suits one of them. A chosen color is honored as typed. An image, when one is set, covers either: the
     * two are offered as a choice on the Appearance page and saving one clears the other, so the value that
     * is set is the value that shows.
     */
    public static final SettingDef SITE_BACKGROUND_COLOR = new SettingDef(
            "site.background.color", Config.Type.STRING, "", "Page background color",
            "The color shown behind the site's pages when no background image is set, as a hex value such "
                    + "as #333333. Blank means the color palette's own background: a tint of the palette's "
                    + "own color, which follows both the palette and the dark-mode choice on its own.")
            .withOrgOnly().withHexColor();

    public static final SettingDef SITE_FOOTER_TITLE = new SettingDef(
            "site.footer.title", Config.Type.STRING, "", "Footer title",
            "The heading of the page footer. Blank shows the organization's name.").withOrgOnly();

    public static final SettingDef SITE_FOOTER_TEXT = new SettingDef(
            "site.footer.text", Config.Type.STRING, "", "Footer text",
            "A line of plain text under the footer title -- a tagline, a mailing address, a registration "
                    + "number. Blank shows nothing there.").withOrgOnly();

    public static final SettingDef SITE_CONTACT_NAME = new SettingDef(
            "site.contact.name", Config.Type.STRING, "", "Contact name",
            "Who the \"Questions?\" card in the sidebar names. The card's email is the organization "
                    + "profile's contact email; the card is hidden when the name, phone and email are all "
                    + "blank.").withOrgOnly();

    public static final SettingDef SITE_CONTACT_PHONE = new SettingDef(
            "site.contact.phone", Config.Type.STRING, "", "Contact phone",
            "The phone number on the \"Questions?\" card, shown as typed. Blank leaves it off the card.")
            .withOrgOnly();

    public static final SettingDef SITE_DONATE_URL = new SettingDef(
            "site.donate.url", Config.Type.STRING, "", "Donate link URL",
            "An http(s) URL people are sent to by the Donate card and menu entry. Blank means neither is "
                    + "shown.").withOrgOnly().withHttpUrl();

    // --- home page ---

    public static final SettingDef HOME_PHOTOS_WINDOW_DAYS = new SettingDef(
            "home.photos.windowDays", Config.Type.INT, "365", "Pictures from the last (days)",
            "A pilgrimage's photos appear on the home page until this long after the trip ends.")
            .withOrgOverride();

    public static final SettingDef HOME_PHOTOS_MIN_COUNT = new SettingDef(
            "home.photos.minCount", Config.Type.INT, "10", "Minimum pictures per pilgrimage",
            "A pilgrimage needs at least this many publicly-visible photos before its album shows on the "
                    + "home page.").withOrgOverride();

    public static final SettingDef HOME_COUNTDOWN_SOON_DAYS = new SettingDef(
            "home.countdown.soonDays", Config.Type.INT, "60", "Extra countdowns within (days)",
            "The sidebar always counts down to the next pilgrimage of each language; a pilgrimage starting "
                    + "within this many days gets a countdown too, even behind one of the same language.")
            .withOrgOverride();

    // --- content templates ---

    public static final SettingDef CONTENT_VERSIONS_RETAINED = new SettingDef(
            "content.versions.retained", Config.Type.INT, "5", "Content versions kept",
            "How many previous versions of each content template and each page-content item are kept for "
                    + "undo. Lowering it trims history on the next save of each item.");

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

    /**
     * The mail-address slots below share one value shape, resolved by {@code MailAddressCommands}: the
     * sentinel {@code site} means the Site email setting; {@code org} (recipient/reply-to slots that have a
     * trip in hand only) means the owning organization's contact email, falling back to the Site email; any
     * other value is a literal address. The Email addresses section of the Settings page renders these with
     * a mode widget instead of the generic text box.
     */
    private static final String MODE_NOTE = " Value shape: 'site' = the Site email, 'org' = the owning "
            + "organization's contact email (falling back to the Site email), 'facilitators' = the trip's "
            + "facilitators' emails (org-context slots only; falls back like 'org'), anything else = that "
            + "literal address.";

    public static final SettingDef CHAT_MAIL_FROM = new SettingDef(
            "chat.mail.from", Config.Type.STRING, "Trip Chat <no-reply@visitqueenofpeace.com>", "From address",
            "Chat email's From. Must be an address SES is verified to send from, or every chat email fails."
                    + MODE_NOTE);

    public static final SettingDef CHAT_MAIL_REPLY_TO = new SettingDef(
            "chat.mail.replyTo", Config.Type.STRING, "no-reply@visitqueenofpeace.com", "Reply-to address",
            "Where a reply goes. Chat email is not a mailing list, so replies are not delivered to the chat."
                    + MODE_NOTE);

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
                    + "value ends up inside a style attribute, where anything else is CSS injection.")
            .withOrgOverride();

    public static final SettingDef CHAT_BACKGROUND_IMAGE = new SettingDef(
            "chat.background.image", Config.Type.STRING,
            "https://files.visitqueenofpeace.com/images/mary-link.jpg",
            "Default background image",
            "Shown faded behind the messages when neither the trip nor the person has chosen one. Blank means "
                    + "no image at all. Must be an http(s) URL.").withOrgOverride();


    public static final SettingDef CHAT_REACTIONS_PALETTE = new SettingDef(
            "chat.reactions.palette", Config.Type.STRING, "👍,❤️,😂,🙏,"
                    + "📿,🎉,😮,😢,😊,✅",
            "Reaction emoji",
            "Comma-separated, in display order. Matched exactly, so an emoji written with a different "
                    + "variation selector is a different entry. Reactions already stored with a removed emoji "
                    + "stay in the data but stop being offered.").withOrgOverride();

    public static final SettingDef CHAT_PHOTO_COMMENTS_ENABLED = new SettingDef(
            "chat.photoComments.enabled", Config.Type.BOOLEAN, "true", "Photo comments and reactions",
            "Comment threads and emoji reactions on individual chat photos (album viewer, chat lightbox and "
                    + "the public landing page). Turning this off hides the panel and refuses new posts; "
                    + "existing comments are kept.");

    public static final SettingDef CHAT_PHOTO_COMMENT_MAX_CHARS = new SettingDef(
            "chat.photoComments.maxChars", Config.Type.INT, "1000", "Photo comment length",
            "Longest allowed photo comment, in characters. Shorter than a chat message on purpose — the "
                    + "panel beside a photo is a caption-sized space, not a second chat.");

    // --- chat invites ---

    public static final SettingDef CHAT_INVITES_ENABLED = new SettingDef(
            "chat.invites.enabled", Config.Type.BOOLEAN, "true", "Allow invite links",
            "Lets chat participants invite people who are not on the trip — family and friends — by link or "
                    + "QR code. Turning this off refuses new links AND stops existing ones from being redeemed; "
                    + "guests already in a chat stay.");

    public static final SettingDef CHAT_INVITE_EXPIRY_DAYS = new SettingDef(
            "chat.invites.expiryDays", Config.Type.INT, "30", "Invite link lifetime (days)",
            "How long a new invite link works. Whatever this says, a link never outlives the chat itself: "
                    + "expiry is capped at the chat's own archive time.");

    public static final SettingDef CHAT_INVITE_MAX_OUTSTANDING = new SettingDef(
            "chat.invites.maxPerChannel", Config.Type.INT, "10", "Outstanding links per chat",
            "How many unexpired invite links one chat may have at once. Expired and revoked links stop "
                    + "counting on their own.");

    // --- login & security ---

    public static final SettingDef LOGIN_CODE_ENABLED = new SettingDef(
            "login.code.enabled", Config.Type.BOOLEAN, "true", "Offer email login codes",
            "Lets people sign in with a short code emailed to them instead of their password. This is also the "
                    + "forgot-password path, so turning it off leaves \"ask for help\" as the only recovery.");

    public static final SettingDef LOGIN_CODE_LENGTH = new SettingDef(
            "login.code.length", Config.Type.INT, "6", "Code length (digits)",
            "How many digits a login code has. Longer is harder to guess and harder to type; 6 matches what "
                    + "people see everywhere else.");

    public static final SettingDef LOGIN_CODE_TTL_SECONDS = new SettingDef(
            "login.code.ttlSeconds", Config.Type.INT, "300", "Code lifetime (seconds)",
            "How long a code works after it is sent. Slow email delivery eats into this, so setting it very "
                    + "low mostly locks out people with slow mail providers.");

    public static final SettingDef LOGIN_CODE_MAX_SENDS = new SettingDef(
            "login.code.maxSends", Config.Type.INT, "3", "Codes sent per person",
            "How many codes one email address can be sent within the window below. A cap on mail volume, and "
                    + "on using the login page to pester somebody's inbox.");

    public static final SettingDef LOGIN_CODE_SEND_WINDOW_SECONDS = new SettingDef(
            "login.code.sendWindowSeconds", Config.Type.INT, "900", "...within this many seconds",
            "The window the send cap is counted over. Changing it starts a fresh count.");

    public static final SettingDef LOGIN_CODE_MAX_ATTEMPTS = new SettingDef(
            "login.code.maxAttempts", Config.Type.INT, "5", "Guesses per code",
            "How many wrong entries invalidate a code. With 6 digits and 5 guesses in 5 minutes, guessing is "
                    + "not a realistic attack.");

    public static final SettingDef LOGIN_MAIL_FROM = new SettingDef(
            "login.mail.from", Config.Type.STRING, "site",
            "From address",
            "Login-code email's From. Must be an address SES is verified to send from, or login-code email "
                    + "fails." + MODE_NOTE);

    public static final SettingDef LOGIN_MAIL_REPLY_TO = new SettingDef(
            "login.mail.replyTo", Config.Type.STRING, "ken@visitqueenofpeace.com", "Reply-to address",
            "Where a reply to a login-code email goes -- someone stuck at the login page tends to reply to "
                    + "the mail in front of them, so this should be an address a human reads." + MODE_NOTE);

    public static final SettingDef LOGIN_PASSKEY_ENABLED = new SettingDef(
            "login.passkey.enabled", Config.Type.BOOLEAN, "false", "Offer passkeys",
            "Lets people add a passkey (Face ID, fingerprint, or device lock) after signing in, and sign in "
                    + "with it afterwards. Nobody is required to set one up, and the login page looks the same "
                    + "for anyone without one. Leave OFF until a full register-and-sign-in has been verified "
                    + "against the deployed image; existing passkeys survive the switch being off.");

    public static final SettingDef LOGIN_REMEMBER_ENABLED = new SettingDef(
            "login.remember.enabled", Config.Type.BOOLEAN, "true", "Keep people signed in",
            "Gives each (non-admin) sign-in a long-lived browser cookie that quietly signs them back in when "
                    + "their session ends. Turning this off also stops honoring every cookie already out "
                    + "there -- it is the kill switch.");

    public static final SettingDef LOGIN_REMEMBER_DAYS = new SettingDef(
            "login.remember.days", Config.Type.INT, "60", "...for this many days",
            "How long the stay-signed-in cookie lasts, counted from sign-in (using it does not extend it). "
                    + "Signing out or changing the password ends it early.");

    public static final SettingDef API_TOKEN_ENABLED = new SettingDef(
            "api.token.enabled", Config.Type.BOOLEAN, "false", "API bearer tokens",
            "Lets native/mobile clients sign in over the REST API with bearer tokens (docs/api-tokens.md). "
                    + "Off means no issuance, no refresh, AND no acceptance of tokens already out there -- it "
                    + "is the kill switch. Leave OFF until a client ships.");

    public static final SettingDef API_TOKEN_ACCESS_MINUTES = new SettingDef(
            "api.token.access.minutes", Config.Type.INT, "30", "...access token minutes",
            "How long one access token works before the client must silently refresh. Also bounds how stale "
                    + "the role stamped in a token can be, so shorter is safer.");

    public static final SettingDef API_TOKEN_REFRESH_DAYS = new SettingDef(
            "api.token.refresh.days", Config.Type.INT, "60", "...refresh token days",
            "How long a signed-in device stays signed in without re-entering credentials, counted from "
                    + "sign-in (refreshing does not extend it). Signing out or a password change ends it "
                    + "early.");

    public static final SettingDef API_TOKEN_REFRESH_ADMIN_DAYS = new SettingDef(
            "api.token.refresh.admin.days", Config.Type.INT, "7", "...admin refresh days",
            "The refresh lifetime for ADMIN-scoped tokens, deliberately short: an admin token is the most "
                    + "valuable credential this system hands out.");

    public static final SettingDef LOGIN_PASSWORD_MAX_FAILS = new SettingDef(
            "login.password.maxFails", Config.Type.INT, "10", "Wrong passwords per person",
            "After this many wrong passwords for one email address within the window below, sign-in for that "
                    + "address is refused until the window rolls over. 0 disables the throttle.");

    public static final SettingDef LOGIN_PASSWORD_FAIL_WINDOW_SECONDS = new SettingDef(
            "login.password.failWindowSeconds", Config.Type.INT, "900", "...within this many seconds",
            "The window wrong passwords are counted over. Changing it starts a fresh count.");

    // --- registration ---

    public static final SettingDef REG_ALLOW_EDITS = new SettingDef(
            "reg.allowEdits", Config.Type.BOOLEAN, "true", "Registrants may edit their responses",
            "Whether a traveler who has already registered (pending or confirmed) can still change their "
                    + "registration-option answers from the registration page. Off makes the expanded view "
                    + "read-only; registering itself is unaffected.").withOrgOverride();

    // --- registration email ---

    public static final SettingDef REG_MAIL_FROM = new SettingDef(
            "reg.mail.from", Config.Type.STRING, "site",
            "From address",
            "Registrant-facing registration emails (received/approved). Must be an address SES is verified "
                    + "to send from, or every registration email fails." + MODE_NOTE);

    public static final SettingDef REG_MAIL_REPLY_TO = new SettingDef(
            "reg.mail.replyTo", Config.Type.STRING, "org", "Reply-To address",
            "Where a registrant's reply lands. The emails invite questions, so this should be a mailbox "
                    + "somebody reads." + MODE_NOTE);

    public static final SettingDef REG_MAIL_BASE_URL = new SettingDef(
            "reg.mail.baseUrl", Config.Type.STRING, "https://www.visitqueenofpeace.com", "Link base URL",
            "Prefixes the trip/profile links inside registration emails.");

    // --- email addresses (rendered by the Settings page's Email addresses section with a mode widget) ---

    public static final SettingDef SITE_MAIL_EMAIL = new SettingDef(
            "site.mail.email", Config.Type.STRING, "Visit Queen of Peace <no-reply@visitqueenofpeace.com>",
            "Site email",
            "The site's own address: what 'Site email' means for every slot below, and the fallback when an "
                    + "organization has no contact email. Used as a From, so its domain must be one SES is "
                    + "verified to send from.");

    public static final SettingDef REG_NOTIFY_EMAIL = new SettingDef(
            "reg.notify.email", Config.Type.STRING, "facilitators",
            "Registration notice recipient",
            "Who receives the internal registration notices (new/moved/cancelled). Defaults to the trip's "
                    + "facilitators -- the same contacts the registrant is shown." + MODE_NOTE);

    public static final SettingDef REG_NOTIFY_FROM = new SettingDef(
            "reg.notify.from", Config.Type.STRING, "site",
            "Registration notice From",
            "The From on the internal registration notices. Must be SES-verified." + MODE_NOTE);

    public static final SettingDef ACCOUNT_NOTIFY_EMAIL = new SettingDef(
            "account.notify.email", Config.Type.STRING,
            "registration-notifications@centerforpeacewest.com",
            "Account notice recipient",
            "Who receives the internal new-account notices. No organization exists at account creation, so "
                    + "there is no org option here. The default preserves the address the page used to "
                    + "hardcode." + MODE_NOTE);

    public static final SettingDef ACCOUNT_NOTIFY_FROM = new SettingDef(
            "account.notify.from", Config.Type.STRING, "site",
            "Account notice From",
            "The From on the internal new-account notices. Must be SES-verified." + MODE_NOTE);

    public static final SettingDef TX_NOTIFY_EMAIL = new SettingDef(
            "tx.notify.email", Config.Type.STRING, "ken@visitqueenofpeace.com",
            "Transaction notice recipient",
            "Who receives the internal transaction-saved notices. The default preserves the address the "
                    + "page used to hardcode." + MODE_NOTE);

    public static final SettingDef TX_NOTIFY_FROM = new SettingDef(
            "tx.notify.from", Config.Type.STRING, "site",
            "Transaction notice From",
            "The From on the internal transaction-saved notices. Must be SES-verified." + MODE_NOTE);

    public static final SettingDef SUPPORT_MAIL_FROM = new SettingDef(
            "support.mail.from", Config.Type.STRING, "site",
            "Support request From",
            "The From on support-request email to the support channel admins. Must be SES-verified."
                    + MODE_NOTE);

    public static final SettingDef SUPPORT_MAIL_REPLY_TO = new SettingDef(
            "support.mail.replyTo", Config.Type.STRING, "site",
            "Support request Reply-To",
            "Where a reply to a support-request email goes. The recipients are the support channel admins "
                    + "themselves (configured below), so this is rarely load-bearing." + MODE_NOTE);

    // --- support requests ---

    public static final SettingDef SUPPORT_MAIL_ENABLED = new SettingDef(
            "support.mail.enabled", Config.Type.BOOLEAN, "true", "Email support requests to channel admins",
            "A support request (family removal, size-limit increase) posts into the support channel either "
                    + "way; this controls whether the channel's admins are also emailed. Deliberately NOT "
                    + "behind the chat-email master switch: a support request must not be silently swallowed "
                    + "because chat mail is off.");

    // --- family accounts ---

    public static final SettingDef FAMILY_MAX_MEMBERS = new SettingDef(
            "family.maxMembers", Config.Type.INT, "10", "Family size limit",
            "Maximum people in one family account, counting the owner. At the limit the family page stops "
                    + "offering Add and lets the user send a support request instead; raise this here when one "
                    + "arrives. An abuse guard, not a business rule.");

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

    public static final SettingDef PAYMENT_NOTIFY_EMAIL = new SettingDef(
            "payment.notify.email", Config.Type.STRING, "",
            "Payment notification recipient",
            "Site-wide fallback address that receives an internal note for each completed payment. An "
                    + "organization's contact email wins when set; blank here means no internal note.");

    public static final SettingDef PAYMENT_MAIL_FROM = new SettingDef(
            "payment.mail.from", Config.Type.STRING, "",
            "Payment confirmation From address",
            "Site-wide fallback From for payment confirmation email, e.g. 'Site Name <no-reply@site.org>'. "
                    + "The trip's payment settings, then the organization's defaults, win when set.");

    public static final SettingDef PAYMENT_CONFIRM_TEMPLATE = new SettingDef(
            "payment.confirm.templateId", Config.Type.STRING, "payment-confirmation",
            "Payment confirmation template",
            "The MAIL-kind content template used for payment confirmations when neither the trip nor its "
                    + "organization picks one. Installed by install-starter-templates.sh.");

    public static final SettingDef PAYMENT_FEES_PAID_BY = new SettingDef(
            "payment.fees.paidBy", Config.Type.STRING, "ORGANIZATION",
            "Who pays processor fees",
            "ORGANIZATION or PAYER; the bottom rung of the trip/org/site ladder. PAYER adds the trip-portion "
                    + "fee on top of the charge; ORGANIZATION absorbs it. A donation's fee share is always "
                    + "absorbed by the organization either way.");

    // --- profile pictures ---

    public static final SettingDef PROFILE_BG_REMOVAL_ENABLED = new SettingDef(
            "profile.bgRemoval.enabled", Config.Type.BOOLEAN, "false", "Offer background removal",
            "Adds a \"replace background\" option to profile pictures: an on-server ML model (U^2-Net-small "
                    + "via ONNX Runtime) cuts out the person and the chosen color fills in behind them. While "
                    + "in use the model holds a few hundred MB of task memory; it is released again after 10 "
                    + "idle minutes (the next use re-pays a ~1s model load).");

    // --- performance ---

    public static final SettingDef CACHE_NEAR_TTL_SECONDS = new SettingDef(
            "cache.near.ttlSeconds", Config.Type.INT, "86400", "Near-cache TTL (seconds)",
            "Absolute upper bound on how long the in-JVM near-cache may serve an entry. Writes through this "
                    + "instance and broadcast invalidations (scripts, admin clears) update the cache "
                    + "immediately, and the health check below converges everything else, so this bound only "
                    + "matters if both fail. 0 turns the near-cache off. Changes apply on save; a "
                    + "-Dtrip.cache.near.ttlSeconds sysprop pins this against the settings table entirely.");

    public static final SettingDef CACHE_NEAR_CHECK_SECONDS = new SettingDef(
            "cache.near.checkSeconds", Config.Type.INT, "300", "Near-cache health-check interval (seconds)",
            "On a near-cache hit whose last check is older than this, ONE background refresh re-reads the "
                    + "shared cache and updates the entry; the request itself never waits. Since broadcast "
                    + "invalidation covers deliberate out-of-band writes, this is the convergence bound only "
                    + "for ordinary writes from OTHER JVMs (CLI tools with the cache configured, a second "
                    + "instance) and for missed broadcasts.");

    /**
     * The Settings page renders this section with a purpose-built mode widget (org/site/custom + a
     * local-part/domain composer for From addresses) instead of the generic text boxes -- it skips the
     * section in its generic loop BY THIS TITLE, so rename both together.
     */
    public static final String EMAIL_ADDRESSES_SECTION = "Email addresses";

    /** The org-site branding section: every def in it is org-only and read by {@code BrandCommands}. */
    public static final String BRANDING_SECTION = "Branding";

    private static final List<SettingSection> SECTIONS = List.of(
            new SettingSection("Site", null,
                    List.of(SITE_ORG_NAME, SITE_ORGSITES_BASE_DOMAIN, SITE_ANALYTICS_ID)),
            new SettingSection(BRANDING_SECTION,
                    "The look of an ORGANIZATION's own site, set by the organization on its Appearance page. "
                            + "An org site never inherits the shared site's look, and the shared site's "
                            + "own logo, footer and contacts are fixed in its pages -- so a value here on "
                            + "the site Settings page applies to nothing.",
                    List.of(SITE_THEME_PALETTE, SITE_THEME_DARK, SITE_LOGO_URL, SITE_FAVICON_URL,
                            SITE_OG_IMAGE_URL,
                            SITE_BACKGROUND_URL, SITE_BACKGROUND_COLOR, SITE_FOOTER_TITLE, SITE_FOOTER_TEXT,
                            SITE_CONTACT_NAME, SITE_CONTACT_PHONE, SITE_DONATE_URL)),
            new SettingSection(EMAIL_ADDRESSES_SECTION,
                    "Every address the application sends with or to. From addresses must be on an "
                            + "SES-verified domain; recipients and Reply-To may be any address.",
                    List.of(SITE_MAIL_EMAIL,
                            REG_MAIL_FROM, REG_MAIL_REPLY_TO, REG_NOTIFY_EMAIL, REG_NOTIFY_FROM,
                            ACCOUNT_NOTIFY_EMAIL, ACCOUNT_NOTIFY_FROM, TX_NOTIFY_EMAIL, TX_NOTIFY_FROM,
                            LOGIN_MAIL_FROM, LOGIN_MAIL_REPLY_TO, CHAT_MAIL_FROM, CHAT_MAIL_REPLY_TO,
                            SUPPORT_MAIL_FROM, SUPPORT_MAIL_REPLY_TO)),
            new SettingSection("Performance",
                    "The in-JVM near-cache in front of the shared Valkey cache. Only reads whose call site "
                            + "declared Cached.YES (public/render paths) are served from it; auth, payments "
                            + "and editors always bypass it.",
                    List.of(CACHE_NEAR_TTL_SECONDS, CACHE_NEAR_CHECK_SECONDS)),
            new SettingSection("Profile pictures", null,
                    List.of(PROFILE_BG_REMOVAL_ENABLED)),
            new SettingSection("Home page", null,
                    List.of(HOME_BANNER_ENABLED, HOME_BANNER_TEXT,
                            HOME_PHOTOS_WINDOW_DAYS, HOME_PHOTOS_MIN_COUNT, HOME_COUNTDOWN_SOON_DAYS)),
            new SettingSection("Content templates", null,
                    List.of(CONTENT_VERSIONS_RETAINED)),
            new SettingSection("Chat email",
                    "Nothing here sends mail on its own. \"Send chat email\" is the master switch; with it off "
                            + "the rest is inert.",
                    List.of(CHAT_MAIL_ENABLED, CHAT_MAIL_BASE_URL)),
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
            new SettingSection("Chat invites",
                    "Invite links admit people with an account who are not on the trip (family, friends) as "
                            + "chat guests. Guests can be removed like anyone else.",
                    List.of(CHAT_INVITES_ENABLED, CHAT_INVITE_EXPIRY_DAYS, CHAT_INVITE_MAX_OUTSTANDING)),
            new SettingSection("Photo comments",
                    "Comment threads and reactions on individual chat photos. Mention email from photo "
                            + "comments rides the chat-email master switch above.",
                    List.of(CHAT_PHOTO_COMMENTS_ENABLED, CHAT_PHOTO_COMMENT_MAX_CHARS)),
            new SettingSection("Login & security",
                    "Email login codes double as the forgot-password flow. The mail addresses here are used "
                            + "for those codes.",
                    List.of(LOGIN_CODE_ENABLED, LOGIN_CODE_LENGTH, LOGIN_CODE_TTL_SECONDS, LOGIN_CODE_MAX_SENDS,
                            LOGIN_CODE_SEND_WINDOW_SECONDS, LOGIN_CODE_MAX_ATTEMPTS,
                            LOGIN_REMEMBER_ENABLED, LOGIN_REMEMBER_DAYS,
                            LOGIN_PASSKEY_ENABLED, LOGIN_PASSWORD_MAX_FAILS,
                            LOGIN_PASSWORD_FAIL_WINDOW_SECONDS,
                            API_TOKEN_ENABLED, API_TOKEN_ACCESS_MINUTES,
                            API_TOKEN_REFRESH_DAYS, API_TOKEN_REFRESH_ADMIN_DAYS)),
            new SettingSection("Family accounts", null,
                    List.of(FAMILY_MAX_MEMBERS)),
            new SettingSection("Registration", null,
                    List.of(REG_ALLOW_EDITS)),
            new SettingSection("Registration email",
                    "The registrant-facing emails themselves are runtime-editable MAIL templates on the "
                            + "Templates page (registration-received / registration-approved).",
                    List.of(REG_MAIL_BASE_URL)),
            new SettingSection("Support requests",
                    "Who receives them is managed in the \"Support channel admins\" panel below the settings.",
                    List.of(SUPPORT_MAIL_ENABLED)),
            new SettingSection("Payments",
                    "The mail/fee settings are the SITE rung of the trip → organization → site "
                            + "defaults ladder. The return-address allowlist only applies to payments "
                            + "started through the API; the web checkout derives its return address from "
                            + "the page the payer was on and never consults it.",
                    List.of(PAYMENT_NOTIFY_EMAIL, PAYMENT_MAIL_FROM, PAYMENT_CONFIRM_TEMPLATE,
                            PAYMENT_FEES_PAID_BY, PAYMENT_RETURN_URL_PREFIXES)));

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

    /**
     * The settings an organization may override for its own site, in page order -- what the org settings
     * editor renders and the only keys {@code Organization.settingsOverrides} may hold.
     */
    public static List<SettingDef> orgOverridable() {
        return all().stream().filter(SettingDef::isOrgOverridable).toList();
    }

    /** The declaration for a key IF an organization may override it; empty for unknown or site-only keys. */
    public static Optional<SettingDef> findOrgOverridable(final String name) {
        return find(name).filter(SettingDef::isOrgOverridable);
    }

    /**
     * The look-and-feel settings: the {@link #BRANDING_SECTION} section, whole. These are what the org
     * Appearance page edits with purpose-built controls and what {@code BrandCommands} previews, so the
     * section membership -- not a second hand-written list -- decides which page a setting appears on.
     */
    public static List<SettingDef> branding() {
        return SECTIONS.stream().filter(section -> section.getTitle().equals(BRANDING_SECTION))
                .flatMap(section -> section.getSettings().stream()).toList();
    }

    /** @return whether {@code name} is one of the {@link #branding()} settings. */
    public static boolean isBranding(final String name) {
        return branding().stream().anyMatch(def -> def.getName().equals(name));
    }

    /**
     * The org-overridable settings the generic org Settings editor renders: everything except the branding
     * ones, which have their own page. The two lists partition {@link #orgOverridable()} exactly, so no
     * setting is offered twice and none goes missing.
     */
    public static List<SettingDef> orgOverridableNonBranding() {
        return orgOverridable().stream().filter(def -> !isBranding(def.getName())).toList();
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
