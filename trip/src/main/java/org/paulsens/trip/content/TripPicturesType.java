package org.paulsens.trip.content;

import java.util.List;
import java.util.Map;
import org.paulsens.trip.action.TripPageCommands;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.model.Placeholder;
import org.paulsens.trip.model.Trip;

/**
 * The trip page's pictures as a section (docs/trip-pictures.md, "On the trip page"): the cover as the hero, the
 * rest as a gallery the visitor pages through by hand, or the cover alone. The pictures are the TRIP's, the same
 * list the listing cards show, so they are managed through {@code TripPictureCommands} (the section's edit panel
 * opens that dialog) and never stored in the section. Offered on a trip's own page only ({@link #onlyTripPage}).
 */
final class TripPicturesType implements ProgrammaticContentTemplate {

    static final String TYPE_ID = "trip-pictures";

    @Override
    public String getTypeId() {
        return TYPE_ID;
    }

    @Override
    public String getDisplayName() {
        return Text.get("server.types.tripPictures.name");
    }

    @Override
    public String getIcon() {
        return "pi-images";
    }

    @Override
    public String getDescription() {
        return Text.get("server.types.tripPictures.description");
    }

    @Override
    public List<Placeholder> getProperties() {
        return List.of(new Placeholder(TripPageCommands.PROP_PICTURES_SHOW, Placeholder.Type.CHOICE,
                Text.get("server.types.tripPictures.show"), Text.get("server.types.tripPictures.showHint"), false));
    }

    @Override
    public String getFragmentPath() {
        return "/WEB-INF/ptypes/tripPictures.xhtml";
    }

    @Override
    public boolean onlyTripPage() {
        return true;
    }

    @Override
    public String getSampleHtml() {
        return "<div class=\"tripTop\"><div class=\"tripHero\"><span class=\"tripHeroMedia\">"
                + "<img class=\"tripCardImg\" src=\"" + StarterTemplates.SAMPLE_PICTURE + "\" width=\"960\""
                + " height=\"640\" alt=\"\" /></span></div></div>";
    }

    /** The page's own trip when it has a picture; anywhere else (or with none) the sample says more. */
    @Override
    public Map<String, String> previewValues(final PreviewContext context) {
        if (context.tripId() == null) {
            return null;
        }
        final Trip trip = new TripPageCommands().tripFor(null, context.tripId());
        return trip != null && !trip.getPictures().isEmpty() ? Map.of() : null;
    }

    @Override
    public List<Choice> choicesFor(final String propertyName) {
        if (!TripPageCommands.PROP_PICTURES_SHOW.equals(propertyName)) {
            return List.of();
        }
        return List.of(new Choice("", Text.get("server.types.tripPictures.showGallery")),
                new Choice(TripPageCommands.PICTURES_COVER_ONLY, Text.get("server.types.tripPictures.showCover")));
    }
}
