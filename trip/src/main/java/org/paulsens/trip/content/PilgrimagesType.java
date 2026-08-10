package org.paulsens.trip.content;

import java.util.Arrays;
import java.util.List;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.Placeholder;

/**
 * The public pilgrimage listing for one language, as a programmatic content type: what v1 hardcoded as a
 * per-language accordion block on the landing page. The instance's title is the section heading
 * ("English Medjugorje Pilgrimages"); the fragment drives the same cached {@code TripCommands} listing the
 * v1 page used, filtered by these properties.
 */
final class PilgrimagesType implements ProgrammaticContentTemplate {

    static final String TYPE_ID = "pilgrimages";
    static final String PROP_LANGUAGE = "language";
    static final String PROP_CFPW_ONLY = "cfpwOnly";
    static final String PROP_MAX_COUNT = "maxCount";

    @Override
    public String getTypeId() {
        return TYPE_ID;
    }

    @Override
    public String getDisplayName() {
        return "Pilgrimage Listings";
    }

    @Override
    public String getDescription() {
        return "The public pilgrimage accordions for one language, straight from the trip data.";
    }

    @Override
    public List<Placeholder> getProperties() {
        return List.of(
                new Placeholder(PROP_LANGUAGE, Placeholder.Type.CHOICE, "Language",
                        "Which language's pilgrimages to list", true),
                new Placeholder(PROP_CFPW_ONLY, Placeholder.Type.CHOICE, "Providers",
                        "All public pilgrimages, or only CFPW-hosted ones", false),
                new Placeholder(PROP_MAX_COUNT, Placeholder.Type.TEXT, "Max to display",
                        "Blank shows every listed pilgrimage", false));
    }

    @Override
    public String getFragmentPath() {
        return "/WEB-INF/ptypes/pilgrimages.xhtml";
    }

    @Override
    public List<Choice> choicesFor(final String propertyName) {
        if (PROP_LANGUAGE.equals(propertyName)) {
            return Arrays.stream(Language.values())
                    .map(PilgrimagesType::languageChoice)
                    .toList();
        }
        if (PROP_CFPW_ONLY.equals(propertyName)) {
            return List.of(new Choice("false", "All providers"), new Choice("true", "CFPW only"));
        }
        return List.of();
    }

    private static Choice languageChoice(final Language language) {
        return new Choice(language.name(), language.getDisplayName());
    }
}
