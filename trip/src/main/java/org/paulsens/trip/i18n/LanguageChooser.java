package org.paulsens.trip.i18n;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.paulsens.trip.model.Language;

/**
 * Picks the language a request is answered in from the wishes it carries ({@code docs/i18n.md}, "Who decides
 * the language"). Pure on purpose: the caller ({@code web/RequestLocale}) gathers the wishes from the cookie,
 * the person and the headers and decides their ORDER, and this only applies the one rule they all share: a wish
 * counts when the site offers that language, and the site's default answers when none does.
 */
public final class LanguageChooser {

    private LanguageChooser() {
    }

    /**
     * The first wish the site offers, else the site's default (the first offered language).
     *
     * @param offered the site's languages, never empty ({@link SiteLanguages#parse})
     * @param wishes language tags in priority order; a null, blank or unknown one is skipped
     */
    public static Language choose(final List<Language> offered, final List<String> wishes) {
        return wishes.stream()
                .filter(Objects::nonNull)
                .map(Language::forTag)
                .flatMap(Optional::stream)
                .filter(offered::contains)
                .findFirst()
                .orElse(offered.get(0));
    }
}
