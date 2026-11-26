package org.paulsens.trip.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.action.TripPageCommands;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.model.Placeholder;
import org.paulsens.trip.model.Trip;

/**
 * The trip page's facts line as a section (docs/trip-pictures.md, "On the trip page"): the dates, the destination,
 * the spiritual director, the guide, the coordinators, the estimated price with its note and the flyer, each only
 * when the trip has one. Read from the trip at render time, so the trip editor stays where they are typed; the
 * section only chooses WHICH of them show ({@link TripPageCommands#FACTS}; blank shows the line as it always was,
 * every fact but the dates, which the opening banner carries). Offered on a trip's own page only.
 */
final class TripFactsType implements ProgrammaticContentTemplate {

    static final String TYPE_ID = "trip-details";

    @Override
    public String getTypeId() {
        return TYPE_ID;
    }

    @Override
    public String getDisplayName() {
        return Text.get("server.types.tripFacts.name");
    }

    @Override
    public String getIcon() {
        return "pi-info-circle";
    }

    @Override
    public String getDescription() {
        return Text.get("server.types.tripFacts.description");
    }

    @Override
    public List<Placeholder> getProperties() {
        return List.of(new Placeholder(TripPageCommands.PROP_FACTS_SHOWN, Placeholder.Type.MULTI_CHOICE,
                Text.get("server.types.tripFacts.shown"), Text.get("server.types.tripFacts.shownHint"), false));
    }

    @Override
    public String getFragmentPath() {
        return "/WEB-INF/ptypes/tripFacts.xhtml";
    }

    @Override
    public boolean onlyTripPage() {
        return true;
    }

    @Override
    public String getSampleHtml() {
        return "<div class=\"tripTop\"><div class=\"tripFacts\">"
                + "<span class=\"tripFact\"><i class=\"pi pi-map-marker\" aria-hidden=\"true\"></i> Rome, Italy</span>"
                + "<span class=\"tripFact\"><b>Guide:</b>&#160;Maria Sample</span>"
                + "<span class=\"tripFact tripFactPrice\"><b>Estimated price:</b>&#160;$2,400</span>"
                + "</div></div>";
    }

    /** The page's own trip when it has a fact to show; anywhere else the sample. */
    @Override
    public Map<String, String> previewValues(final PreviewContext context) {
        if (context.tripId() == null) {
            return null;
        }
        final TripPageCommands pages = new TripPageCommands();
        final Trip trip = pages.tripFor(null, context.tripId());
        return trip != null && pages.hasFacts(trip, null) ? Map.of() : null;
    }

    @Override
    public List<Choice> choicesFor(final String propertyName) {
        if (!TripPageCommands.PROP_FACTS_SHOWN.equals(propertyName)) {
            return List.of();
        }
        final List<Choice> choices = new ArrayList<>();
        for (final TripPageCommands.Fact fact : TripPageCommands.FACTS) {
            choices.add(new Choice(fact.value(), Text.get(fact.labelKey())));
        }
        return choices;
    }
}
