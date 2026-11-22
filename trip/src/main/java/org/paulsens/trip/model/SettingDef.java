package org.paulsens.trip.model;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.Value;
import org.paulsens.trip.i18n.LanguageMark;
import org.paulsens.trip.i18n.Text;

/**
 * The declaration of one setting the application actually knows about: its key, its type, its shipped default,
 * and how to describe it to an administrator.
 *
 * <p>This exists so a default lives in exactly ONE place. Before it, a setting was a string literal plus a
 * default at each call site, and the admin page was a bare name/value table -- so nothing connected the key an
 * administrator typed to the key the code read, and nothing showed what the value would be if left unset. A
 * typo produced a row that looked configured and changed nothing, which is the worst possible failure for a
 * settings table: silent, and indistinguishable from the setting not working.
 *
 * <p>{@code Serializable} because these reach {@code viewScope} through the Settings page's repeat.
 *
 * @see org.paulsens.trip.config.KnownSettings
 */
// Equality and toString read the FIELDS: the getters answer in the language of the request in progress, and
// a hash that changed with the reader's language would lose the object in a map.
@Value
@EqualsAndHashCode(doNotUseGetters = true)
@ToString(doNotUseGetters = true)
public class SettingDef implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    /** The config-table key. Stable: renaming one is really creating a different setting. */
    String name;
    Config.Type type;
    /**
     * What the code uses when this setting is unset, blank or unparseable. Held as a string because that is how
     * the table stores values, so the page can show the default exactly as it would be typed.
     */
    String defaultValue;
    /**
     * Short label for the property sheet. Null for a setting declared in {@code KnownSettings}, whose label is
     * the bundle's {@code labels.setting.{name}.label}; a literal only for a definition built outside it.
     */
    String label;
    /**
     * What it controls, and any consequence worth knowing before changing it. Null for a declared setting, as
     * {@link #label} is: {@code labels.setting.{name}.description}.
     */
    String description;
    /**
     * Whether an organization may override this setting for its OWN site (the trip &rarr; org &rarr; site
     * ladder the payment settings established, generalized): the org's non-blank override wins on its host,
     * else the site's stored row, else {@link #defaultValue}. False for the vast majority -- a setting that
     * governs shared infrastructure (mail plumbing, rate limits, caches) has no per-tenant meaning.
     */
    boolean orgOverridable;
    /**
     * Whether an ORG host resolves this setting from the org rung ONLY -- its own override or the compiled
     * default, never the site's stored row. For values that would be a cross-tenant leak if inherited: an
     * analytics property id set for the shared site must not silently collect an org site's traffic.
     * Implies {@link #orgOverridable}. Off an org host the site row applies as for any other setting.
     */
    boolean orgOnly;
    /**
     * The only values this setting accepts, in the order a menu offers them; empty means free text. A
     * blank value stays "unset" whatever this says. Both save paths (the site Settings page and the org
     * settings editor) refuse anything else, because a value outside the list is not merely wrong but
     * unrenderable -- a theme name is a stylesheet path, and a misspelt one is a 404 on every page.
     */
    List<String> choices;
    /**
     * Whether a non-blank value must be an {@code http(s)} URL. The save paths refuse anything else, and
     * the reading code still re-checks before the value lands in an attribute (a row written by hand
     * bypasses the page).
     */
    boolean httpUrl;
    /**
     * Whether a non-blank value must be a CSS hex color ({@code #rgb} / {@code #rrggbb}). Same contract as
     * {@link #httpUrl}: both save paths refuse anything else, and the reading code re-screens, because these
     * values are interpolated into a {@code style} attribute where anything else is CSS injection.
     */
    boolean hexColor;
    /**
     * Whether a non-blank value must be one email address. Same contract as {@link #httpUrl}: both save paths
     * refuse anything else, and the reading code re-checks before sending to it.
     */
    boolean email;
    /**
     * Whether a non-blank value is a SITE-level From address: one address on a sending domain that SES verifies
     * AND that is shared ({@code KnownSettings.MAIL_SHARED_DOMAINS}) -- the domains the Settings page's From
     * composers offer. Site mail goes out for every organization, so it must never wear one tenant's custom
     * domain, and a From on an unverified domain bounces at SES with nothing on screen. Both save paths refuse
     * anything else.
     */
    boolean siteFrom;
    /**
     * Whether a non-blank value must be a web analytics property id: a plain token of letters, digits and hyphens
     * ({@link #analyticsId(String)}). Same contract as {@link #hexColor}: both save paths refuse anything else, and
     * the page re-screens, because the id is written into a script URL and a JavaScript string, where HTML escaping
     * does not apply. Refusing on save also means a pasted snippet or two ids never quietly turn the tag off.
     */
    boolean analyticsId;
    /**
     * Whether an organization may give this setting one value per language its site offers ({@code docs/i18n.md},
     * "Short texts an organization writes per language"): display text such as the footer line, stored beside
     * the plain override under {@link #keyIn the name with a language mark} ({@code site.footer.text@es}) and read
     * before it for a reader in that language. Only an org-overridable STRING can be one: the translations live
     * on the org row, and a number, a color or a URL means the same thing in every language
     * ({@code TranslatedSettingsTest} holds every declaration to that, and the readers ignore the mark on anything
     * else).
     */
    boolean translatable;

    /**
     * A setting whose label and description are in the {@code labels} bundle, keyed by its name: every setting
     * {@code KnownSettings} declares, so the Settings pages read in the administrator's language.
     */
    public SettingDef(final String name, final Config.Type type, final String defaultValue) {
        this(name, type, defaultValue, null, null);
    }

    /** A setting carrying its own words: for a definition that is not in {@code KnownSettings} (a test's). */
    public SettingDef(final String name, final Config.Type type, final String defaultValue, final String label,
            final String description) {
        this(name, type, defaultValue, label, description, false, false, List.of(), false, false, false, false,
                false, false);
    }

    /*
     * The withers below copy through toBuilder() so each names the one field it sets. They used to call this
     * constructor positionally, five adjacent booleans deep, where a value in the wrong slot still compiled and
     * (say) made an email setting refuse every address as "not a hex color". The builder is PRIVATE because a
     * fresh builder() starts every field at its Java default (the persistence-gotchas trap) and choices at null;
     * only toBuilder() of an already-built instance is used, so every field it does not name is carried over.
     */
    @Builder(toBuilder = true, access = AccessLevel.PRIVATE)
    private SettingDef(final String name, final Config.Type type, final String defaultValue, final String label,
            final String description, final boolean orgOverridable, final boolean orgOnly,
            final List<String> choices, final boolean httpUrl, final boolean hexColor, final boolean email,
            final boolean siteFrom, final boolean analyticsId, final boolean translatable) {
        this.name = name;
        this.type = type;
        this.defaultValue = defaultValue;
        this.label = label;
        this.description = description;
        this.orgOverridable = orgOverridable || orgOnly;
        this.orgOnly = orgOnly;
        this.choices = List.copyOf(choices);
        this.httpUrl = httpUrl;
        this.hexColor = hexColor;
        this.email = email;
        this.siteFrom = siteFrom;
        this.analyticsId = analyticsId;
        this.translatable = translatable;
    }

    /** The label in the language of the request in progress; a definition's own literal when it has one. */
    public String getLabel() {
        return (label != null) ? label : bundled("label");
    }

    /** The description (the field's help-card text) in the language of the request in progress. */
    public String getDescription() {
        return (description != null) ? description : bundled("description");
    }

    private String bundled(final String what) {
        final String key = "labels.setting." + name + "." + what;
        return Text.get(key);
    }

    /**
     * This declaration, marked as one an organization may override on its own site. Leaves {@link #orgOnly} as it
     * was: an org-only setting is already overridable, and quietly dropping the org-only marking would let an org
     * host inherit the site's value.
     */
    public SettingDef withOrgOverride() {
        return toBuilder().orgOverridable(true).build();
    }

    /** This declaration, marked org-explicit: an org host never inherits the site's value (see {@link #orgOnly}). */
    public SettingDef withOrgOnly() {
        return toBuilder().orgOnly(true).build();
    }

    /** This declaration, restricted to exactly these values (see {@link #choices}); blank stays "unset". */
    public SettingDef withChoices(final String... allowed) {
        return toBuilder().choices(List.of(allowed)).build();
    }

    /** This declaration, requiring a non-blank value to be an {@code http(s)} URL (see {@link #httpUrl}). */
    public SettingDef withHttpUrl() {
        return toBuilder().httpUrl(true).build();
    }

    /** This declaration, requiring a non-blank value to be a CSS hex color (see {@link #hexColor}). */
    public SettingDef withHexColor() {
        return toBuilder().hexColor(true).build();
    }

    /** This declaration, requiring a non-blank value to be one email address (see {@link #email}). */
    public SettingDef withEmail() {
        return toBuilder().email(true).build();
    }

    /** This declaration, requiring a non-blank value to be a site-level From address (see {@link #siteFrom}). */
    public SettingDef withSiteFrom() {
        return toBuilder().siteFrom(true).build();
    }

    /** This declaration, requiring a non-blank value to be an analytics property id (see {@link #analyticsId}). */
    public SettingDef withAnalyticsId() {
        return toBuilder().analyticsId(true).build();
    }

    /**
     * This declaration, marked as display text an organization may write once per language (see
     * {@link #translatable}): an org-overridable STRING only.
     */
    public SettingDef withTranslatable() {
        return toBuilder().translatable(true).build();
    }

    /** The override key of this setting's text in {@code language}: {@code site.footer.text@es}. */
    public String keyIn(final Language language) {
        return LanguageMark.keyFor(name, language);
    }

    /** The setting name an override key belongs to: the key itself, without a language mark. Null in, null out. */
    public static String baseNameOf(final String key) {
        return LanguageMark.baseOf(key);
    }

    /** The language an override key names ({@code site.footer.text@es}); empty for a plain setting name. */
    public static Optional<Language> languageOfKey(final String key) {
        return LanguageMark.languageOf(key);
    }

    /**
     * A web analytics property id (a GA4 measurement id such as {@code G-ABC123}), trimmed, or null when
     * {@code raw} is not a plain token of 1 to 40 letters, digits and hyphens. The one screen both the save paths and
     * the page ({@code BrandCommands.getPageAnalyticsId}) apply.
     */
    public static String analyticsId(final String raw) {
        if (raw == null) {
            return null;
        }
        final String value = raw.trim();
        return value.matches("[A-Za-z0-9-]{1,40}") ? value : null;
    }

    /**
     * A CSS hex color, normalized to lower case, or null when {@code raw} is not one.
     *
     * <p>Deliberately narrow, and for the same reason {@code ChatAppearance} is: the value is interpolated
     * into a {@code style} attribute, so anything carrying a quote, a semicolon, a brace or {@code url(}
     * could close the declaration and start another. Color keywords are excluded here on purpose -- this is
     * what a color PICKER produces, and one shape is one thing to validate.
     *
     * @param raw the stored or submitted value, with or without surrounding whitespace.
     * @return the normalized {@code #rgb} / {@code #rrggbb} value, or null.
     */
    public static String hexColor(final String raw) {
        if (raw == null) {
            return null;
        }
        final String value = raw.trim().toLowerCase(Locale.ROOT);
        return value.matches("#([0-9a-f]{3}|[0-9a-f]{6})") ? value : null;
    }

    /** @return whether the settings pages render this as a menu over {@link #getChoices()} instead of a text box. */
    public boolean hasChoices() {
        return !choices.isEmpty();
    }

    /**
     * @return whether a trimmed, non-blank value is one this declaration's {@link #choices} admit. Always true
     *         for a free-text setting; the URL rule is the save path's, not this method's.
     */
    public boolean allows(final String value) {
        return !hasChoices() || choices.contains(value);
    }

    /**
     * @return whether this is edited as a tri-state (unset / yes / no) rather than a text field. Named for the
     *         control rather than the type because the page binds to it, and {@code boolean} as an EL property
     *         name is asking for trouble.
     */
    public boolean isYesNo() {
        return type == Config.Type.BOOLEAN;
    }

    /** The default as an int, for callers that declared an INT/LONG setting. */
    public int intDefault() {
        return Integer.parseInt(defaultValue);
    }

    public long longDefault() {
        return Long.parseLong(defaultValue);
    }

    public boolean booleanDefault() {
        return Boolean.parseBoolean(defaultValue);
    }
}
