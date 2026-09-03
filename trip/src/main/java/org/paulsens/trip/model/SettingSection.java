package org.paulsens.trip.model;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import lombok.Value;

/**
 * A group of related settings, rendered as one fieldset on the admin Settings page.
 *
 * <p>Grouping is presentation only -- the config table is flat -- but it is what makes eighteen settings
 * readable instead of an alphabetical wall.
 */
@Value
public class SettingSection implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    String title;
    /** Shown under the legend when the group needs a caveat that no single setting owns. */
    String note;
    List<SettingDef> settings;

    public SettingSection(final String title, final String note, final List<SettingDef> settings) {
        this.title = title;
        this.note = note;
        this.settings = List.copyOf(settings);
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
