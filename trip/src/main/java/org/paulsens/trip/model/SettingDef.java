package org.paulsens.trip.model;

import java.io.Serializable;
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

    public SettingDef(final String name, final Config.Type type, final String defaultValue, final String label,
            final String description) {
        this(name, type, defaultValue, label, description, false, false);
    }

    private SettingDef(final String name, final Config.Type type, final String defaultValue, final String label,
            final String description, final boolean orgOverridable, final boolean orgOnly) {
        this.name = name;
        this.type = type;
        this.defaultValue = defaultValue;
        this.label = label;
        this.description = description;
        this.orgOverridable = orgOverridable || orgOnly;
        this.orgOnly = orgOnly;
    }

    /** This declaration, marked as one an organization may override on its own site. */
    public SettingDef withOrgOverride() {
        return new SettingDef(name, type, defaultValue, label, description, true, false);
    }

    /** This declaration, marked org-explicit: an org host never inherits the site's value (see {@link #orgOnly}). */
    public SettingDef withOrgOnly() {
        return new SettingDef(name, type, defaultValue, label, description, true, true);
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
