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

    /** Where the sample's pictures go: {@link StarterTemplates#SAMPLE_PICTURE}. */
    private static final String PICTURE_SLOT = "@picture@";

    /**
     * The picture cards the fragment draws (pilgrimages.xhtml, tripCard.xhtml), in their own classes: the next
     * departure large, then the grid, one card with a picture and one with the generated cover a trip without one
     * gets. Each card is a div, not a link: a sample goes nowhere.
     */
    @Override
    public String getSampleHtml() {
        return """
                <div class="tripCardsSection">
                    <h3>Upcoming trips</h3>
                    <div class="tripCards">
                        <div class="tripCard tripCardFeature">
                            <span class="tripCardMedia"><img class="tripCardImg" src="@picture@" alt=""
                                width="1600" height="1067" /></span>
                            <span class="tripCardTop"><span class="tripChip tripChip-register">Register</span></span>
                            <span class="tripCardText">
                                <span class="tripCardEyebrow"><i class="pi pi-send" aria-hidden="true"></i>
                                    <span>Next departure</span></span>
                                <span class="tripCardTitle">Rome and Assisi</span>
                                <span class="tripCardMeta">May 3 - 12, 2027 · Rome, Italy</span>
                            </span>
                        </div>
                        <div class="tripCardGrid">
                            <div class="tripCard">
                                <span class="tripCardMedia"><img class="tripCardImg" src="@picture@" alt=""
                                    width="1600" height="1067" /></span>
                                <span class="tripCardTop"><span class="tripChip tripChip-full">Full</span></span>
                                <span class="tripCardText">
                                    <span class="tripCardTitle">Holy Land</span>
                                    <span class="tripCardMeta">October 8 - 18, 2027 · Jerusalem, Israel</span>
                                </span>
                            </div>
                            <div class="tripCard tripCardNoPhoto">
                                <span class="tripCardMedia"><span class="tripCardGenerated" aria-hidden="true"><i
                                    class="pi pi-compass"></i></span></span>
                                <span class="tripCardTop"><span class="tripChip tripChip-register">Register</span>
                                    </span>
                                <span class="tripCardText">
                                    <span class="tripCardTitle">Lourdes and Fatima</span>
                                    <span class="tripCardMeta">June 1 - 10, 2028</span>
                                </span>
                            </div>
                        </div>
                    </div>
                </div>
                """.replace(PICTURE_SLOT, StarterTemplates.SAMPLE_PICTURE);
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
