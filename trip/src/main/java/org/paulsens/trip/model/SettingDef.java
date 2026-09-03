package org.paulsens.trip.model;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Locale;
import lombok.Value;

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
@Value
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
    /** Short label for the property sheet. */
    String label;
    /** What it controls, and any consequence worth knowing before changing it. */
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

    public SettingDef(final String name, final Config.Type type, final String defaultValue, final String label,
            final String description) {
        this(name, type, defaultValue, label, description, false, false, List.of(), false, false);
    }

    private SettingDef(final String name, final Config.Type type, final String defaultValue, final String label,
            final String description, final boolean orgOverridable, final boolean orgOnly,
            final List<String> choices, final boolean httpUrl, final boolean hexColor) {
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
    }

    /** This declaration, marked as one an organization may override on its own site. */
    public SettingDef withOrgOverride() {
        return new SettingDef(name, type, defaultValue, label, description, true, false, choices, httpUrl,
                hexColor);
    }

    /** This declaration, marked org-explicit: an org host never inherits the site's value (see {@link #orgOnly}). */
    public SettingDef withOrgOnly() {
        return new SettingDef(name, type, defaultValue, label, description, true, true, choices, httpUrl,
                hexColor);
    }

    /** This declaration, restricted to exactly these values (see {@link #choices}); blank stays "unset". */
    public SettingDef withChoices(final String... allowed) {
        return new SettingDef(name, type, defaultValue, label, description, orgOverridable, orgOnly,
                List.of(allowed), httpUrl, hexColor);
    }

    /** This declaration, requiring a non-blank value to be an {@code http(s)} URL (see {@link #httpUrl}). */
    public SettingDef withHttpUrl() {
        return new SettingDef(name, type, defaultValue, label, description, orgOverridable, orgOnly, choices,
                true, hexColor);
    }

    /** This declaration, requiring a non-blank value to be a CSS hex color (see {@link #hexColor}). */
    public SettingDef withHexColor() {
        return new SettingDef(name, type, defaultValue, label, description, orgOverridable, orgOnly, choices,
                httpUrl, true);
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
