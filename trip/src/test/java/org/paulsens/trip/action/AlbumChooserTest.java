package org.paulsens.trip.action;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The one "Choose from Albums" dialog's dispatcher: a use NAME from the page decides, here, which bean lists the
 * albums, how many may be chosen and which bean takes the picks; a name it does not know gets nothing.
 */
public class AlbumChooserTest {

    private final Map<String, Object> params = new HashMap<>();
    private final List<String> refreshes = new ArrayList<>();
    private AlbumPhotoSource albums;
    private AlbumLibrary library;
    private TripPictureCommands tripPictures;
    private BadgePhotoCommands badges;
    private ProfilePhotoCommands profiles;
    private BrandingUploadCommands branding;
    private LodgingUploadCommands lodging;
    private ChatPhotoUpload chat;
    private AlbumChooser chooser;

    @BeforeMethod
    public void setUp() {
        params.clear();
        refreshes.clear();
        albums = Mockito.mock(AlbumPhotoSource.class);
        library = Mockito.mock(AlbumLibrary.class);
        tripPictures = Mockito.mock(TripPictureCommands.class);
        badges = Mockito.mock(BadgePhotoCommands.class);
        profiles = Mockito.mock(ProfilePhotoCommands.class);
        branding = Mockito.mock(BrandingUploadCommands.class);
        lodging = Mockito.mock(LodgingUploadCommands.class);
        chat = Mockito.mock(ChatPhotoUpload.class);
        Mockito.when(tripPictures.getAlbumScope()).thenReturn("trip:t1");
        Mockito.when(badges.getAlbumScope()).thenReturn("trip:t2");
        Mockito.when(profiles.getAlbumScope()).thenReturn(AlbumPhotoSource.SCOPE_MINE);
        Mockito.when(branding.getAlbumScope()).thenReturn("org:o1");
        Mockito.when(lodging.getAlbumScope()).thenReturn(AlbumPhotoSource.SCOPE_MINE);
        Mockito.when(chat.getAlbumScope()).thenReturn("trip:t3");
        Mockito.when(library.getAlbumScope()).thenReturn(AlbumPhotoSource.SCOPE_SITE);
        Mockito.when(library.mayAdd()).thenReturn(true);
        chooser = new AlbumChooser() {
            @Override
            protected void refreshCropSteps() {
                refreshes.add("crop");
            }

            @Override
            protected void publish(final String name, final Object value) {
                params.put(name, value);
            }
        };
        chooser.wireForTest(albums, library, tripPictures, badges, profiles, branding, lodging, chat);
    }

    @Test
    public void eachUseListsItsAlbumsInItsOwnHostsScope() {
        Assert.assertEquals(chooser.scopeOf("tripPictures"), "trip:t1");
        Assert.assertEquals(chooser.scopeOf("badge"), "trip:t2");
        Assert.assertEquals(chooser.scopeOf("profile"), AlbumPhotoSource.SCOPE_MINE);
        Assert.assertEquals(chooser.scopeOf("linkPreview"), "org:o1");
        Assert.assertEquals(chooser.scopeOf("lodging"), AlbumPhotoSource.SCOPE_MINE);
        Assert.assertEquals(chooser.scopeOf("library"), AlbumPhotoSource.SCOPE_SITE);
        Assert.assertEquals(chooser.scopeOf("url"), AlbumPhotoSource.SCOPE_SITE);
        Assert.assertEquals(chooser.scopeOf("chat"), "trip:t3");
        Assert.assertNull(chooser.scopeOf("everything"), "A use nobody defined has no albums");
        Assert.assertNull(chooser.scopeOf(null));

        // Who may list is each host's own answer (AlbumLibraryTest proves the library's): the chooser passes it on.
        Mockito.when(library.getAlbumScope()).thenReturn(null);
        Assert.assertNull(chooser.scopeOf("library"), "Someone the library refuses is shown no albums");
        Assert.assertNull(chooser.scopeOf("url"));

        final List<AlbumPhotoSource.Album> listed = List.of(
                new AlbumPhotoSource.Album("t9", "Nine", "", false, List.of(), 0, null));
        Mockito.when(albums.albumsOf("trip:t2")).thenReturn(listed);
        Mockito.when(albums.albumOf("trip:t2", "t9")).thenReturn(listed.getFirst());
        Assert.assertEquals(chooser.albumsOf("badge"), listed);
        Assert.assertEquals(chooser.albumOf("badge", "t9"), listed.getFirst());
        for (final AlbumChooser.Use use : AlbumChooser.Use.values()) {
            Assert.assertEquals(AlbumChooser.Use.of(use.getKey()).orElseThrow(), use);
        }
    }

    @Test
    public void theRoomIsTheHostsToSay() {
        Mockito.when(tripPictures.targetId()).thenReturn("t1");
        Mockito.when(tripPictures.roomFor("t1")).thenReturn(4);
        Assert.assertEquals(chooser.roomOf("tripPictures"), 4, "What the trip still has room for");
        Assert.assertEquals(chooser.roomOf("library"), AlbumLibrary.MAX_PER_ADD);
        Assert.assertEquals(chooser.roomOf("chat"), AlbumLibrary.MAX_PER_ADD,
                "The page lowers it to its free slots, and the send holds the cap");
        Assert.assertEquals(chooser.roomOf("profile"), 1);
        Assert.assertEquals(chooser.roomOf("url"), 1);
        Assert.assertEquals(chooser.roomOf("nonsense"), 0);
    }

    @Test
    public void addGoesToTheTripsPicturesOrTheLibraryAndNowhereElse() {
        chooser.add("tripPictures", "a@0.5,0.5");
        Mockito.verify(tripPictures).addFromAlbumUi("a@0.5,0.5");
        chooser.add("library", "b@0.5,0.5");
        Mockito.verify(library).addFromAlbumUi("b@0.5,0.5");

        chooser.add("badge", "c@0.5,0.5");
        Assert.assertEquals(params.get("albumAdded"), false, "A one-photo use takes no batch");
        params.clear();
        chooser.add("nonsense", "c@0.5,0.5");
        Assert.assertEquals(params.get("albumAdded"), false);
        chooser.add(null, "c@0.5,0.5");
        Mockito.verifyNoMoreInteractions(tripPictures, library);
    }

    @Test
    public void aPickGoesToTheUsesOwnCropHostOrBecomesALibraryUrl() {
        chooser.pick("badge", "m1");
        Mockito.verify(badges).pickFromAlbum("m1");
        chooser.pick("profile", "m2");
        Mockito.verify(profiles).pickFromAlbum("m2");
        chooser.pick("linkPreview", "m3");
        Mockito.verify(branding).pickFromAlbum("m3");
        chooser.pick("lodging", "m4");
        Mockito.verify(lodging).pickFromAlbum("m4");
        Assert.assertEquals(refreshes.size(), 4, "Each crop host's dialog steps are re-rendered with the pick");

        chooser.pick("url", "m5");
        Mockito.verify(library).pickFromAlbum("m5");
        Assert.assertEquals(refreshes.size(), 4, "A URL field has no crop dialog");

        chooser.pick("tripPictures", "m6");
        Assert.assertEquals(params.get("albumPicked"), false, "A use that takes several is not picked one by one");
        params.clear();
        chooser.pick("chat", "m7");
        Assert.assertEquals(params.get("albumPicked"), false, "Chat's choices never come here: its page sends them");
        params.clear();
        chooser.pick("nonsense", "m8");
        Assert.assertEquals(params.get("albumPicked"), false);
        Mockito.verify(tripPictures, Mockito.never()).pickFromAlbum(Mockito.anyString());
        Mockito.verify(chat, Mockito.never()).pickFromAlbum(Mockito.anyString());
    }

    @Test
    public void theRealSeamsDoNothingOffAPage() {
        final AlbumChooser real = new AlbumChooser();
        real.wireForTest(albums, library, tripPictures, badges, profiles, branding, lodging, chat);
        real.pick("badge", "m1");
        Mockito.verify(badges).pickFromAlbum("m1");
        real.add("nonsense", "x");
    }

    @Test
    public void aRequestAsksEachHostForItsScopeOnce() {
        final Map<String, Object> request = new HashMap<>();
        final AlbumChooser memoized = new AlbumChooser() {
            @Override
            protected Map<String, Object> requestMap() {
                return request;
            }
        };
        memoized.wireForTest(albums, library, tripPictures, badges, profiles, branding, lodging, chat);
        for (int i = 0; i < 5; i++) {
            Assert.assertEquals(memoized.scopeOf("chat"), "trip:t3");
            Assert.assertNull(memoized.scopeOf("everything"));
        }
        Mockito.verify(chat, Mockito.times(1)).getAlbumScope();
        request.clear();
        Assert.assertEquals(memoized.scopeOf("chat"), "trip:t3", "The next request asks again");
        Mockito.verify(chat, Mockito.times(2)).getAlbumScope();
        Assert.assertNull(new AlbumChooser().requestMap(), "Off a page there is no request to remember in");
    }
}
