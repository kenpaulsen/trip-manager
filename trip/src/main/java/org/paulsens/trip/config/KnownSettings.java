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
 *
 * <p>A setting's label and description, and a section's heading and note, are not written here: they are the
 * {@code labels.setting.{name}.label} / {@code .description} and {@code labels.settingSection.{id}.title} /
 * {@code .note} lines of {@code i18n/labels.properties} ({@code docs/i18n.md}), read in the reader's language.
 * Adding a setting means adding those two lines too, in every language ({@code LabelsBundleTest}).
 */
public final class KnownSettings {

    // --- site identity ---

    public static final SettingDef SITE_ORG_NAME = new SettingDef(
            "site.org.name", Config.Type.STRING, "Center for Peace West");

    public static final SettingDef SITE_ORGSITES_BASE_DOMAIN = new SettingDef(
            "site.orgsites.baseDomain", Config.Type.STRING, "unitetrip.com");

    /**
     * The sending domains EVERY organization may use. Any other SES-verified domain belongs to the one
     * organization whose custom domain it is ({@code Organization.customDomain}) and is offered to nobody else; a
     * verified domain that is neither shared nor anyone's custom domain is offered to nobody (deny by default,
     * so verifying a customer's domain in SES never lends it to every tenant). Also the domains no organization
     * may claim as its custom domain. Comma-separated.
     */
    public static final SettingDef MAIL_SHARED_DOMAINS = new SettingDef(
            "mail.sharedDomains", Config.Type.STRING, "unitetrip.com, visitqueenofpeace.com");

    /**
     * The tags a trip editor may put on a trip (issue 154): ONE platform-wide list, so a visitor filtering trips
     * across organizations sees one vocabulary. Comma-separated labels; a trip stores each tag's id, the label's
     * lowercase slug ({@code TripTags}), so a label's capitalization can change without touching any trip.
     * Starter list confirmed by the owner 2026-11-16.
     */
    public static final SettingDef TRIP_TAGS = new SettingDef(
            "trip.tags", Config.Type.STRING,
            "Pilgrimage, Retreat, Conference, Mission, Youth, Family, Study, Service, Tour");

    /**
     * Org-EXPLICIT (see {@link SettingDef#isOrgOnly()}): the shared site's property must never collect an
     * organization site's traffic, so an org host emits a tag only when the org set its own id. The SITE's own
     * value is the shared hosts' id ({@code BrandCommands.getPageAnalyticsId}); blank there means no tag on them.
     */
    public static final SettingDef SITE_ANALYTICS_ID = new SettingDef(
            "site.analytics.id", Config.Type.STRING, "")
            .withOrgOnly().withAnalyticsId();

    /**
     * The languages a site offers, as {@code Language} tags ({@code docs/i18n.md}). Ships as English alone so the
     * code for a new language deploys dark: a language is switched on, per site, only once a native speaker has
     * reviewed it. Read through {@code i18n/SiteLanguages}, never split by hand: that parser is what guarantees a
     * typo cannot leave a site with no language at all.
     */
    public static final SettingDef SITE_LANGUAGES = new SettingDef(
            "site.languages", Config.Type.STRING, "en")
            .withOrgOverride();

    /**
     * The first native-app version that ships its own translations. An older build sends the PHONE's language in
     * {@code Accept-Language} by default while its screens are English only, so answering it in Spanish would mix
     * two languages on one screen; below this version (and while this is blank) the apps are answered in the
     * site's default language. Browsers are never gated: their pages and their API calls change together.
     */
    public static final SettingDef API_LANGUAGES_MIN_APP_VERSION = new SettingDef(
            "api.languages.minAppVersion", Config.Type.STRING, "");

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
            "site.theme.palette", Config.Type.STRING, "")
            .withOrgOnly().withChoices(THEME_PALETTES.toArray(String[]::new));

    /**
     * The organization's own light/dark choice, beside the palette because it selects the other half of the
     * same stylesheet pair ({@code freya-{palette}-{light|dark}}). A VISITOR who has worked the topbar's
     * Dark Mode toggle still overrides this for themselves -- that switch is a personal preference and
     * stays one; this is what everybody else sees.
     */
    public static final SettingDef SITE_THEME_DARK = new SettingDef(
            "site.theme.dark", Config.Type.BOOLEAN, "false").withOrgOnly();

    /** The stored value of {@link #SITE_LAYOUT} that keeps the content card and the sidebar. */
    public static final String LAYOUT_CLASSIC = "classic";
    /** The stored value of {@link #SITE_LAYOUT} that lets the home page's bands span the whole window. */
    public static final String LAYOUT_FULL_WIDTH = "full-width";

    /**
     * How the organization's HOME page is laid out. The product's own marketing host is always full-width
     * ({@code BrandCommands.isFullWidth}); an organization opts in here. Only the home page changes --
     * every other page keeps the card-and-sidebar layout, which is what its forms and tables are built for.
     */
    public static final SettingDef SITE_LAYOUT = new SettingDef(
            "site.layout", Config.Type.STRING, "")
            .withOrgOnly().withChoices(LAYOUT_CLASSIC, LAYOUT_FULL_WIDTH);

    public static final SettingDef SITE_LOGO_URL = new SettingDef(
            "site.logo.url", Config.Type.STRING, "").withOrgOnly().withHttpUrl();

    public static final SettingDef SITE_FAVICON_URL = new SettingDef(
            "site.favicon.url", Config.Type.STRING, "").withOrgOnly().withHttpUrl();

    public static final SettingDef SITE_OG_IMAGE_URL = new SettingDef(
            "site.ogImage.url", Config.Type.STRING, "")
            .withOrgOnly().withHttpUrl();

    public static final SettingDef SITE_BACKGROUND_URL = new SettingDef(
            "site.background.url", Config.Type.STRING, "")
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
            "site.background.color", Config.Type.STRING, "")
            .withOrgOnly().withHexColor();

    public static final SettingDef SITE_FOOTER_TITLE = new SettingDef(
            "site.footer.title", Config.Type.STRING, "")
            .withOrgOnly().withTranslatable();

    public static final SettingDef SITE_FOOTER_TEXT = new SettingDef(
            "site.footer.text", Config.Type.STRING, "").withOrgOnly().withTranslatable();

    /**
     * The two footer colors. Blank means the theme's own footer color, which is the near-white the classic
     * layout draws over the page background and the palette's colors on a full-width page; a chosen color is
     * honored on either layout. Hex only ({@link SettingDef#hexColor}), because it lands in a style attribute.
     */
    public static final SettingDef SITE_FOOTER_TITLE_COLOR = new SettingDef(
            "site.footer.title.color", Config.Type.STRING, "").withOrgOnly().withHexColor();

    public static final SettingDef SITE_FOOTER_TEXT_COLOR = new SettingDef(
            "site.footer.text.color", Config.Type.STRING, "").withOrgOnly().withHexColor();

    /** ONE color for the footer's Privacy, Terms and Support links and the dots between them. */
    public static final SettingDef SITE_FOOTER_LINKS_COLOR = new SettingDef(
            "site.footer.links.color", Config.Type.STRING, "")
            .withOrgOnly().withHexColor();

    public static final SettingDef SITE_CONTACT_NAME = new SettingDef(
            "site.contact.name", Config.Type.STRING, "").withOrgOnly();

    public static final SettingDef SITE_CONTACT_PHONE = new SettingDef(
            "site.contact.phone", Config.Type.STRING, "")
            .withOrgOnly();

    public static final SettingDef SITE_DONATE_URL = new SettingDef(
            "site.donate.url", Config.Type.STRING, "").withOrgOnly().withHttpUrl();

    /**
     * The donate card's own words. Blank keeps the plain card (heading "Donate", no text), so every org that set
     * only the URL keeps exactly the card it had.
     */
    public static final SettingDef SITE_DONATE_TITLE = new SettingDef(
            "site.donate.title", Config.Type.STRING, "").withOrgOnly().withTranslatable();

    public static final SettingDef SITE_DONATE_TEXT = new SettingDef(
            "site.donate.text", Config.Type.STRING, "").withOrgOnly().withTranslatable();

    /**
     * PayPal's own Donate image in place of the themed button (issue #123: an org that collects through a PayPal
     * hosted button may want the button its donors recognize). Honored only while the donate link is a PayPal
     * page ({@code BrandCommands.isDonatePaypalImage}), so PayPal's mark never fronts a link to anywhere else.
     */
    public static final SettingDef SITE_DONATE_PAYPAL_IMAGE = new SettingDef(
            "site.donate.paypalImage", Config.Type.BOOLEAN, "false").withOrgOnly();

    /**
     * The org site's browser title. Pages that name themselves (a trip page, a site page) still use their own
     * title; this replaces the ORG NAME that the home page, the sign-in pages and the installed-app name show.
     */
    public static final SettingDef SITE_TITLE = new SettingDef(
            "site.title", Config.Type.STRING, "")
            .withOrgOnly().withTranslatable();

    public static final SettingDef SITE_SIDEBAR_TITLE = new SettingDef(
            "site.sidebar.title", Config.Type.STRING, "").withOrgOnly().withTranslatable();

    // --- home page ---

    public static final SettingDef HOME_PHOTOS_WINDOW_DAYS = new SettingDef(
            "home.photos.windowDays", Config.Type.INT, "365")
            .withOrgOverride();

    public static final SettingDef HOME_PHOTOS_MIN_COUNT = new SettingDef(
            "home.photos.minCount", Config.Type.INT, "10").withOrgOverride();

    public static final SettingDef HOME_COUNTDOWN_SOON_DAYS = new SettingDef(
            "home.countdown.soonDays", Config.Type.INT, "60")
            .withOrgOverride();

    // --- content templates ---

    public static final SettingDef CONTENT_VERSIONS_RETAINED = new SettingDef(
            "content.versions.retained", Config.Type.INT, "5");

    public static final SettingDef HOME_BANNER_ENABLED = new SettingDef(
            "home.banner.enabled", Config.Type.BOOLEAN, "false");

    public static final SettingDef HOME_BANNER_TEXT = new SettingDef(
            "home.banner.text", Config.Type.STRING, "");

    // --- chat email ---

    public static final SettingDef CHAT_MAIL_ENABLED = new SettingDef(
            "chat.mail.enabled", Config.Type.BOOLEAN, "false");

    /*
     * The mail-address slots below share one value shape, resolved by {@code MailAddressCommands}: the
     * sentinel {@code site} means the Site email setting; {@code org} (recipient/reply-to slots that have a
     * trip in hand only) means the owning organization's contact email, falling back to the Site email; any
     * other value is a literal address. The Email addresses section of the Settings page renders these with
     * a mode widget instead of the generic text box.
     *
     * Each such slot's description ends with the same "Value shape: ..." sentence. It was one constant appended
     * here; in the bundle it is written out in every description, because a translated text is a whole text.
     */

    public static final SettingDef CHAT_MAIL_FROM = new SettingDef(
            "chat.mail.from", Config.Type.STRING, "Trip Chat <no-reply@visitqueenofpeace.com>");

    public static final SettingDef CHAT_MAIL_REPLY_TO = new SettingDef(
            "chat.mail.replyTo", Config.Type.STRING, "no-reply@visitqueenofpeace.com");

    public static final SettingDef CHAT_MAIL_BASE_URL = new SettingDef(
            "chat.mail.baseUrl", Config.Type.STRING, "https://my.centermirmedjugorje.com");

    // --- chat digest ---

    public static final SettingDef CHAT_DIGEST_ENABLED = new SettingDef(
            "chat.digest.enabled", Config.Type.BOOLEAN, "false");

    public static final SettingDef CHAT_DIGEST_HOUR = new SettingDef(
            "chat.digest.hour", Config.Type.INT, "8");

    public static final SettingDef CHAT_DIGEST_ZONE = new SettingDef(
            "chat.digest.zone", Config.Type.STRING, "America/Los_Angeles");

    // --- trip reminders (docs/reminders.md) ---

    public static final SettingDef REMINDERS_ENABLED = new SettingDef(
            "reminders.enabled", Config.Type.BOOLEAN, "false");

    public static final SettingDef REMINDERS_HOUR = new SettingDef(
            "reminders.hour", Config.Type.INT, "9");

    public static final SettingDef REMINDERS_ZONE = new SettingDef(
            "reminders.zone", Config.Type.STRING, "America/Los_Angeles");

    // --- chat limits ---

    public static final SettingDef CHAT_GLOBAL_LIMIT = new SettingDef(
            "chat.global.limit", Config.Type.INT, "200");

    public static final SettingDef CHAT_GLOBAL_WINDOW_SECONDS = new SettingDef(
            "chat.global.windowSeconds", Config.Type.INT, "300");

    public static final SettingDef CHAT_EDIT_WINDOW_MINUTES = new SettingDef(
            "chat.edit.windowMinutes", Config.Type.INT, "15");

    // --- chat moderation ---

    public static final SettingDef CHAT_AUTO_MUTE_TRIGGER_COUNT = new SettingDef(
            "chat.autoMute.triggerCount", Config.Type.INT, "3");

    public static final SettingDef CHAT_AUTO_MUTE_TRIGGER_WINDOW_SECONDS = new SettingDef(
            "chat.autoMute.triggerWindowSeconds", Config.Type.INT, "600");

    public static final SettingDef CHAT_AUTO_MUTE_LADDER_MINUTES = new SettingDef(
            "chat.autoMute.ladderMinutes", Config.Type.STRING, "5,30,1440");

    public static final SettingDef CHAT_AUTO_MUTE_TIER_DECAY_HOURS = new SettingDef(
            "chat.autoMute.tierDecayHours", Config.Type.INT, "24");

    public static final SettingDef CHAT_ALARM_DEDUPE_WINDOW_SECONDS = new SettingDef(
            "chat.alarm.dedupeWindowSeconds", Config.Type.INT, "300");

    /**
     * The objectionable-content filter (App Store guideline 1.2 asks for "a method for filtering objectionable
     * material"): a message or photo comment containing any of these terms is refused at send time with the
     * {@code BLOCKED_CONTENT} code. Matched as whole words, case-insensitively. The default is deliberately
     * short -- the clearest obscenities and slurs -- because false positives silence pilgrims talking about
     * ordinary things; a site admin extends it or empties it here.
     */
    public static final SettingDef CHAT_BLOCKED_TERMS = new SettingDef(
            "chat.blockedTerms", Config.Type.STRING,
            "fuck,fucking,fucker,motherfucker,shit,bullshit,cunt,cock,dick,pussy,asshole,bitch,bastard,"
                    + "whore,slut,nigger,nigga,faggot,fag,dyke,tranny,retard,spic,chink,kike,wetback,raghead");

    public static final SettingDef MODERATION_REPORT_EMAIL = new SettingDef(
            "moderation.report.email", Config.Type.STRING, "facilitators");

    public static final SettingDef MODERATION_REPORT_FROM = new SettingDef(
            "moderation.report.from", Config.Type.STRING, "site");

    public static final SettingDef MODERATION_PLATFORM_EMAIL = new SettingDef(
            "moderation.platform.email", Config.Type.STRING, "info@unitetrip.com");

    // --- chat appearance ---

    public static final SettingDef CHAT_BACKGROUND_COLORS = new SettingDef(
            "chat.background.colors", Config.Type.STRING,
            "#eef2f7,#f5efe6,#eaf3ec,#f3eaf3,#fdf4e3,#e8eef5,#ffffff")
            .withOrgOverride();

    public static final SettingDef CHAT_BACKGROUND_IMAGE = new SettingDef(
            "chat.background.image", Config.Type.STRING,
            "https://files.visitqueenofpeace.com/images/mary-link.jpg").withOrgOverride();


    public static final SettingDef CHAT_REACTIONS_PALETTE = new SettingDef(
            "chat.reactions.palette", Config.Type.STRING, "👍,❤️,😂,🙏,"
                    + "📿,🎉,😮,😢,😊,✅")
            .withOrgOverride();

    public static final SettingDef CHAT_PHOTO_COMMENTS_ENABLED = new SettingDef(
            "chat.photoComments.enabled", Config.Type.BOOLEAN, "true");

    public static final SettingDef CHAT_PHOTO_COMMENT_MAX_CHARS = new SettingDef(
            "chat.photoComments.maxChars", Config.Type.INT, "1000");

    // --- chat invites ---

    public static final SettingDef CHAT_INVITES_ENABLED = new SettingDef(
            "chat.invites.enabled", Config.Type.BOOLEAN, "true");

    public static final SettingDef CHAT_INVITE_EXPIRY_DAYS = new SettingDef(
            "chat.invites.expiryDays", Config.Type.INT, "30");

    public static final SettingDef CHAT_INVITE_MAX_OUTSTANDING = new SettingDef(
            "chat.invites.maxPerChannel", Config.Type.INT, "10");

    // --- login & security ---

    public static final SettingDef LOGIN_CODE_ENABLED = new SettingDef(
            "login.code.enabled", Config.Type.BOOLEAN, "true");

    public static final SettingDef LOGIN_CODE_LENGTH = new SettingDef(
            "login.code.length", Config.Type.INT, "6");

    public static final SettingDef LOGIN_CODE_TTL_SECONDS = new SettingDef(
            "login.code.ttlSeconds", Config.Type.INT, "300");

    public static final SettingDef LOGIN_CODE_MAX_SENDS = new SettingDef(
            "login.code.maxSends", Config.Type.INT, "3");

    public static final SettingDef LOGIN_CODE_SEND_WINDOW_SECONDS = new SettingDef(
            "login.code.sendWindowSeconds", Config.Type.INT, "900");

    public static final SettingDef LOGIN_CODE_MAX_ATTEMPTS = new SettingDef(
            "login.code.maxAttempts", Config.Type.INT, "5");

    public static final SettingDef LOGIN_MAIL_FROM = new SettingDef(
            "login.mail.from", Config.Type.STRING, "site");

    public static final SettingDef LOGIN_MAIL_REPLY_TO = new SettingDef(
            "login.mail.replyTo", Config.Type.STRING, "ken@visitqueenofpeace.com");

    public static final SettingDef LOGIN_PASSKEY_ENABLED = new SettingDef(
            "login.passkey.enabled", Config.Type.BOOLEAN, "false");

    public static final SettingDef LOGIN_REMEMBER_ENABLED = new SettingDef(
            "login.remember.enabled", Config.Type.BOOLEAN, "true");

    public static final SettingDef LOGIN_REMEMBER_DAYS = new SettingDef(
            "login.remember.days", Config.Type.INT, "60");

    public static final SettingDef API_TOKEN_ENABLED = new SettingDef(
            "api.token.enabled", Config.Type.BOOLEAN, "false");

    public static final SettingDef API_TOKEN_ACCESS_MINUTES = new SettingDef(
            "api.token.access.minutes", Config.Type.INT, "30");

    public static final SettingDef API_TOKEN_REFRESH_DAYS = new SettingDef(
            "api.token.refresh.days", Config.Type.INT, "60");

    public static final SettingDef API_TOKEN_REFRESH_ADMIN_DAYS = new SettingDef(
            "api.token.refresh.admin.days", Config.Type.INT, "30");

    // --- account deletion (self-service, from the native app) ---

    public static final SettingDef ACCOUNT_DELETE_REQUIRE_SETTLED = new SettingDef(
            "account.delete.requireSettled", Config.Type.BOOLEAN, "true");

    public static final SettingDef ACCOUNT_DELETE_NOTIFY_EMAIL = new SettingDef(
            "account.delete.notify.email", Config.Type.STRING, "org");

    public static final SettingDef ACCOUNT_DELETE_NOTIFY_FROM = new SettingDef(
            "account.delete.notify.from", Config.Type.STRING, "site");

    public static final SettingDef LOGIN_PASSWORD_MAX_FAILS = new SettingDef(
            "login.password.maxFails", Config.Type.INT, "10");

    public static final SettingDef LOGIN_PASSWORD_FAIL_WINDOW_SECONDS = new SettingDef(
            "login.password.failWindowSeconds", Config.Type.INT, "900");

    // --- registration ---

    public static final SettingDef REG_ALLOW_EDITS = new SettingDef(
            "reg.allowEdits", Config.Type.BOOLEAN, "true")
            .withOrgOverride();

    // --- lodging ---

    /**
     * Lodging bills depend on who else is in the room each night (PER_ROOM splits, the single supplement), so
     * every reservation write recomputes the affected room's bills and rewrites its deterministic Bill rows in
     * place, audited. Off means an admin presses Recompute on the trip's Lodging page instead.
     */
    public static final SettingDef LODGING_AUTO_RECOMPUTE = new SettingDef(
            "lodging.bills.autoRecompute", Config.Type.BOOLEAN, "true").withOrgOverride();

    // --- registration email ---

    public static final SettingDef REG_MAIL_FROM = new SettingDef(
            "reg.mail.from", Config.Type.STRING, "site");

    public static final SettingDef REG_MAIL_REPLY_TO = new SettingDef(
            "reg.mail.replyTo", Config.Type.STRING, "org");

    public static final SettingDef REG_MAIL_BASE_URL = new SettingDef(
            "reg.mail.baseUrl", Config.Type.STRING, "https://visitqueenofpeace.com");

    // --- email addresses (rendered by the Settings page's Email addresses section with a mode widget) ---

    public static final SettingDef SITE_MAIL_EMAIL = new SettingDef(
            "site.mail.email", Config.Type.STRING, "Visit Queen of Peace <no-reply@visitqueenofpeace.com>");

    public static final SettingDef REG_NOTIFY_EMAIL = new SettingDef(
            "reg.notify.email", Config.Type.STRING, "facilitators");

    public static final SettingDef REG_NOTIFY_FROM = new SettingDef(
            "reg.notify.from", Config.Type.STRING, "site");

    public static final SettingDef ACCOUNT_NOTIFY_EMAIL = new SettingDef(
            "account.notify.email", Config.Type.STRING,
            "registration-notifications@centerforpeacewest.com");

    public static final SettingDef ACCOUNT_NOTIFY_FROM = new SettingDef(
            "account.notify.from", Config.Type.STRING, "site");

    public static final SettingDef TX_NOTIFY_EMAIL = new SettingDef(
            "tx.notify.email", Config.Type.STRING, "ken@visitqueenofpeace.com");

    public static final SettingDef TX_NOTIFY_FROM = new SettingDef(
            "tx.notify.from", Config.Type.STRING, "site");

    public static final SettingDef SUPPORT_MAIL_FROM = new SettingDef(
            "support.mail.from", Config.Type.STRING, "site");

    public static final SettingDef SUPPORT_MAIL_REPLY_TO = new SettingDef(
            "support.mail.replyTo", Config.Type.STRING, "site");

    // --- support requests ---

    public static final SettingDef SUPPORT_MAIL_ENABLED = new SettingDef(
            "support.mail.enabled", Config.Type.BOOLEAN, "true");

    // --- family accounts ---

    public static final SettingDef FAMILY_MAX_MEMBERS = new SettingDef(
            "family.maxMembers", Config.Type.INT, "10");

    // --- payments ---

    public static final SettingDef PAYMENT_RETURN_URL_PREFIXES = new SettingDef(
            "payment.returnUrl.allowedPrefixes", Config.Type.STRING,
            "https://my.centermirmedjugorje.com,https://www.visitqueenofpeace.com,https://visitqueenofpeace.com");

    /**
     * The internal copy (a bcc) of every payment mail: the confirmation, the checkout confirmation and the
     * payment-failed notice. Org-overridable, and read for the PAYMENT's organization
     * ({@code PaymentCommands.notifyEmail}), never the request's site: a payment is finished wherever the
     * processor calls back. UNLIKE the treasurer below, an organization does not inherit the site's value
     * (owner, 2026-11-24): with no address of its own it gets no copy, and the site's value serves only a legacy
     * payment that has no organization. One address, not a list.
     */
    public static final SettingDef PAYMENT_NOTIFY_EMAIL = new SettingDef(
            "payment.notify.email", Config.Type.STRING, "")
            .withOrgOverride().withEmail();

    /**
     * Where a trip's submitted expense report is emailed, with its receipts, when the submitter chooses to
     * ({@code docs/expense-report.md}). Org-overridable: each organization names its own treasurer, and the
     * site value is the fallback for organizations that have not.
     */
    public static final SettingDef FINANCE_TREASURER_EMAIL = new SettingDef(
            "finance.treasurer.email", Config.Type.STRING, "")
            .withOrgOverride().withEmail();

    public static final SettingDef PAYMENT_MAIL_FROM = new SettingDef(
            "payment.mail.from", Config.Type.STRING, "")
            .withSiteFrom();

    public static final SettingDef PAYMENT_CONFIRM_TEMPLATE = new SettingDef(
            "payment.confirm.templateId", Config.Type.STRING, "payment-confirmation");

    public static final SettingDef PAYMENT_FEES_PAID_BY = new SettingDef(
            "payment.fees.paidBy", Config.Type.STRING, "ORGANIZATION");

    // --- push notifications ---

    public static final SettingDef PUSH_ENABLED = new SettingDef(
            "push.enabled", Config.Type.BOOLEAN, "false");

    public static final SettingDef PUSH_APNS_TOPIC = new SettingDef(
            "push.apns.topic", Config.Type.STRING, "org.paulsens.unitetrip");

    public static final SettingDef PUSH_SILENT_INTERVAL_MINUTES = new SettingDef(
            "push.silent.intervalMinutes", Config.Type.INT, "15");

    public static final SettingDef PUSH_WEB_ENABLED = new SettingDef(
            "push.web.enabled", Config.Type.BOOLEAN, "true");

    // --- profile pictures ---

    public static final SettingDef PROFILE_BG_REMOVAL_ENABLED = new SettingDef(
            "profile.bgRemoval.enabled", Config.Type.BOOLEAN, "false");

    // --- performance ---

    public static final SettingDef CACHE_NEAR_TTL_SECONDS = new SettingDef(
            "cache.near.ttlSeconds", Config.Type.INT, "86400");

    public static final SettingDef CACHE_NEAR_CHECK_SECONDS = new SettingDef(
            "cache.near.checkSeconds", Config.Type.INT, "300");

    /**
     * The Settings page renders this section with a purpose-built mode widget (org/site/custom + a
     * local-part/domain composer for From addresses) instead of the generic text boxes -- it skips the
     * section in its generic loop BY THIS TITLE, so rename both together.
     */
    // --- mobile apps ---

    public static final SettingDef APPS_APP_STORE_URL = new SettingDef(
            "apps.appStore.url", Config.Type.STRING, "").withHttpUrl();

    public static final SettingDef APPS_PLAY_STORE_URL = new SettingDef(
            "apps.playStore.url", Config.Type.STRING, "").withHttpUrl();

    // --- discover (the apps' signed-out browse, medjugorje#155) ---

    public static final SettingDef DISCOVER_RATE_LIMIT = new SettingDef(
            "discover.rate.limit", Config.Type.INT, "120");

    public static final SettingDef DISCOVER_RATE_WINDOW_SECONDS = new SettingDef(
            "discover.rate.windowSeconds", Config.Type.INT, "60");

    /*
     * A section's title is its stable ID (code and the Settings page compare it), and happens to be its English
     * heading. What a person reads is SettingSection.getDisplayTitle(), from the bundle.
     */
    public static final String EMAIL_ADDRESSES_SECTION = "Email addresses";

    /** The org-site branding section: every def in it is org-only and read by {@code BrandCommands}. */
    public static final String BRANDING_SECTION = "Branding";

    private static final List<SettingSection> SECTIONS = List.of(
            new SettingSection("Site",
                    List.of(SITE_ORG_NAME, SITE_ORGSITES_BASE_DOMAIN, MAIL_SHARED_DOMAINS, SITE_ANALYTICS_ID,
                            SITE_LANGUAGES, API_LANGUAGES_MIN_APP_VERSION)),
            new SettingSection(BRANDING_SECTION,
                    List.of(SITE_THEME_PALETTE, SITE_THEME_DARK, SITE_LAYOUT, SITE_LOGO_URL, SITE_FAVICON_URL,
                            SITE_OG_IMAGE_URL,
                            SITE_BACKGROUND_URL, SITE_BACKGROUND_COLOR, SITE_FOOTER_TITLE, SITE_FOOTER_TITLE_COLOR,
                            SITE_FOOTER_TEXT, SITE_FOOTER_TEXT_COLOR, SITE_FOOTER_LINKS_COLOR,
                            SITE_CONTACT_NAME, SITE_CONTACT_PHONE, SITE_DONATE_URL, SITE_DONATE_TITLE,
                            SITE_DONATE_TEXT, SITE_DONATE_PAYPAL_IMAGE, SITE_TITLE, SITE_SIDEBAR_TITLE)),
            new SettingSection(EMAIL_ADDRESSES_SECTION,
                    List.of(SITE_MAIL_EMAIL,
                            REG_MAIL_FROM, REG_MAIL_REPLY_TO, REG_NOTIFY_EMAIL, REG_NOTIFY_FROM,
                            ACCOUNT_NOTIFY_EMAIL, ACCOUNT_NOTIFY_FROM, TX_NOTIFY_EMAIL, TX_NOTIFY_FROM,
                            LOGIN_MAIL_FROM, LOGIN_MAIL_REPLY_TO, CHAT_MAIL_FROM, CHAT_MAIL_REPLY_TO,
                            SUPPORT_MAIL_FROM, SUPPORT_MAIL_REPLY_TO)),
            new SettingSection("Performance",
                    List.of(CACHE_NEAR_TTL_SECONDS, CACHE_NEAR_CHECK_SECONDS)),
            new SettingSection("Push notifications",
                    List.of(PUSH_ENABLED, PUSH_APNS_TOPIC, PUSH_SILENT_INTERVAL_MINUTES, PUSH_WEB_ENABLED)),
            new SettingSection("Profile pictures",
                    List.of(PROFILE_BG_REMOVAL_ENABLED)),
            new SettingSection("Home page",
                    List.of(HOME_BANNER_ENABLED, HOME_BANNER_TEXT,
                            HOME_PHOTOS_WINDOW_DAYS, HOME_PHOTOS_MIN_COUNT, HOME_COUNTDOWN_SOON_DAYS)),
            new SettingSection("Content templates",
                    List.of(CONTENT_VERSIONS_RETAINED)),
            new SettingSection("Chat email",
                    List.of(CHAT_MAIL_ENABLED, CHAT_MAIL_BASE_URL)),
            new SettingSection("Chat daily digest",
                    List.of(CHAT_DIGEST_ENABLED, CHAT_DIGEST_HOUR, CHAT_DIGEST_ZONE)),
            new SettingSection("Chat limits",
                    List.of(CHAT_GLOBAL_LIMIT, CHAT_GLOBAL_WINDOW_SECONDS, CHAT_EDIT_WINDOW_MINUTES)),
            new SettingSection("Chat moderation",
                    List.of(CHAT_AUTO_MUTE_TRIGGER_COUNT, CHAT_AUTO_MUTE_TRIGGER_WINDOW_SECONDS,
                            CHAT_AUTO_MUTE_LADDER_MINUTES, CHAT_AUTO_MUTE_TIER_DECAY_HOURS,
                            CHAT_ALARM_DEDUPE_WINDOW_SECONDS, CHAT_BLOCKED_TERMS,
                            MODERATION_REPORT_EMAIL, MODERATION_REPORT_FROM, MODERATION_PLATFORM_EMAIL)),
            new SettingSection("Chat appearance",
                    List.of(CHAT_REACTIONS_PALETTE, CHAT_BACKGROUND_COLORS, CHAT_BACKGROUND_IMAGE)),
            new SettingSection("Chat invites",
                    List.of(CHAT_INVITES_ENABLED, CHAT_INVITE_EXPIRY_DAYS, CHAT_INVITE_MAX_OUTSTANDING)),
            new SettingSection("Photo comments",
                    List.of(CHAT_PHOTO_COMMENTS_ENABLED, CHAT_PHOTO_COMMENT_MAX_CHARS)),
            new SettingSection("Login & security",
                    List.of(LOGIN_CODE_ENABLED, LOGIN_CODE_LENGTH, LOGIN_CODE_TTL_SECONDS, LOGIN_CODE_MAX_SENDS,
                            LOGIN_CODE_SEND_WINDOW_SECONDS, LOGIN_CODE_MAX_ATTEMPTS,
                            LOGIN_REMEMBER_ENABLED, LOGIN_REMEMBER_DAYS,
                            LOGIN_PASSKEY_ENABLED, LOGIN_PASSWORD_MAX_FAILS,
                            LOGIN_PASSWORD_FAIL_WINDOW_SECONDS,
                            API_TOKEN_ENABLED, API_TOKEN_ACCESS_MINUTES,
                            API_TOKEN_REFRESH_DAYS, API_TOKEN_REFRESH_ADMIN_DAYS)),
            new SettingSection("Account deletion",
                    List.of(ACCOUNT_DELETE_REQUIRE_SETTLED, ACCOUNT_DELETE_NOTIFY_EMAIL,
                            ACCOUNT_DELETE_NOTIFY_FROM)),
            new SettingSection("Family accounts",
                    List.of(FAMILY_MAX_MEMBERS)),
            new SettingSection("Registration",
                    List.of(REG_ALLOW_EDITS)),
            new SettingSection("Trips",
                    List.of(TRIP_TAGS)),
            new SettingSection("Scheduled trip messages",
                    List.of(REMINDERS_ENABLED, REMINDERS_HOUR, REMINDERS_ZONE)),
            new SettingSection("Registration email",
                    List.of(REG_MAIL_BASE_URL)),
            new SettingSection("Support requests",
                    List.of(SUPPORT_MAIL_ENABLED)),
            new SettingSection("Mobile apps",
                    List.of(APPS_APP_STORE_URL, APPS_PLAY_STORE_URL)),
            new SettingSection("Discover",
                    List.of(DISCOVER_RATE_LIMIT, DISCOVER_RATE_WINDOW_SECONDS)),
            new SettingSection("Finance",
                    List.of(FINANCE_TREASURER_EMAIL)),
            new SettingSection("Lodging",
                    List.of(LODGING_AUTO_RECOMPUTE)),
            new SettingSection("Payments",
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
     * editor renders and the only names {@code Organization.settingsOverrides} may hold, beside the language
     * keys of the {@link #translatable()} ones ({@link #findOrgOverrideKey}).
     */
    public static List<SettingDef> orgOverridable() {
        return all().stream().filter(SettingDef::isOrgOverridable).toList();
    }

    /** The declaration for a key IF an organization may override it; empty for unknown or site-only keys. */
    public static Optional<SettingDef> findOrgOverridable(final String name) {
        return find(name).filter(SettingDef::isOrgOverridable);
    }

    /**
     * The declaration an override KEY belongs to, for the keys an organization row may hold: a plain
     * org-overridable name ({@link #findOrgOverridable}), or a {@link SettingDef#isTranslatable() translatable}
     * one's text in one language ({@code site.footer.text@es}). Empty for anything else, a language mark on a
     * setting that is not display text included.
     */
    public static Optional<SettingDef> findOrgOverrideKey(final String key) {
        if (SettingDef.languageOfKey(key).isEmpty()) {
            return findOrgOverridable(key);
        }
        return findOrgOverridable(SettingDef.baseNameOf(key)).filter(SettingDef::isTranslatable);
    }

    /** The settings an organization may write once per language: display text only, declared once above. */
    public static List<SettingDef> translatable() {
        return orgOverridable().stream().filter(SettingDef::isTranslatable).toList();
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
