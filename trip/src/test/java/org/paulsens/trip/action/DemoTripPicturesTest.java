package org.paulsens.trip.action;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.paulsens.trip.media.PhotoProcessor;
import org.paulsens.trip.model.TripPicture;
import org.testng.Assert;
import org.testng.annotations.Test;

/** The bundled local-mode trip pictures: every table entry is what the real pipeline says about its source. */
public class DemoTripPicturesTest {

    @Test
    public void everyEntryMatchesWhatThePipelineMakesOfItsSource() {
        final PhotoProcessor processor = new PhotoProcessor();
        final List<String> wrong = new ArrayList<>();
        for (final DemoTripPictures.Demo demo : DemoTripPictures.all().values()) {
            final Optional<byte[]> source = DemoTripPictures.source(demo);
            Assert.assertTrue(source.isPresent(), "bundled resource missing: " + demo.name());
            final PhotoProcessor.PictureRenditions made = processor.processPicture(source.get(), null,
                    TripPictureCommands.ASPECT, TripPicture.WIDTHS);
            final String actual = made.width() + ", " + made.height() + ", \""
                    + String.format("#%06x", made.averageRgb()) + "\"";
            final String declared = demo.width() + ", " + demo.height() + ", \"" + demo.placeholder() + "\"";
            if (!actual.equals(declared)) {
                wrong.add(demo.name() + ": declared " + declared + " but the pipeline says " + actual);
            }
        }
        Assert.assertTrue(wrong.isEmpty(), "DemoTripPictures' table is out of date: " + String.join("; ", wrong));
    }

    @Test
    public void keysNameTheirPictureAndNothingElseDoes() {
        final TripPicture picture = DemoTripPictures.pictureFor("trip-1", "st-james");
        Assert.assertEquals(picture.getKey(), "tripPictures/trip-1/demo-st-james");
        Assert.assertEquals(picture.getAlt(), "St. James Church with pilgrims gathered outside");
        Assert.assertEquals(DemoTripPictures.demoOf(picture.keyAt(480)).orElseThrow().name(), "st-james");
        Assert.assertEquals(DemoTripPictures.stemOf(picture.keyAt(480)), picture.getKey());
        Assert.assertTrue(DemoTripPictures.demoOf("tripPictures/trip-1/1794861329447-480.jpg").isEmpty(),
                "an uploaded picture is not a demo");
        Assert.assertTrue(DemoTripPictures.demoOf("tripPictures/trip-1/demo-nothing-480.jpg").isEmpty());
        Assert.assertTrue(DemoTripPictures.demoOf("tripPictures/trip-1/demo-").isEmpty());
        Assert.assertTrue(DemoTripPictures.demoOf(null).isEmpty());
        for (final int width : TripPicture.WIDTHS) {
            Assert.assertTrue(DemoTripPictures.isRendition(picture.keyAt(width)), "a real rendition: " + width);
        }
        Assert.assertFalse(DemoTripPictures.isRendition("tripPictures/trip-1/demo-st-james-1.jpg"),
                "a known name with a width nobody stores is no rendition");
        Assert.assertFalse(DemoTripPictures.isRendition("tripPictures/trip-1/demo-st-james-480.png"));
        Assert.assertFalse(DemoTripPictures.isRendition("tripPictures/trip-1/1794861329447-480.jpg"));
        Assert.assertFalse(DemoTripPictures.isRendition(null));
        Assert.expectThrows(IllegalArgumentException.class, () -> DemoTripPictures.pictureFor("trip-1", "nope"));
        Assert.assertTrue(DemoTripPictures.source(new DemoTripPictures.Demo("missing", "", 1, 1, "#000000")).isEmpty());
    }
}
