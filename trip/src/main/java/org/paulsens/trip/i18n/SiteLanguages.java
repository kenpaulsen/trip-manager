package org.paulsens.trip.i18n;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.paulsens.trip.model.Language;

/**
 * The languages a site offers, parsed from the {@code site.languages} setting ({@code docs/i18n.md}): a
 * comma-separated list of {@link Language} tags whose first entry is the site's default.
 *
 * <p>The one parser for that value. It never answers an empty list: a code this build does not know is dropped
 * and nothing left means English, because the setting is typed by a person and a typo must not leave a site
 * with no language to render in.
 */
public final class SiteLanguages {

    private SiteLanguages() {
    }

    /** The offered languages in the order the setting lists them, each once; English alone when none is valid. */
    public static List<Language> parse(final String setting) {
        final List<Language> offered = setting == null ? List.of() : Arrays.stream(setting.split(","))
                .map(Language::forTag)
                .flatMap(Optional::stream)
                .distinct()
                .toList();
        return offered.isEmpty() ? List.of(Language.English) : offered;
    }
}
