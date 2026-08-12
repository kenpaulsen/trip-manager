package org.paulsens.trip.action;

import jakarta.faces.application.FacesMessage;
import jakarta.faces.model.SelectItem;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * {@link TripUtilCommands}' EL helpers and {@link ProfilePhotos}' presence index.
 *
 * <p>The photo index rules worth pinning: one listing seeds everybody (never a HEAD per person), absence is
 * cached as deliberately as presence, a seed FAILURE stays unseeded so the next request retries, and a media
 * event flips the answer immediately without a restart.
 */
public class UtilAndPhotosTest {

    private final TripUtilCommands util = new TripUtilCommands();

    // --- TripUtilCommands ---

    @Test
    public void severityNamesMapToTheirConstants() {
        Assert.assertEquals(util.createFacesMessage("WARN", "s", "d").getSeverity(),
                FacesMessage.SEVERITY_WARN);
        Assert.assertEquals(util.createFacesMessage("ERROR", "s", "d").getSeverity(),
                FacesMessage.SEVERITY_ERROR);
        Assert.assertEquals(util.createFacesMessage("FATAL", "s", "d").getSeverity(),
                FacesMessage.SEVERITY_FATAL);
        Assert.assertEquals(util.createFacesMessage("anything-else", "s", "d").getSeverity(),
                FacesMessage.SEVERITY_INFO);
    }

    @Test
    public void messagesOffAFacesThreadAreLoggedNotLost() {
        // No FacesContext: each of these must complete without throwing.
        util.infoMsg("s", "d");
        util.warnMsg("s", "d");
        util.errorMsg("s", "d");
        util.fatalMsg("s", "d");
        TripUtilCommands.addMessage("client-1", new FacesMessage("s"));
    }

    @Test
    public void isEmptyUnderstandsListsArraysAndNull() {
        Assert.assertTrue(util.isEmpty(null));
        Assert.assertTrue(util.isEmpty(List.of()));
        Assert.assertTrue(util.isEmpty(new Object[0]));
        Assert.assertFalse(util.isEmpty(List.of("x")));
        Assert.assertFalse(util.isEmpty(new Object[] {"x"}));
        Assert.assertFalse(util.isEmpty("a string is not a collection"));
    }

    @Test
    public void timeHelpersConvertAndFormat() {
        final LocalDateTime time = LocalDateTime.of(2027, 3, 14, 9, 26);

        Assert.assertEquals(util.formatDateTime("yyyy-MM-dd", time), "2027-03-14");
        Assert.assertNotNull(util.localDateTimeNow());
        Assert.assertNotNull(util.localDateNow());
        Assert.assertNotNull(util.getDateTimeFormatter("HH:mm"));

        final ZonedDateTime pacific = util.withTimeZone(time, "America/Los_Angeles");
        Assert.assertEquals(pacific.toInstant(), time.toInstant(ZoneOffset.UTC),
                "The instant survives the zone change");
        Assert.assertEquals(util.withTimeZone(time, null).getZone().getId(), "UTC");
        Assert.assertNull(util.withTimeZone(null, "UTC"));

        Assert.assertEquals(util.epochSecondsToUTCLocalDateTime(0L),
                LocalDateTime.of(1970, 1, 1, 0, 0));
        Assert.assertNull(util.epochSecondsToUTCLocalDateTime(null));
    }

    @Test
    public void collectionHelpersConvertShapes() {
        Assert.assertEquals(util.asList(List.of("a")), List.of("a"));
        Assert.assertEquals(util.asList(java.util.Set.of("a")), List.of("a"));
        Assert.assertEquals(util.getMapValues(Map.of("k", "v")), List.of("v"));
        Assert.assertEquals(TripUtilCommands.arrayToList(new String[] {"a", "b"}), List.of("a", "b"));
        Assert.assertEquals(TripUtilCommands.arrayToList(null), List.of());
        Assert.assertEquals(TripUtilCommands.arrayToList(new String[0]), List.of());
    }

    @Test
    public void selectItemsPairLabelsWithValues() {
        final SelectItem[] items = util.getSelectItems(List.of("Label A", "Label B"), List.of("a", "b"));

        Assert.assertEquals(items.length, 2);
        Assert.assertEquals(items[0].getLabel(), "Label A");
        Assert.assertEquals(items[0].getValue(), "a");

        Assert.assertEquals(util.getSelectItems(null, List.of()).length, 0);
        Assert.assertEquals(util.getSelectItems(List.of(), null).length, 0);
        Assert.assertThrows(IllegalArgumentException.class,
                () -> util.getSelectItems(List.of("one"), List.of()));
    }

    @Test
    public void miscHelpersBehave() {
        Assert.assertNotNull(util.sortBy("name"));
        Assert.assertNull(util.evalEL(null));
        Assert.assertThrows(RuntimeException.class, () -> util.throwException("boom"));
        // The unit suite runs in local mode by construction (see the surefire sysprop).
        Assert.assertTrue(util.isLocal());
    }

    // --- ProfilePhotos ---

    private ProfilePhotos photosWith(final MediaCommands media) throws Exception {
        final ProfilePhotos photos = new ProfilePhotos();
        final Field field = ProfilePhotos.class.getDeclaredField("media");
        field.setAccessible(true);
        field.set(photos, media);
        photos.subscribe();
        return photos;
    }

    @Test
    public void oneListingSeedsEveryAnswer() throws Exception {
        final MediaCommands media = Mockito.mock(MediaCommands.class);
        Mockito.when(media.listKeys(ProfilePhotos.PREFIX))
                .thenReturn(List.of("profilePics/p1.jpg", "profilePics/p2.jpg", "profilePics/not-a-photo.txt"));
        final ProfilePhotos photos = photosWith(media);

        Assert.assertTrue(photos.hasPhoto("p1"));
        Assert.assertTrue(photos.hasPhoto("p2"));
        Assert.assertFalse(photos.hasPhoto("p3"), "Absence is cached too");
        Assert.assertFalse(photos.hasPhoto(null));

        Mockito.verify(media, Mockito.times(1)).listKeys(ProfilePhotos.PREFIX);
    }

    /** A failed seed stays unseeded so the NEXT request retries, instead of a task-lifetime of gravatars. */
    @Test
    public void aFailedSeedRetriesOnTheNextAsk() throws Exception {
        final MediaCommands media = Mockito.mock(MediaCommands.class);
        Mockito.when(media.listKeys(ProfilePhotos.PREFIX))
                .thenThrow(new IllegalStateException("s3 down"))
                .thenReturn(List.of("profilePics/p9.jpg"));
        final ProfilePhotos photos = photosWith(media);

        Assert.assertFalse(photos.hasPhoto("p9"), "During the outage the answer is a safe no");
        Assert.assertTrue(photos.hasPhoto("p9"), "The next ask must retry the listing");
    }

    /** An upload flips the answer immediately; without the event it would wait for a restart. */
    @Test
    public void mediaEventsUpdateTheIndexWithoutARestart() throws Exception {
        final MediaCommands media = Mockito.mock(MediaCommands.class);
        Mockito.when(media.listKeys(ProfilePhotos.PREFIX)).thenReturn(List.of());
        final ProfilePhotos photos = photosWith(media);
        Assert.assertFalse(photos.hasPhoto("newbie"));

        MediaEvents.fire(MediaEvents.Change.ADDED, "profilePics/newbie.jpg");
        Assert.assertTrue(photos.hasPhoto("newbie"));

        MediaEvents.fire(MediaEvents.Change.REMOVED, "profilePics/newbie.jpg");
        Assert.assertFalse(photos.hasPhoto("newbie"));

        // Events outside the prefix are not this class's business.
        MediaEvents.fire(MediaEvents.Change.ADDED, "downloads/guide.pdf");
    }

    @Test
    public void keyParsingRejectsForeignKeys() {
        // Both key forms parse; everything else under the prefix is not a profile photo. The full parsing
        // matrix (slots, versions, malformed names) lives in ProfilePhotosSlotsTest.
        Assert.assertEquals(ProfilePhotos.legacyKeyFor("p1"), "profilePics/p1.jpg");
        Assert.assertNotNull(ProfilePhotos.parse("profilePics/p1.jpg"));
        Assert.assertNotNull(ProfilePhotos.parse(ProfilePhotos.keyFor("p1", 1, 123)));
        Assert.assertNull(ProfilePhotos.parse("downloads/x.jpg"));
        Assert.assertNull(ProfilePhotos.parse("profilePics/x.png"));
        Assert.assertNull(ProfilePhotos.parse("profilePics/.jpg"));
        Assert.assertNull(ProfilePhotos.parse(null));
    }

    @Test
    public void theUrlComesFromTheMediaLayer() throws Exception {
        final MediaCommands media = Mockito.mock(MediaCommands.class);
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        Mockito.when(media.listKeys(ProfilePhotos.PREFIX)).thenReturn(List.of("profilePics/p1.jpg"));
        Mockito.when(media.publicUrl(ArgumentMatchers.anyString()))
                .thenAnswer(call -> "https://cdn.example/" + call.getArgument(0));
        final ProfilePhotos photos = photosWith(media);

        Assert.assertEquals(photos.getUrl("p1"), "https://cdn.example/profilePics/p1.jpg");
    }
}
