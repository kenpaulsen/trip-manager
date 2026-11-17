package org.paulsens.trip.content;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.paulsens.trip.action.TripCommands;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.Placeholder;

/**
 * The public trip listing for one language, or for every language ({@code TripCommands.ANY_LANGUAGE}), as a
 * programmatic content type: what v1 hardcoded as a
 * per-language block on the landing page, drawn since issue 154 as picture cards. The instance's title is the
 * section heading ("English Medjugorje Pilgrimages"); the fragment drives the same cached {@code TripCommands}
 * listing the v1 page used, filtered by these properties.
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
        return "Trip Listings";
    }

    @Override
    public String getIcon() {
        return "pi-map";
    }

    @Override
    public String getDescription() {
        return "The public trips of one language, or of every language, as picture cards, straight from the trip "
                + "data.";
    }

    @Override
    public List<Placeholder> getProperties() {
        return List.of(
                new Placeholder(PROP_LANGUAGE, Placeholder.Type.CHOICE, "Language",
                        "Which language's trips to list, or any language", true),
                new Placeholder(PROP_CFPW_ONLY, Placeholder.Type.CHOICE, "Providers",
                        "All public trips, or only CFPW-hosted ones", false),
                new Placeholder(PROP_MAX_COUNT, Placeholder.Type.TEXT, "Max to display",
                        "Blank shows every listed trip", false),
                SharedSiteOrgChoices.property());
    }

    /** Lists other trips, so a single trip's page never offers it. */
    @Override
    public boolean fitsTripPage() {
        return false;
    }

    @Override
    public String getFragmentPath() {
        return "/WEB-INF/ptypes/pilgrimages.xhtml";
    }

    @Override
    public String getSampleHtml() {
        return """
                <h3>Upcoming trips</h3>
                <div class="ui-accordion ui-widget ui-helper-reset pilgrimages">
                    <div class="ui-accordion-header ui-helper-reset ui-state-default ui-corner-all">
                        <div class="tripTitle"><span class="title">Rome and Assisi</span></div>
                        <div class="info">May 3 - 12, 2027</div>
                    </div>
                    <div class="ui-accordion-header ui-helper-reset ui-state-default ui-corner-all">
                        <div class="tripTitle"><span class="title">Holy Land</span></div>
                        <div class="info">October 8 - 18, 2027</div>
                    </div>
                </div>
                """;
    }

    /** The first language this site lists a trip in; a site with no public trip shows the sample. */
    @Override
    public Map<String, String> previewValues(final PreviewContext context) {
        final TripCommands trips = new TripCommands();
        for (final Language language : Language.values()) {
            final Map<String, String> values = Map.of(PROP_LANGUAGE, language.name());
            if (!trips.getPublicTripsFor(listing(values)).isEmpty()) {
                return values;
            }
        }
        return null;
    }

    private static ContentInstance listing(final Map<String, String> values) {
        return new ContentInstance("preview", null, "", TYPE_ID, 0, new HashMap<>(values), null, 0, 0, null, null);
    }

    /** Provider and org curation are shared-site questions; an org's site is implicitly its own provider. */
    @Override
    public Set<String> sharedSiteOnlyProperties() {
        return Set.of(PROP_CFPW_ONLY, SharedSiteOrgChoices.PROP_INCLUDE_ORGS);
    }

    @Override
    public List<Choice> choicesFor(final String propertyName) {
        if (PROP_LANGUAGE.equals(propertyName)) {
            // "Any language" LAST: an existing section's stored value is untouched, and a new section's menu
            // still opens on the first language, as it always has.
            return Stream.concat(Arrays.stream(Language.values()).map(PilgrimagesType::languageChoice),
                    Stream.of(new Choice(TripCommands.ANY_LANGUAGE, "Any language"))).toList();
        }
        if (PROP_CFPW_ONLY.equals(propertyName)) {
            return List.of(new Choice("false", "All providers"), new Choice("true", "CFPW only"));
        }
        if (SharedSiteOrgChoices.PROP_INCLUDE_ORGS.equals(propertyName)) {
            return SharedSiteOrgChoices.choices();
        }
        return List.of();
    }

    private static Choice languageChoice(final Language language) {
        return new Choice(language.name(), language.getDisplayName());
    }
}
