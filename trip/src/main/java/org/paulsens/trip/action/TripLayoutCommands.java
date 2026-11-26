package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.paulsens.trip.api.Beans;
import org.paulsens.trip.content.StarterTemplates;
import org.paulsens.trip.content.TripLayout;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripPicture;
import org.paulsens.trip.util.Html;

/**
 * The trip page's two live sections drawn through their template's LAYOUT body (docs/content-templates.md, "Live
 * sections with a layout"): Trip Details ({@code trip-details}) and Trip Detail Pictures ({@code trip-pictures}).
 * This bean gathers one trip's values for {@link TripLayout}, which fills them in; the fragments
 * ({@code WEB-INF/ptypes/tripFacts.xhtml}, {@code ptypes/tripPictures.xhtml}) write the two halves around the
 * gallery component. A blank body ({@link Layout#set} false) leaves the fragments drawing their built-in markup,
 * exactly as before layouts.
 *
 * <p>Also each section's Tone (the bands' background menu), as the class its wrapper wears. Render-only and
 * anonymous-safe: cached reads, no session or view state, one build per section per request.
 */
@Named("tripLayout")
@ApplicationScoped
public class TripLayoutCommands {

    /** A placeholder color as the picture pipeline stores it; anything else is left out of the style attribute. */
    private static final Pattern COLOR = Pattern.compile("#[0-9A-Fa-f]{3,8}");
    /** The cover's width in its {@code src}, as the built-in hero asks for it. */
    private static final int COVER_WIDTH = 960;

    /**
     * One section's layout for one trip: {@code set} when its template has a body; the filled HTML before and after
     * the gallery; {@code gallery} when the gallery component goes between them.
     */
    public record Layout(boolean set, String before, String after, boolean gallery) {
        /** No layout body: the fragment draws its built-in markup. */
        static final Layout NONE = new Layout(false, "", "", false);
    }

    private final Supplier<TripPageCommands> pages;
    private final Supplier<TripPictureCommands> pictures;
    private final Supplier<TripCommands> trips;
    private final Function<ContentInstance, String> bodies;
    private final Function<String, String> labels;
    private final Supplier<Map<String, Object>> requestMap;

    public TripLayoutCommands() {
        this(() -> Beans.get(TripPageCommands.class), () -> Beans.get(TripPictureCommands.class),
                () -> Beans.get(TripCommands.class), TripLayoutCommands::storedLayout, Text::get,
                RequestMemo::facesRequestMap);
    }

    /** Every collaborator explicit: the test seam (each one a bean or a bundle a unit test cannot look up). */
    TripLayoutCommands(final Supplier<TripPageCommands> pages, final Supplier<TripPictureCommands> pictures,
            final Supplier<TripCommands> trips, final Function<ContentInstance, String> bodies,
            final Function<String, String> labels, final Supplier<Map<String, Object>> requestMap) {
        this.pages = pages;
        this.pictures = pictures;
        this.trips = trips;
        this.bodies = bodies;
        this.labels = labels;
        this.requestMap = requestMap;
    }

    private static String storedLayout(final ContentInstance instance) {
        return Beans.get(ContentCommands.class).layoutBody(instance);
    }

    /**
     * A Trip Details section's layout for {@code trip} (the fragment is {@code ptypes/tripFacts.xhtml}, whatever the
     * template's own id: a template of this type made under another id draws through it too). Not set for a missing
     * section or trip, or a blank body.
     */
    public Layout facts(final ContentInstance instance, final Trip trip) {
        if (instance == null || trip == null) {
            return Layout.NONE;
        }
        return RequestMemo.get(requestMap.get(), "tripLayout:facts:" + instance.getId() + ":" + trip.getId(),
                () -> build(instance, () -> factsData(instance, trip)));
    }

    /**
     * A Trip Detail Pictures section's layout for {@code trip}, {@code sizes} being the hero picture's sizes
     * attribute (the fragment's, from {@link TripPictureCommands#wideSizes}).
     */
    public Layout pictures(final ContentInstance instance, final Trip trip, final String sizes) {
        if (instance == null || trip == null) {
            return Layout.NONE;
        }
        final String key = "tripLayout:pictures:" + instance.getId() + ":" + trip.getId() + ":" + sizes;
        return RequestMemo.get(requestMap.get(), key, () -> build(instance, () -> picturesData(instance, trip, sizes)));
    }

    /** {@code base} with the section's Tone class added ({@link TripLayout#toneClass}), or {@code base} alone. */
    public String withTone(final String base, final ContentInstance instance) {
        final String tone = instance == null
                ? "" : TripLayout.toneClass(instance.getValues().get(TripLayout.PROP_TONE));
        return tone.isEmpty() ? base : base + " " + tone;
    }

    private Layout build(final ContentInstance instance, final Supplier<TripLayout.Data> values) {
        final String body = bodies.apply(instance);
        if (body == null || body.isBlank()) {
            return Layout.NONE;
        }
        final TripLayout.Data data = values.get();
        final TripLayout.Rendered rendered = TripLayout.render(body, data);
        return new Layout(true, rendered.before(), rendered.after(),
                rendered.slot() && data.blocks().contains(TripLayout.GALLERY));
    }

    private TripLayout.Data factsData(final ContentInstance instance, final Trip trip) {
        final TripPageCommands page = pages.get();
        final Map<String, String> text = new HashMap<>();
        text.put("dates", page.factDates(trip));
        text.put("destination", trip.getDestination());
        text.put("director", trip.getDirector());
        text.put("guide", trip.getLocalGuide());
        text.put("coordinators", trip.getFacilitators());
        text.put("price", trip.getEstimatedPrice());
        text.put("flyerUrl", trips.get().flyerUrlOf(trip));
        text.put("directorLabel", labels.apply("common.spiritualDirector"));
        text.put("guideLabel", labels.apply("trip.page.guide"));
        text.put("coordinatorsLabel", labels.apply("common.coordinators"));
        text.put("priceLabel", labels.apply("trip.page.estimatedPrice"));
        text.put("priceTerms", labels.apply("trip.page.priceTerms"));
        text.put("flyerLabel", labels.apply("trip.page.flyer"));
        final Set<String> blocks = new LinkedHashSet<>();
        for (final TripPageCommands.Fact fact : TripPageCommands.FACTS) {
            if (page.showsFact(instance, fact.value()) && page.hasFact(trip, fact.value())) {
                blocks.add(fact.value());
            }
        }
        return new TripLayout.Data(text, Map.of(), blocks, properties(instance));
    }

    private TripLayout.Data picturesData(final ContentInstance instance, final Trip trip, final String sizes) {
        final List<TripPicture> all = trip.getPictures();
        final TripPicture cover = trip.getCover();
        final Map<String, String> text = new HashMap<>();
        final Map<String, String> html = new HashMap<>();
        final Set<String> blocks = new LinkedHashSet<>();
        if (cover != null) {
            final TripPictureCommands pics = pictures.get();
            text.put("coverUrl", pics.url(cover, COVER_WIDTH));
            text.put("coverAlt", pics.altOf(cover, trip));
            text.put("coverColor", colorOf(cover));
            html.put("cover", coverImg(pics, cover, trip, sizes));
            final boolean coverOnly = pages.get().coverOnly(instance);
            blocks.add(all.size() == 1 || coverOnly ? "single" : TripLayout.GALLERY);
        }
        return new TripLayout.Data(text, html, blocks, properties(instance));
    }

    /**
     * The cover as the built-in hero draws it ({@code WEB-INF/tripTopPictures.xhtml}): every attribute escaped, as
     * the page's own EL would have.
     */
    private static String coverImg(final TripPictureCommands pics, final TripPicture cover, final Trip trip,
            final String sizes) {
        return "<img class=\"tripCardImg\" src=\"" + Html.escape(pics.url(cover, COVER_WIDTH))
                + "\" srcset=\"" + Html.escape(pics.srcset(cover)) + "\" sizes=\"" + Html.escape(sizes)
                + "\" width=\"" + cover.getWidth() + "\" height=\"" + cover.getHeight()
                + "\" alt=\"" + Html.escape(pics.altOf(cover, trip)) + "\" loading=\"eager\" />";
    }

    /** The cover's placeholder color when it is one, else "": it goes into a style attribute. */
    static String colorOf(final TripPicture picture) {
        final String color = picture.getPlaceholder();
        return color != null && COLOR.matcher(color).matches() ? color : "";
    }

    /** The section's own properties a layout may use: its Tone, screened to the tones. */
    private static Map<String, String> properties(final ContentInstance instance) {
        final String tone = instance.getValues().get(TripLayout.PROP_TONE);
        return Map.of(TripLayout.PROP_TONE, tone != null && StarterTemplates.TONES.contains(tone) ? tone : "");
    }
}
