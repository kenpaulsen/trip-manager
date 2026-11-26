package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.paulsens.trip.api.Beans;
import org.paulsens.trip.content.TripLayout;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.MediaItem;

/**
 * A File item drawn through its template's LAYOUT body (docs/content-templates.md, "Live sections with a layout"): the
 * HTML a template author writes around one document's {@code {{file:title}}}, {@code {{file:url}}},
 * {@code {{file:size}}} and {@code {{file:description}}}, with a {@code {{#file:description}}} block kept only when the
 * document has one. This bean gathers the document's values for {@link TripLayout}, which fills them in; the fragment
 * ({@code WEB-INF/ptypes/file.xhtml}) writes the result. A blank body ({@link Layout#set} false) leaves the fragment
 * drawing its built-in link and size.
 *
 * <p>Render-only and anonymous-safe: cached reads, no session or view state, one build per item per request.
 */
@Named("fileLayout")
@ApplicationScoped
public class FileLayoutCommands {

    /** One File's layout: {@code set} when its template has a body, and the filled HTML. */
    public record Layout(boolean set, String html) {
        /** No layout body: the fragment draws its built-in markup. */
        static final Layout NONE = new Layout(false, "");
    }

    private final Function<ContentInstance, String> bodies;
    private final Function<MediaItem, String> urls;
    private final Supplier<Map<String, Object>> requestMap;

    public FileLayoutCommands() {
        this(FileLayoutCommands::storedLayout, item -> Beans.get(MediaCommands.class).getUrl(item),
                RequestMemo::facesRequestMap);
    }

    /** Every collaborator explicit: the test seam (each one a bean a unit test cannot look up). */
    FileLayoutCommands(final Function<ContentInstance, String> bodies, final Function<MediaItem, String> urls,
            final Supplier<Map<String, Object>> requestMap) {
        this.bodies = bodies;
        this.urls = urls;
        this.requestMap = requestMap;
    }

    private static String storedLayout(final ContentInstance instance) {
        return Beans.get(ContentCommands.class).layoutBody(instance);
    }

    /** The layout of {@code instance} for {@code doc}, its visible document; not set for either missing. */
    public Layout of(final ContentInstance instance, final MediaItem doc) {
        if (instance == null || doc == null) {
            return Layout.NONE;
        }
        return RequestMemo.get(requestMap.get(), "fileLayout:" + instance.getId() + ":" + doc.getId(),
                () -> build(instance, doc));
    }

    private Layout build(final ContentInstance instance, final MediaItem doc) {
        final String body = bodies.apply(instance);
        if (body == null || body.isBlank()) {
            return Layout.NONE;
        }
        final Map<String, String> text = new HashMap<>();
        text.put("title", doc.getTitle());
        text.put("url", urls.apply(doc));
        text.put("size", doc.getDisplaySize());
        text.put("description", doc.getDescription());
        final boolean described = doc.getDescription() != null && !doc.getDescription().isBlank();
        final TripLayout.Data data = new TripLayout.Data(text, Map.of(), described ? Set.of("description") : Set.of(),
                Map.of());
        return new Layout(true, TripLayout.render(TripLayout.FILE, body, data).before());
    }
}
