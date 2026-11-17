package org.paulsens.trip.action;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.media.PhotoFixtures;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Album photos copied into the media library: who may, that a copy is the whole photo re-encoded under the owner's
 * own folder, what the library row says, every refusal, and the two ways a page asks (several for the library
 * page, one for a field that takes an image by URL). The album rules themselves are {@code AlbumPhotoSourceTest}'s:
 * here the source is a mock that offers exactly what a test says.
 */
public class AlbumLibraryTest {

    private static final String ORG = "5f0c2a4e-9d31-4a8e-8f34-6d2f19c7b0a1";

    private final Map<String, Object> params = new HashMap<>();
    private final List<MediaItem> rows = new ArrayList<>();
    private Person.Id personId;
    private PrivilegeCommands priv;
    private Caller caller;
    private String siteOrg;
    private boolean orgAdmin;
    private boolean recordResult;
    private MediaCommands media;
    private AlbumPhotoSource albums;
    private AlbumLibrary library;

    @BeforeMethod
    public void setUp() {
        params.clear();
        rows.clear();
        personId = Person.Id.newInstance();
        priv = Mockito.mock(PrivilegeCommands.class);
        caller = new Caller(personId, true, new AuditActor("admin@example.com", personId.getValue()), priv);
        siteOrg = ORG;
        orgAdmin = false;
        recordResult = true;
        media = Mockito.mock(MediaCommands.class);
        Mockito.when(media.isUploadEnabled()).thenReturn(false);
        Mockito.when(media.getUrl(Mockito.any())).thenAnswer(call ->
                AlbumLibrary.LOCAL_PATH + ((MediaItem) call.getArgument(0)).getS3Key());
        albums = Mockito.mock(AlbumPhotoSource.class);
        library = new AlbumLibrary() {
            @Override
            protected Caller caller() {
                return caller;
            }

            @Override
            protected String siteOrgId() {
                return siteOrg;
            }

            @Override
            protected boolean isOrgAdmin(final Caller who, final String orgId) {
                return orgAdmin && orgId != null;
            }

            @Override
            protected boolean record(final MediaItem item) {
                rows.add(item);
                return recordResult;
            }

            @Override
            protected void publish(final String name, final Object value) {
                params.put(name, value);
            }

            @Override
            protected String requestOrigin() {
                return "http://localhost:8080";
            }
        };
        library.wireForTest(media, albums);
    }

    private MediaItem offer(final String mediaId, final String title, final byte[] bytes) {
        final MediaItem photo = new MediaItem(mediaId, "chat/t/" + mediaId + ".jpg", title, "Uploaded by Trinity",
                "image/jpeg", 1L, "tripChat-t", 0, null, "someone");
        Mockito.when(albums.offered(caller, AlbumPhotoSource.SCOPE_SITE, mediaId)).thenReturn(Optional.of(photo));
        Mockito.when(albums.bytesOf(photo)).thenReturn(Optional.ofNullable(bytes));
        return photo;
    }

    private BufferedImage stored(final MediaItem item) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(library.localGet(item.getS3Key()).orElseThrow()));
    }

    private Caller plain() {
        return new Caller(personId, false, new AuditActor("someone@example.com", personId.getValue()), priv);
    }

    // --- who ---

    @Test
    public void mediaAdminsContentEditorsAndOrgAdminsMayCopyAndNobodyElse() {
        Assert.assertTrue(library.mayAdd(), "A site admin holds every privilege");
        Assert.assertEquals(library.getAlbumScope(), AlbumPhotoSource.SCOPE_SITE);

        caller = plain();
        Assert.assertFalse(library.mayAdd(), "A plain member may not");
        Assert.assertFalse(library.mayAdd(null));
        Assert.assertFalse(library.mayAdd(new Caller(null, false, AuditActor.from(null), priv)), "Anonymous may not");

        orgAdmin = true;
        Assert.assertTrue(library.mayAdd(), "An admin of this site's organization may");
        siteOrg = null;
        Assert.assertFalse(library.mayAdd(), "A shared site's library has no organization to be an admin of");

        offer("m1", "Sunrise", PhotoFixtures.jpeg(1200, 800));
        final AlbumLibrary.CopyResult refused = library.copy(caller, List.of("m1"));
        Assert.assertTrue(refused.items().isEmpty());
        Assert.assertEquals(refused.refusal(), "Not allowed: you may not add to this site's media library.");
        Assert.assertTrue(rows.isEmpty());
    }

    // --- the copy ---

    @Test
    public void aCopyIsTheWholePhotoReEncodedUnderTheOwnersAlbumFolder() throws Exception {
        offer("m1", "Sunrise at the hill", PhotoFixtures.jpeg(3600, 2400));
        offer("m2", "m2.jpg", PhotoFixtures.png(600, 900, true));

        final AlbumLibrary.CopyResult result = library.copy(caller, List.of("m1", "m2"));
        Assert.assertNull(result.refusal());
        Assert.assertEquals(result.items().size(), 2);

        final MediaItem first = result.items().getFirst();
        Assert.assertTrue(first.getS3Key().matches("org/" + ORG + "/album-photos/[0-9]+\\.jpg"), first.getS3Key());
        Assert.assertTrue(AlbumLibrary.isAlbumCopyKey(first.getS3Key()));
        Assert.assertEquals(first.getOrgId(), ORG);
        Assert.assertEquals(first.getContentType(), "image/jpeg");
        Assert.assertEquals(first.getTitle(), "Sunrise at the hill");
        Assert.assertEquals(first.getDescription(), "Uploaded by Trinity");
        Assert.assertNull(first.getSlot(), "A library item, in no slot");
        Assert.assertFalse(first.getHidden());
        Assert.assertEquals(first.getUploadedBy(), "admin@example.com");

        final BufferedImage wide = stored(first);
        Assert.assertEquals(wide.getWidth(), AlbumLibrary.MAX_WIDTH, "Scaled down to the widest a copy is stored");
        Assert.assertEquals(wide.getHeight(), AlbumLibrary.MAX_WIDTH * 2 / 3, "Whole: the photo's own shape");

        final MediaItem second = result.items().get(1);
        Assert.assertEquals(second.getTitle(), "Album photo", "A title that is only the file's name is no title");
        Assert.assertNotEquals(second.getS3Key(), first.getS3Key(), "Two copies in one millisecond never share a key");
        final BufferedImage tall = stored(second);
        Assert.assertEquals(tall.getWidth(), 600, "Never enlarged");
        Assert.assertEquals(tall.getHeight(), 900);
        Assert.assertEquals(rows.size(), 2);
    }

    @Test
    public void aSharedSitesCopyIsSiteLevelAndGoesToTheBucketWhenThereIsOne() {
        siteOrg = null;
        Mockito.when(media.isUploadEnabled()).thenReturn(true);
        Mockito.when(media.putObject(Mockito.anyString(), Mockito.any(), Mockito.eq("image/jpeg"),
                Mockito.eq(AlbumLibrary.CACHE_SECONDS), Mockito.eq(false))).thenReturn(true);
        offer("m1", null, PhotoFixtures.jpeg(1200, 800));

        final MediaItem item = library.copy(caller, List.of("m1")).items().getFirst();
        Assert.assertTrue(item.getS3Key().matches("album-photos/[0-9]+\\.jpg"), item.getS3Key());
        Assert.assertNull(item.getOrgId());
        Assert.assertEquals(item.getTitle(), "Album photo");
        Assert.assertTrue(library.localGet(item.getS3Key()).isEmpty(), "Stored in the bucket, not here");

        Mockito.when(media.putObject(Mockito.anyString(), Mockito.any(), Mockito.anyString(), Mockito.anyLong(),
                Mockito.anyBoolean())).thenReturn(false);
        final AlbumLibrary.CopyResult failed = library.copy(caller, List.of("m1"));
        Assert.assertTrue(failed.items().isEmpty());
        Assert.assertEquals(failed.refusal(), "Not stored: the photo could not be stored. Try again.");
    }

    @Test
    public void everyRefusalIsNamedAndTheOthersStillGoIn() {
        offer("good", "Good", PhotoFixtures.jpeg(1200, 800));
        offer("unreadable", "Gone", null);
        offer("garbage", "Not a picture", new byte[] {1, 2, 3});

        AlbumLibrary.CopyResult result = library.copy(caller, List.of("someone-elses", "good"));
        Assert.assertEquals(result.items().size(), 1);
        Assert.assertEquals(result.refusal(), "Not available: that album photo cannot be used here.");

        result = library.copy(caller, List.of("unreadable"));
        Assert.assertEquals(result.refusal(), "Not read: an album photo could not be read. Try again.");
        result = library.copy(caller, List.of("garbage", "unreadable"));
        Assert.assertTrue(result.refusal().startsWith("Photo rejected: "), "The FIRST reason: " + result.refusal());

        recordResult = false;
        result = library.copy(caller, List.of("good"));
        Assert.assertTrue(result.items().isEmpty());
        Assert.assertEquals(result.refusal(),
                "Not listed: the photo was stored but could not be added to the library.");
    }

    @Test
    public void oneAddCopiesAtMostTheBatchSize() {
        final List<String> ids = new ArrayList<>();
        for (int i = 0; i <= AlbumLibrary.MAX_PER_ADD; i++) {
            ids.add("p" + i);
            offer("p" + i, "Photo " + i, PhotoFixtures.jpeg(300, 200));
        }
        final AlbumLibrary.CopyResult result = library.copy(caller, ids);
        Assert.assertEquals(result.items().size(), AlbumLibrary.MAX_PER_ADD);
        Assert.assertEquals(result.refusal(), "Too many: at most 20 photos can be added at once.");
    }

    // --- the two ways a page asks ---

    @Test
    public void theLibraryPagesAddReportsWhatWentInAndWhatDidNot() {
        offer("a", "A", PhotoFixtures.jpeg(600, 400));
        offer("b", "B", PhotoFixtures.jpeg(600, 400));

        library.addFromAlbumUi(" ");
        Assert.assertEquals(params.get("albumAdded"), false, "Nothing chosen");

        library.addFromAlbumUi("a@0.5,0.5");
        Assert.assertEquals(params.get("albumAdded"), true);
        Assert.assertEquals(params.get("albumDone"), "Photo added to the library.");
        Assert.assertNull(params.get("albumNote"));

        params.clear();
        library.addFromAlbumUi("a@0.1,0.9;b@0.5,0.5");
        Assert.assertEquals(params.get("albumDone"), "2 photos added to the library.");

        params.clear();
        library.addFromAlbumUi("a@0.5,0.5;missing@0.5,0.5");
        Assert.assertEquals(params.get("albumAdded"), true);
        Assert.assertEquals(params.get("albumNote"),
                "1 of 2 added. Not available: that album photo cannot be used here.");

        params.clear();
        library.addFromAlbumUi("missing@0.5,0.5");
        Assert.assertEquals(params.get("albumAdded"), false);
        Assert.assertEquals(params.get("albumNote"), "Not available: that album photo cannot be used here.");
    }

    @Test
    public void aFieldThatTakesAnImageByUrlGetsTheCopysFullAddress() {
        offer("a", "A", PhotoFixtures.jpeg(600, 400));
        library.pickFromAlbum("a");
        Assert.assertEquals(params.get("albumPicked"), true);
        final String url = (String) params.get("albumUrl");
        Assert.assertTrue(url.matches("http://localhost:8080/album-library/org/" + ORG + "/album-photos/[0-9]+\\.jpg"),
                "Locally the path is made a full address, which is all a picture field takes: " + url);

        Mockito.doReturn("https://files.example.org/org/x/album-photos/1.jpg").when(media).getUrl(Mockito.any());
        library.pickFromAlbum("a");
        Assert.assertEquals(params.get("albumUrl"), "https://files.example.org/org/x/album-photos/1.jpg",
                "With a media host the address is already whole");

        params.clear();
        library.pickFromAlbum("missing");
        Assert.assertEquals(params.get("albumPicked"), false);
        Assert.assertNull(params.get("albumUrl"));
        library.pickFromAlbum(null);
        Assert.assertEquals(params.get("albumPicked"), false);
    }

    // --- keys ---

    @Test
    public void onlyThisClassesKeysAreItsKeysAndTheLibraryServesThemLocally() {
        Assert.assertTrue(AlbumLibrary.isAlbumCopyKey("album-photos/1763340000000.jpg"));
        Assert.assertTrue(AlbumLibrary.isAlbumCopyKey("org/" + ORG + "/album-photos/1.jpg"));
        for (final String key : new String[] {null, "", "album-photos/x.jpg", "album-photos/1.png",
                "org/" + ORG + "/album-photos/../branding/logo-1.jpg", "org/a/b/album-photos/1.jpg",
                "chat/t/album-photos/1.jpg", "album-photos/1.jpg/extra"}) {
            Assert.assertFalse(AlbumLibrary.isAlbumCopyKey(key), "key '" + key + "'");
        }
        Assert.assertTrue(library.localGet("album-photos/1.jpg").isEmpty(), "A well-formed key nothing was stored at");
        Assert.assertTrue(library.localGet("chat/t/x.jpg").isEmpty());
        Assert.assertEquals(MediaCommands.localServedPath("album-photos/1.jpg"), "/album-library/album-photos/1.jpg");
        Assert.assertTrue(AlbumLibrary.nextStamp() < AlbumLibrary.nextStamp());
    }

    @Test
    public void theRealSeamsAnswerOffAPage() {
        final AlbumLibrary real = new AlbumLibrary();
        real.wireForTest(media, albums);
        Assert.assertFalse(real.mayAdd(), "Off a request there is nobody signed in");
        Assert.assertEquals(real.requestOrigin(), "");
        Assert.assertNull(real.siteOrgId(), "Off a request the site is the shared default");
        Assert.assertFalse(real.isOrgAdmin(caller, null));
        real.publish("anything", true);
    }
}
