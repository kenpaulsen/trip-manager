package org.paulsens.trip.action;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.paulsens.trip.model.TripPicture;

/**
 * The trip pictures bundled for LOCAL MODE only ({@code src/main/resources/demo/trip-pictures}, owner decision
 * 2026-11-16): the pictures {@code FakeData}'s pictured trips name, so a fresh local container and the browser tests
 * show real covers, a gallery and the no-picture fallback without anyone uploading anything.
 *
 * <p>Only the SOURCE of each picture is bundled, already cut to 3:2 and stripped of metadata. Its three renditions
 * are made by the real pipeline the first time one is asked for ({@code TripPictureCommands.localGet}), so a demo
 * picture is served exactly like an uploaded one. Each entry's width, height and placeholder are what that pipeline
 * answers for its source, which {@code DemoTripPicturesTest} holds it to.
 *
 * <p>A demo picture's key stem is {@code tripPictures/{tripId}/demo-{name}}: the {@code demo-} marker is how the
 * local store tells a bundled picture from an uploaded one, whose stem is a number.
 */
public final class DemoTripPictures {

    /** One bundled picture: its resource name, description, and the pipeline's facts about it. */
    public record Demo(String name, String alt, int width, int height, String placeholder) {
    }

    /** What separates a demo picture's name from the trip id in its key stem. */
    static final String MARKER = "demo-";

    private static final String RESOURCE_DIR = "/demo/trip-pictures/";

    private static final Map<String, Demo> BY_NAME = new LinkedHashMap<>();

    static {
        add("apparition-hill-sunset", "The statue of Our Lady on Apparition Hill at sunset", 1920, 1280, "#59564f");
        add("st-james", "St. James Church with pilgrims gathered outside", 960, 640, "#616c72");
        add("valley", "The valley seen from the hill", 960, 640, "#8d9282");
        add("outdoor-altar", "Evening Mass at the outdoor altar", 960, 640, "#6e7071");
        add("candles", "Candles lit at the foot of the cross", 960, 640, "#721008");
        add("stone-church", "A stone church among the olive trees", 960, 640, "#686c78");
        add("monastery", "A monastery in the hills", 960, 640, "#767a7a");
        add("sheep", "Sheep grazing beside the road", 960, 640, "#665b3a");
        add("evening-hill", "Dusk over the village from Apparition Hill", 960, 640, "#5c5f5f");
        add("surf-cup", "Surf Cup Sports Park", 809, 539, "#463015");
        add("soccer-warmup", "Warmups before the first whistle", 960, 640, "#ab8242");
        add("soccer-match", "Group stage, last winter", 960, 640, "#b48a4c");
        add("cusco-plaza", "Plaza de Armas, Cusco", 960, 640, "#391705");
        add("ballestas-arch", "A sea arch at the Ballestas Islands", 960, 640, "#6b6969");
        add("huacachina-dunes", "The dunes above Huacachina", 960, 640, "#969291");
        add("andes-train", "The valley on the way up", 960, 640, "#708ca7");
    }

    private DemoTripPictures() {
    }

    private static void add(final String name, final String alt, final int width, final int height,
            final String placeholder) {
        BY_NAME.put(name, new Demo(name, alt, width, height, placeholder));
    }

    /** Every bundled picture, in declaration order. */
    public static Map<String, Demo> all() {
        return Collections.unmodifiableMap(BY_NAME);
    }

    /** The trip-row entry for one bundled picture on one trip. */
    public static TripPicture pictureFor(final String tripId, final String name) {
        final Demo demo = BY_NAME.get(name);
        if (demo == null) {
            throw new IllegalArgumentException("No bundled demo picture named " + name);
        }
        return new TripPicture(TripPictureCommands.PREFIX + tripId + "/" + MARKER + name, demo.alt(), demo.width(),
                demo.height(), demo.placeholder());
    }

    /**
     * The bundled picture a rendition key names ({@code tripPictures/{tripId}/demo-{name}-{width}.jpg}), or empty
     * for any other key.
     */
    static Optional<Demo> demoOf(final String key) {
        if (key == null) {
            return Optional.empty();
        }
        final int marker = key.lastIndexOf("/" + MARKER);
        final int width = key.lastIndexOf('-');
        if (marker < 0 || width <= marker + MARKER.length() + 1) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_NAME.get(key.substring(marker + 1 + MARKER.length(), width)));
    }

    /**
     * Whether a key is EXACTLY one stored rendition of a bundled picture: a known name and one of the three
     * widths. {@code .../demo-valley-1.jpg} names a bundled picture but is no rendition of it.
     */
    static boolean isRendition(final String key) {
        return demoOf(key).isPresent() && TripPicture.WIDTHS.stream()
                .anyMatch(width -> TripPicture.variantKey(stemOf(key), width).equals(key));
    }

    /** The key stem ({@code .../demo-{name}}) of a demo rendition key; only meaningful when {@link #demoOf} is. */
    static String stemOf(final String key) {
        return key.substring(0, key.lastIndexOf('-'));
    }

    /** The bundled source bytes of one picture; empty when the resource is missing or unreadable. */
    public static Optional<byte[]> source(final Demo demo) {
        try (InputStream in = DemoTripPictures.class.getResourceAsStream(RESOURCE_DIR + demo.name() + ".jpg")) {
            return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
        } catch (final IOException ex) {
            return Optional.empty();
        }
    }
}
