package org.paulsens.trip.content;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.paulsens.trip.action.ChatPhotos;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Placeholder;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.site.SiteContext;

/**
 * One document from the media library, as a programmatic content type: the instance stores ONLY the media
 * row's id, and the fragment reads title/size/URL live from the (cached) media table at render -- nothing
 * is duplicated, so retitling or hiding the media item takes effect everywhere it appears. A File is the
 * document alone, a link and its size: it draws no bullet, since whatever lists it (the Documents group's row, the
 * {@code document-list} template) owns the list. Its template's body is a LAYOUT ({@link TripLayout}, the
 * {@code {{file:...}}} words; docs/content-templates.md, "Live sections with a layout"), and a blank body draws the
 * built-in link and size.
 */
final class FileType implements ProgrammaticContentTemplate {

    static final String TYPE_ID = "file";
    static final String PROP_MEDIA_ID = "mediaId";
    /** The value tokens a layout may use: the document's title, link, size and description. */
    static final List<String> VALUES = List.of("title", "url", "size", "description");
    /** The one block: kept only when the document has a description. */
    static final String DESCRIPTION = "description";

    @Override
    public String getTypeId() {
        return TYPE_ID;
    }

    @Override
    public String getDisplayName() {
        return Text.get("server.types.file.name");
    }

    @Override
    public String getIcon() {
        return "pi-file";
    }

    @Override
    public String getDescription() {
        return Text.get("server.types.file.description");
    }

    @Override
    public List<Placeholder> getProperties() {
        return List.of(new Placeholder(PROP_MEDIA_ID, Placeholder.Type.CHOICE,
                Text.get("common.document"), Text.get("server.types.file.documentHint"), true));
    }

    /** The document's values and its description block; no section property (a File has no Tone). */
    @Override
    public TripLayout.Vocabulary layoutVocabulary() {
        return new TripLayout.Vocabulary(TripLayout.FILE, VALUES, List.of(DESCRIPTION), List.of());
    }

    /**
     * True when the document is hidden or gone, so the fragment draws nothing: the group's row around it is then
     * left out too ({@code ContentCommands.childRowBefore}), or a hidden document would leave an empty bullet.
     */
    @Override
    public boolean drawsNothing(final ContentInstance instance) {
        final String id = instance == null ? null : instance.getValues().get(PROP_MEDIA_ID);
        if (id == null || id.isBlank()) {
            return true;
        }
        return DAO.getInstance().getMedia(id, Cached.YES).map(MediaItem::getHidden).orElse(true);
    }

    @Override
    public String getFragmentPath() {
        return "/WEB-INF/ptypes/file.xhtml";
    }

    @Override
    public String getSampleHtml() {
        return "<a>Parish newsletter</a><span style=\"font-size:0.85em;opacity:0.8\"> - 240 KB</span>";
    }

    /** The first document this site's picker offers; a site with no document shows the sample. */
    @Override
    public Map<String, String> previewValues(final PreviewContext context) {
        final List<Choice> documents = choicesFor(PROP_MEDIA_ID);
        return documents.isEmpty() ? null : Map.of(PROP_MEDIA_ID, documents.getFirst().value());
    }

    /**
     * Curated (non-chat), publicly-visible library items this SITE may discover, by title -- the admin's
     * pick list. An organization's site picks from its own documents, a shared site from site-level ones.
     */
    @Override
    public List<Choice> choicesFor(final String propertyName) {
        if (!PROP_MEDIA_ID.equals(propertyName)) {
            return List.of();
        }
        final SiteContext site = SiteContext.current();
        return DAO.getInstance().getAllMedia(Cached.YES).stream()
                .filter(item -> !ChatPhotos.isChatSlot(item.getSlot()))
                .filter(item -> site.isOrg() ? site.isSiteOf(item.getOrgId()) : !item.isOrgOwned())
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
