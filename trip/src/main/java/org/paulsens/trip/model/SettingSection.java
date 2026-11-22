package org.paulsens.trip.model;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.Value;
import org.paulsens.trip.i18n.Text;

/**
 * A group of related settings, rendered as one fieldset on the admin Settings page.
 *
 * <p>Grouping is presentation only -- the config table is flat -- but it is what makes eighteen settings
 * readable instead of an alphabetical wall.
 */
// Equality and toString read the FIELDS: the getters answer in the language of the request in progress, and
// a hash that changed with the reader's language would lose the object in a map.
@Value
@EqualsAndHashCode(doNotUseGetters = true)
@ToString(doNotUseGetters = true)
public class SettingSection implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    private static final String KEYS = "labels.settingSection.";

    /**
     * The section's stable ID, which code and the Settings page compare ({@code KnownSettings.BRANDING_SECTION}):
     * never translated. It is also the English heading; what a person reads is {@link #getDisplayTitle()}.
     */
    String title;
    /**
     * Shown under the legend when the group needs a caveat that no single setting owns. Null for a section
     * declared in {@code KnownSettings}, whose note (when it has one) is in the bundle.
     */
    String note;
    List<SettingDef> settings;

    /**
     * A section whose heading and note are in the {@code labels} bundle, keyed by its title:
     * {@code labels.settingSection.{id}.title} and, when the section has a caveat, {@code .note}.
     */
    public SettingSection(final String title, final List<SettingDef> settings) {
        this(title, null, settings);
    }

    /** A section carrying its own note: for one that is not in {@code KnownSettings} (a test's). */
    public SettingSection(final String title, final String note, final List<SettingDef> settings) {
        this.title = title;
        this.note = note;
        this.settings = List.copyOf(settings);
    }

    /** The heading in the language of the request in progress; the title itself for a section the bundle lacks. */
    public String getDisplayTitle() {
        final String key = KEYS + keyId() + ".title";
        return Text.has(key) ? Text.get(key) : title;
    }

    /** The note in the language of the request in progress, or null when the section has none. */
    public String getNote() {
        final String key = KEYS + keyId() + ".note";
        return (note != null) ? note : Text.has(key) ? Text.get(key) : null;
    }

    /** The title as a key segment: "Login &amp; security" is {@code loginSecurity}. */
    private String keyId() {
        final StringBuilder id = new StringBuilder();
        for (final String word : title.split("[^A-Za-z0-9]+")) {
            if (!word.isEmpty()) {
                id.append(id.isEmpty() ? Character.toLowerCase(word.charAt(0)) : Character.toUpperCase(word.charAt(0)))
                        .append(word.substring(1));
            }
        }
        return id.toString();
    }

    /**
     * @return whether any setting here is one the SITE's own row can affect, i.e. not org-only. The site
     *         Settings page hides org-only rows (a value stored there applies to nothing), and a section made
     *         entirely of them would otherwise render as an empty fieldset under a heading.
     */
    public boolean hasSiteSettings() {
        return settings.stream().anyMatch(def -> !def.isOrgOnly());
    }
}
