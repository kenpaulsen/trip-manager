package org.paulsens.trip.content;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.paulsens.trip.action.ChatPhotos;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Placeholder;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.site.SiteContext;

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

    @Override
    public String getFragmentPath() {
        return "/WEB-INF/ptypes/file.xhtml";
    }

    @Override
    public String getSampleHtml() {
        return """
                <ul class="qopBullets" style="font-size:1.15em;margin:2px 0;">
                    <li><a>Parish newsletter</a><span style="font-size:0.85em;opacity:0.8"> - 240 KB</span></li>
                </ul>
                """;
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
