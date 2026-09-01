package org.paulsens.trip.content;

import java.util.List;
import java.util.Set;
import org.paulsens.trip.model.Placeholder;

/**
 * The pilgrimage picture galleries as a programmatic content type: one galleria per qualifying trip from
 * the chat-photo albums, exactly the v1 Pictures section. Blank properties fall back to the KnownSettings
 * defaults ({@code home.photos.windowDays} / {@code home.photos.minCount}) so an empty instance behaves
 * like v1 did.
 */
final class PhotoAlbumsType implements ProgrammaticContentTemplate {

    static final String TYPE_ID = "photo-albums";
    static final String PROP_WINDOW_DAYS = "windowDays";
    static final String PROP_MIN_PHOTOS = "minPhotos";

    @Override
    public String getTypeId() {
        return TYPE_ID;
    }

    @Override
    public String getDisplayName() {
        return "Pilgrimage Photo Albums";
    }

    @Override
    public String getDescription() {
        return "A photo gallery per recent pilgrimage, from the publicly-visible chat photos.";
    }

    @Override
    public List<Placeholder> getProperties() {
        return List.of(
                new Placeholder(PROP_WINDOW_DAYS, Placeholder.Type.TEXT, "Window (days)",
                        "How far back a pilgrimage may have ended; blank = the site default", false),
                new Placeholder(PROP_MIN_PHOTOS, Placeholder.Type.TEXT, "Minimum photos",
                        "Fewest visible photos a pilgrimage needs to show; blank = the site default", false),
                SharedSiteOrgChoices.property());
    }

    @Override
    public String getFragmentPath() {
        return "/WEB-INF/ptypes/photoAlbums.xhtml";
    }

    @Override
    public List<Choice> choicesFor(final String propertyName) {
        return SharedSiteOrgChoices.PROP_INCLUDE_ORGS.equals(propertyName)
                ? SharedSiteOrgChoices.choices() : List.of();
    }

    @Override
    public Set<String> sharedSiteOnlyProperties() {
        return Set.of(SharedSiteOrgChoices.PROP_INCLUDE_ORGS);
    }
}
