package org.paulsens.trip.action;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.model.MediaItem;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * {@link MediaCommands}' presigned-upload half (issue #37): the URL's signature must pin what the client may
 * put, and the confirm step must trust what S3 reports over what the caller claims. Client and presigner are
 * injected by reflection, same seam and same reason as {@link MediaCommandsS3Test}.
 */
public class MediaCommandsPresignTest {

    private MediaCommands media;
    private S3Client s3;
    private S3Presigner presigner;

    @BeforeClass
    public void injectClientsAndBucket() throws Exception {
        System.setProperty("trip.media.bucket", "test-bucket");
        media = new MediaCommands();
        s3 = Mockito.mock(S3Client.class);
        presigner = Mockito.mock(S3Presigner.class);
        inject("s3", s3);
        inject("presigner", presigner);
    }

    private void inject(final String fieldName, final Object value) throws Exception {
        final java.lang.reflect.Field field = MediaCommands.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(media, value);
    }

    @BeforeMethod
    public void resetClients() {
        Mockito.reset(s3, presigner);
    }

    @AfterClass(alwaysRun = true)
    public void clearBucket() {
        System.clearProperty("trip.media.bucket");
    }

    private void presignAnswers(final String url) {
        final PresignedPutObjectRequest signed = Mockito.mock(PresignedPutObjectRequest.class);
        Mockito.when(signed.url()).thenAnswer(call -> URI.create(url).toURL());
        Mockito.when(presigner.presignPutObject(ArgumentMatchers.any(PutObjectPresignRequest.class)))
                .thenReturn(signed);
    }

    private void headAnswers(final long size, final String contentType) {
        Mockito.when(s3.headObject(ArgumentMatchers.any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(size).contentType(contentType).build());
    }

    /** The signature must constrain the put, not just expire: key, type, exact length and cache policy. */
    @Test
    public void thePresignedUrlPinsKeyTypeLengthAndCachePolicy() {
        presignAnswers("https://test-bucket.s3/downloads/a.pdf?sig");

        final String url = media.presignUpload("/downloads/a.pdf", "application/pdf", 9L);

        Assert.assertEquals(url, "https://test-bucket.s3/downloads/a.pdf?sig");
        final ArgumentCaptor<PutObjectPresignRequest> captor =
                ArgumentCaptor.forClass(PutObjectPresignRequest.class);
        Mockito.verify(presigner).presignPutObject(captor.capture());
        final PutObjectPresignRequest request = captor.getValue();
        Assert.assertEquals(request.signatureDuration(), MediaCommands.UPLOAD_URL_TTL);
        Assert.assertEquals(request.putObjectRequest().bucket(), "test-bucket");
        // Leading slashes are stripped: the key doubles as the file's public URL path.
        Assert.assertEquals(request.putObjectRequest().key(), "downloads/a.pdf");
        Assert.assertEquals(request.putObjectRequest().contentType(), "application/pdf");
        Assert.assertEquals(request.putObjectRequest().contentLength(), Long.valueOf(9L));
        Assert.assertTrue(request.putObjectRequest().cacheControl().startsWith("public, max-age="),
                "Direct uploads must carry the same cache policy container uploads stamp");
    }

    @Test
    public void aMalformedPresignRequestIsRefusedWithoutSigning() {
        Assert.assertNull(media.presignUpload(null, "application/pdf", 9L));
        Assert.assertNull(media.presignUpload("  ", "application/pdf", 9L));
        Assert.assertNull(media.presignUpload("downloads/a.pdf", " ", 9L));
        Assert.assertNull(media.presignUpload("downloads/a.pdf", "application/pdf", 0L));
        Mockito.verifyNoInteractions(presigner);
    }

    @Test
    public void withoutABucketNothingIsSigned() {
        System.clearProperty("trip.media.bucket");
        try {
            Assert.assertNull(media.presignUpload("downloads/a.pdf", "application/pdf", 9L));
            Mockito.verifyNoInteractions(presigner);
        } finally {
            System.setProperty("trip.media.bucket", "test-bucket");
        }
    }

    @Test
    public void aPresignerFailureAnswersNullRatherThanThrowing() {
        Mockito.when(presigner.presignPutObject(ArgumentMatchers.any(PutObjectPresignRequest.class)))
                .thenThrow(new IllegalStateException("no credentials"));

        Assert.assertNull(media.presignUpload("downloads/a.pdf", "application/pdf", 9L));
    }

    @Test
    public void statObjectReportsWhatS3Holds() {
        headAnswers(123L, "image/png");

        final Optional<MediaCommands.StoredObject> stored = media.statObject("downloads/pic.png");

        Assert.assertTrue(stored.isPresent());
        Assert.assertEquals(stored.get().size(), 123L);
        Assert.assertEquals(stored.get().contentType(), "image/png");
    }

    @Test
    public void statObjectAnswersEmptyForMissingObjectsErrorsAndNoBucket() {
        Mockito.when(s3.headObject(ArgumentMatchers.any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().build());
        Assert.assertTrue(media.statObject("downloads/nope.pdf").isEmpty());

        Mockito.reset(s3);
        Mockito.when(s3.headObject(ArgumentMatchers.any(HeadObjectRequest.class)))
                .thenThrow(new IllegalStateException("s3 down"));
        Assert.assertTrue(media.statObject("downloads/err.pdf").isEmpty());

        Assert.assertTrue(media.statObject(null).isEmpty());

        System.clearProperty("trip.media.bucket");
        try {
            Assert.assertTrue(media.statObject("downloads/a.pdf").isEmpty());
        } finally {
            System.setProperty("trip.media.bucket", "test-bucket");
        }
    }

    /**
     * The whole point of the two-phase upload: the row records what S3 holds, the media events fire so
     * prefix listeners (profile photos, slot listings) stay coherent, and the row is immediately findable.
     */
    @Test
    public void confirmingAnUploadRecordsS3sFactsAndFiresTheAddedEvent() {
        final String key = "downloads/confirm-" + System.nanoTime() + ".pdf";
        headAnswers(9L, "application/pdf");
        final List<String> added = new CopyOnWriteArrayList<>();
        MediaEvents.onPrefix(key, (change, changedKey) -> recordAdd(added, change, changedKey));

        final MediaItem saved = media.confirmUpload("/" + key, "  ", "d", "home-docs", 3,
                "admin@example.com", null);

        Assert.assertNotNull(saved);
        Assert.assertEquals(saved.getS3Key(), key, "Leading slashes must be stripped");
        Assert.assertEquals(saved.getTitle(), key, "A blank title falls back to the key, like upload()");
        Assert.assertEquals(saved.getSize(), 9L, "Size must come from S3, not the caller");
        Assert.assertEquals(saved.getContentType(), "application/pdf", "Type must come from S3");
        Assert.assertEquals(saved.getSlot(), "home-docs");
        Assert.assertEquals(saved.getPosition(), 3);
        Assert.assertEquals(saved.getUploadedBy(), "admin@example.com");
        Assert.assertEquals(added, List.of(key), "Prefix listeners must hear about the new object");
        Assert.assertEquals(media.getByKey(key).getId(), saved.getId());
    }

    private static void recordAdd(final List<String> added, final MediaEvents.Change change, final String key) {
        if (change == MediaEvents.Change.ADDED) {
            added.add(key);
        }
    }

    /** A confirm is only a claim; without an object behind it, no row may appear. */
    @Test
    public void confirmingWithNoObjectBehindItRecordsNothing() {
        final String key = "downloads/ghost-" + System.nanoTime() + ".pdf";
        Mockito.when(s3.headObject(ArgumentMatchers.any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().build());

        Assert.assertNull(media.confirmUpload(key, "T", null, null, 0, "admin@example.com", null));
        Assert.assertNull(media.getByKey(key), "No row may exist for an unverified object");
    }

    @Test
    public void confirmingAMalformedKeyRecordsNothing() {
        Assert.assertNull(media.confirmUpload("   ", "T", null, null, 0, "admin@example.com", null));
        Assert.assertNull(media.confirmUpload(null, "T", null, null, 0, "admin@example.com", null));
        Mockito.verifyNoInteractions(s3);
    }

    @Test
    public void getByKeyFindsTheRowThatClaimsAKeyAndOnlyThat() {
        final String key = "downloads/claimed-" + System.nanoTime() + ".pdf";
        headAnswers(5L, "application/pdf");
        final MediaItem saved = media.confirmUpload(key, "T", null, null, 0, "admin@example.com", null);

        Assert.assertEquals(media.getByKey("/" + key).getId(), saved.getId(),
                "Lookups must normalize the way writes do");
        Assert.assertNull(media.getByKey("downloads/never-uploaded.pdf"));
        Assert.assertNull(media.getByKey("  "));
        Assert.assertNull(media.getByKey(null));
    }

    /** The reserved namespaces: features that index these prefixes would be corrupted by outside writes. */
    @Test
    public void reservedKeysAreTheOtherFeaturesPrefixesAndAnythingMalformed() {
        Assert.assertTrue(MediaCommands.isReservedKey("profilePics/p1/1-1.jpg"));
        Assert.assertTrue(MediaCommands.isReservedKey("/profilePics/p1.jpg"));
        Assert.assertTrue(MediaCommands.isReservedKey("chat/trip-1/photo.jpg"));
        Assert.assertTrue(MediaCommands.isReservedKey(null));
        Assert.assertTrue(MediaCommands.isReservedKey("   "));
        Assert.assertFalse(MediaCommands.isReservedKey("downloads/guide.pdf"));
        Assert.assertFalse(MediaCommands.isReservedKey("images/home/banner.jpg"));
    }

    /** The REST edge passes its own actor; the count must still be the refreshed inventory's size. */
    @Test
    public void refreshingWithAnExplicitActorCountsTheInventory() {
        final int count = media.refreshFromDatabase(AuditActor.from(null));

        Assert.assertEquals(count, media.getAll().size());
    }
}
