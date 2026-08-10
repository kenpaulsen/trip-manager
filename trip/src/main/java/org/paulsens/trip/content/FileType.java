package org.paulsens.trip.content;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.paulsens.trip.action.ChatPhotos;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Placeholder;

/**
 * One document from the media library, as a programmatic content type: the instance stores ONLY the media
 * row's id, and the fragment reads title/size/URL live from the (cached) media table at render -- nothing
 * is duplicated, so retitling or hiding the media item takes effect everywhere it appears. The Documents
 * section is a plain container holding N of these.
 */
final class FileType implements ProgrammaticContentTemplate {

    static final String TYPE_ID = "file";
    static final String PROP_MEDIA_ID = "mediaId";

    @Override
    public String getTypeId() {
        return TYPE_ID;
    }

    @Override
    public String getDisplayName() {
        return "File (from media library)";
    }

    @Override
    public String getDescription() {
        return "A link to one media-library document; its name and size stay in sync with the library.";
    }

    @Override
    public List<Placeholder> getProperties() {
        return List.of(new Placeholder(PROP_MEDIA_ID, Placeholder.Type.CHOICE, "Document",
                "Pick the media-library item to show", true));
    }

    @Override
    public String getFragmentPath() {
        return "/WEB-INF/ptypes/file.xhtml";
    }

    /** Curated (non-chat), publicly-visible library items, by title -- the admin's pick list. */
    @Override
    public List<Choice> choicesFor(final String propertyName) {
        if (!PROP_MEDIA_ID.equals(propertyName)) {
            return List.of();
        }
        return DAO.getInstance().getAllMedia().stream()
                .filter(item -> !ChatPhotos.isChatSlot(item.getSlot()))
                .filter(item -> !item.getHidden())
                .sorted(Comparator.comparing(FileType::titleKey))
                .map(FileType::mediaChoice)
                .toList();
    }

    private static String titleKey(final MediaItem item) {
        return item.getTitle() == null ? "" : item.getTitle().toLowerCase(Locale.ROOT);
    }

    private static Choice mediaChoice(final MediaItem item) {
        return new Choice(item.getId(), item.getTitle() + " (" + item.getDisplaySize() + ")");
    }
}
