package org.paulsens.trip.content;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.paulsens.trip.action.MediaCommands;
import org.paulsens.trip.action.TripPageCommands;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.Placeholder;

/**
 * The trip picture galleries as a programmatic content type: one galleria per qualifying trip from
 * the chat-photo albums, exactly the v1 Pictures section. Blank properties fall back to the KnownSettings
 * defaults ({@code home.photos.windowDays} / {@code home.photos.minCount}) so an empty instance behaves
 * like v1 did.
 */
final class PhotoAlbumsType implements ProgrammaticContentTemplate {

    static final String TYPE_ID = "photo-albums";
    static final String PROP_WINDOW_DAYS = "windowDays";
    static final String PROP_MIN_PHOTOS = "minPhotos";
    /**
     * One event's album instead of the recent trips' (docs/conference.md): the conference types' "Event" property,
     * blank meaning the page's own event on a conference page (and the recent trips anywhere else). What lets a
     * conference page show its own photos, which is why the type no longer refuses trip pages.
     */
    private static final Placeholder EVENT = new Placeholder(TripPageCommands.PROP_TRIP_ID, Placeholder.Type.CHOICE,
            "Event", "Blank: recent trips' albums (an event's own on its page); pick an event to show its album alone",
            false);

    @Override
    public String getTypeId() {
        return TYPE_ID;
    }

    @Override
    public String getDisplayName() {
        return "Trip Photo Albums";
    }

    @Override
    public String getIcon() {
        return "pi-images";
    }

    @Override
    public String getDescription() {
        return "A photo gallery per recent trip, from the publicly-visible chat photos.";
    }

    @Override
    public List<Placeholder> getProperties() {
        return List.of(
                new Placeholder(PROP_WINDOW_DAYS, Placeholder.Type.TEXT, "Window (days)",
                        "How far back a trip may have ended; blank = the site default", false),
                new Placeholder(PROP_MIN_PHOTOS, Placeholder.Type.TEXT, "Minimum photos",
                        "Fewest visible photos a trip needs to show; blank = the site default", false),
                EVENT,
                SharedSiteOrgChoices.property());
    }

    @Override
    public String getFragmentPath() {
        return "/WEB-INF/ptypes/photoAlbums.xhtml";
    }

    /** Where the sample's pictures go; the text block cannot name the constant itself. */
    private static final String PICTURE_SLOT = "@picture@";

    @Override
    public String getSampleHtml() {
        return """
                <h3>Trip pictures</h3>
                <div class="homeAlbums">
                    <div class="homeAlbum"><h4>Rome and Assisi (May 3 - 12, 2027)</h4>
                        <img src="@picture@" alt="" style="width:100%;display:block;" loading="lazy" /></div>
                    <div class="homeAlbum"><h4>Holy Land (October 8 - 18, 2027)</h4>
                        <img src="@picture@" alt="" style="width:100%;display:block;" loading="lazy" /></div>
                </div>
                """.replace(PICTURE_SLOT, StarterTemplates.SAMPLE_PICTURE);
    }

    /**
     * The type's defaults (on an event's page, that event's album) when they show at least one album; a site
     * with no qualifying album shows the sample.
     */
    @Override
    public Map<String, String> previewValues(final PreviewContext context) {
        final Map<String, String> values = context.tripId() == null ? Map.of()
                : Map.of(TripPageCommands.PROP_TRIP_ID, context.tripId());
        final ContentInstance probe = new ContentInstance("preview", null, "", TYPE_ID, 0, new HashMap<>(values),
                null, 0, 0, null, null);
        return new MediaCommands().getHomeAlbumsFor(probe).isEmpty() ? null : values;
    }

    @Override
    public List<Choice> choicesFor(final String propertyName) {
        if (EVENT.getName().equals(propertyName)) {
            return EventTripChoices.choices("Recent trips (on an event's page: that event)");
        }
        return SharedSiteOrgChoices.PROP_INCLUDE_ORGS.equals(propertyName)
                ? SharedSiteOrgChoices.choices() : List.of();
    }

    @Override
    public Set<String> sharedSiteOnlyProperties() {
        return Set.of(SharedSiteOrgChoices.PROP_INCLUDE_ORGS);
    }
}
