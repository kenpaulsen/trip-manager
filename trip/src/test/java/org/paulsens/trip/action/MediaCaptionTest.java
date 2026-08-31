package org.paulsens.trip.action;

import java.time.LocalDateTime;
import org.paulsens.trip.model.MediaItem;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * {@link MediaCommands#photoCaption}: the landing galleria's caption must be the uploader's title, and the
 * empty string — never the raw generated filename an untitled upload stores as its title (the page hides
 * an empty caption; a filename shown under a photo reads as debris).
 */
public class MediaCaptionTest {

    private final MediaCommands media = new MediaCommands();

    private static MediaItem photo(final String title) {
        return new MediaItem("cap-1", "chat/trip/20260830-212714549-m457ag.jpg", title, null,
                "image/jpeg", 100L, "tripChat-trip", 0, LocalDateTime.now(), "seed", null, null);
    }

    @Test
    public void aRealTitleIsTheCaption() {
        Assert.assertEquals(media.photoCaption(photo("Our Lady at sunset")), "Our Lady at sunset");
        Assert.assertEquals(media.photoCaption(photo("  padded  ")), "padded");
    }

    @Test
    public void aGeneratedFilenameTitleIsSuppressed() {
        Assert.assertEquals(media.photoCaption(photo("20260830-212714549-m457ag.jpg")), "");
        Assert.assertEquals(media.photoCaption(photo("20260812-215251314-69exwr.jpg")), "");
        Assert.assertEquals(media.photoCaption(photo("20260830-212714549-m457ag-small.jpg")), "");
    }

    @Test
    public void nearMissesKeepTheirCaption() {
        // A title that merely CONTAINS or resembles a filename is someone's actual words: keep it.
        Assert.assertEquals(media.photoCaption(photo("copy of 20260830-212714549-m457ag.jpg")),
                "copy of 20260830-212714549-m457ag.jpg");
        Assert.assertEquals(media.photoCaption(photo("IMG_4021.jpg")), "IMG_4021.jpg");
    }

    @Test
    public void nullsAreEmptyNeverAnError() {
        Assert.assertEquals(media.photoCaption(null), "");
        Assert.assertEquals(media.photoCaption(photo(null)), "");
    }
}
