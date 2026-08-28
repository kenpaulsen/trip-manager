package org.paulsens.trip.action;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.util.List;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.model.MediaItem;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * {@link MediaCommands}' S3 half, with the client injected and the bucket set by system property -- the same
 * seam {@code env()} already reads. The DAO half stays real against the fake store, so the row bookkeeping
 * (rows written, events fired, dates NOT restamped) runs for real.
 *
 * <p>The property worth pinning on upload is Cache-Control at PUT time: S3 serves object metadata as response
 * headers and CloudFront honours them, so a missing header at upload becomes a browser re-validating a file
 * that never changes.
 */
public class MediaS3PathsTest {

    private static final String BUCKET_PROP = "trip.media.bucket";
    private static final String BASE_URL_PROP = "trip.media.base.url";

    private MediaCommands media;
    private S3Client s3;

    @BeforeMethod
    public void wireClient() throws Exception {
        media = new MediaCommands();
        s3 = Mockito.mock(S3Client.class);
        final Field field = MediaCommands.class.getDeclaredField("s3");
        field.setAccessible(true);
        field.set(media, s3);
        System.setProperty(BUCKET_PROP, "test-bucket");
        System.setProperty(BASE_URL_PROP, "https://media.example.org/");
    }

    @AfterMethod(alwaysRun = true)
    public void clearProperties() {
        System.clearProperty(BUCKET_PROP);
        System.clearProperty(BASE_URL_PROP);
    }

    private boolean upload(final String key) {
        final byte[] bytes = "content".getBytes();
        return media.upload(key, new ByteArrayInputStream(bytes), bytes.length, "text/plain",
                "A file", "Description", null, 0, "uploader@example.org");
    }

    @Test
    public void anUploadWithoutABucketIsRefusedBeforeS3() {
        System.clearProperty(BUCKET_PROP);
        // Bucket resolution falls back to the env var, which must also be absent for this test to mean anything.
        if (System.getenv(MediaCommands.BUCKET_VAR) != null) {
            throw new org.testng.SkipException(MediaCommands.BUCKET_VAR + " is set in this environment");
        }

        Assert.assertFalse(upload("downloads/guide.pdf"));
        Assert.assertFalse(media.isUploadEnabled());
        Mockito.verifyNoInteractions(s3);
    }

    @Test
    public void anUploadNeedsAUsableKey() {
        Assert.assertFalse(upload(null));
        Assert.assertFalse(upload("   "));
        Assert.assertFalse(upload("///"));
        Mockito.verifyNoInteractions(s3);
    }

    @Test
    public void anUploadPutsTheObjectWithCacheControlAndRecordsTheRow() {
        final boolean uploaded = upload("/downloads/guide.pdf");

        Assert.assertTrue(uploaded);
        final ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        Mockito.verify(s3).putObject(put.capture(),
                ArgumentMatchers.any(software.amazon.awssdk.core.sync.RequestBody.class));
        Assert.assertEquals(put.getValue().bucket(), "test-bucket");
        Assert.assertEquals(put.getValue().key(), "downloads/guide.pdf", "Leading slashes must be stripped");
        Assert.assertTrue(put.getValue().cacheControl().startsWith("public, max-age="),
                "Cache-Control must be set at PUT time; CloudFront serves it as a response header");

        final MediaItem row = media.getAll().stream()
                .filter(item -> "downloads/guide.pdf".equals(item.getS3Key()))
                .findFirst().orElse(null);
        Assert.assertNotNull(row, "The metadata row must exist after upload");
    }

    @Test
    public void anS3FailureDuringUploadRefusesAndWritesNoRow() {
        Mockito.when(s3.putObject(ArgumentMatchers.any(PutObjectRequest.class),
                ArgumentMatchers.any(software.amazon.awssdk.core.sync.RequestBody.class)))
                .thenThrow(new IllegalStateException("s3 down"));

        Assert.assertFalse(upload("downloads/failed.pdf"));
        Assert.assertTrue(media.getAll().stream().noneMatch(i -> "downloads/failed.pdf".equals(i.getS3Key())),
                "A failed PUT must not leave a metadata row pointing at nothing");
    }

    /** The key IS the public URL, so a rename is copy-then-delete, not a metadata edit. */
    @Test
    public void aRenameCopiesTheObjectAndDeletesTheOldKey() {
        Assert.assertTrue(upload("downloads/old-name.pdf"));
        final MediaItem row = media.getAll().stream()
                .filter(item -> "downloads/old-name.pdf".equals(item.getS3Key()))
                .findFirst().orElseThrow();

        Assert.assertTrue(media.update(row.getId(), "downloads/new-name.pdf", "t", "d", null, 0,
                "editor@example.org"));

        final ArgumentCaptor<CopyObjectRequest> copy = ArgumentCaptor.forClass(CopyObjectRequest.class);
        Mockito.verify(s3).copyObject(copy.capture());
        Assert.assertEquals(copy.getValue().sourceKey(), "downloads/old-name.pdf");
        Assert.assertEquals(copy.getValue().destinationKey(), "downloads/new-name.pdf");
        final ArgumentCaptor<DeleteObjectRequest> delete = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        Mockito.verify(s3).deleteObject(delete.capture());
        Assert.assertEquals(delete.getValue().key(), "downloads/old-name.pdf");
    }

    @Test
    public void aFailedCopyAbortsTheRenameAndKeepsTheRow() {
        Assert.assertTrue(upload("downloads/immovable.pdf"));
        final MediaItem row = media.getAll().stream()
                .filter(item -> "downloads/immovable.pdf".equals(item.getS3Key()))
                .findFirst().orElseThrow();
        Mockito.when(s3.copyObject(ArgumentMatchers.any(CopyObjectRequest.class)))
                .thenThrow(new IllegalStateException("copy failed"));

        Assert.assertFalse(media.update(row.getId(), "downloads/elsewhere.pdf", "t", "d", null, 0, "e@x"));

        Assert.assertEquals(media.get(row.getId()).getS3Key(), "downloads/immovable.pdf",
                "A failed copy must leave the row pointing at the object that still exists");
    }

    @Test
    public void deleteRemovesTheRowAndTheObject() {
        Assert.assertTrue(upload("downloads/doomed.pdf"));
        final MediaItem row = media.getAll().stream()
                .filter(item -> "downloads/doomed.pdf".equals(item.getS3Key()))
                .findFirst().orElseThrow();

        Assert.assertTrue(media.delete(row.getId(), "deleter@example.org"));

        Assert.assertNull(media.get(row.getId()));
        final ArgumentCaptor<DeleteObjectRequest> delete = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        Mockito.verify(s3).deleteObject(delete.capture());
        Assert.assertEquals(delete.getValue().key(), "downloads/doomed.pdf");
    }

    /** An orphaned object is tidiness; a row that outlives its bytes is a broken page. Row wins. */
    @Test
    public void aFailedObjectDeleteStillCountsAsDeleted() {
        Assert.assertTrue(upload("downloads/sticky.pdf"));
        final MediaItem row = media.getAll().stream()
                .filter(item -> "downloads/sticky.pdf".equals(item.getS3Key()))
                .findFirst().orElseThrow();
        Mockito.when(s3.deleteObject(ArgumentMatchers.any(DeleteObjectRequest.class)))
                .thenThrow(new IllegalStateException("delete failed"));

        Assert.assertTrue(media.delete(row.getId(), "deleter@example.org"));
        Assert.assertNull(media.get(row.getId()), "The row must be gone even though the object is not");
    }

    @Test
    public void deletingNothingReportsFalse() {
        Assert.assertFalse(media.delete("no-such-id", "x"));
        Assert.assertFalse(media.delete(null, "x"));
    }

    @Test
    public void publicUrlsComeFromTheConfiguredBase() {
        Assert.assertEquals(media.publicUrl("downloads/guide.pdf"),
                "https://media.example.org/downloads/guide.pdf");

        final MediaItem item = new MediaItem("m1", "img/pic.jpg", null, null, null, 0L, null, 0, null, null);
        Assert.assertEquals(media.getUrl(item), "https://media.example.org/img/pic.jpg");
        Assert.assertNull(media.getUrl(null));
    }

    @Test
    public void withoutABaseUrlPathsAreRelative() {
        System.clearProperty(BASE_URL_PROP);
        if (System.getenv(MediaCommands.BASE_URL_VAR) != null) {
            throw new org.testng.SkipException(MediaCommands.BASE_URL_VAR + " is set in this environment");
        }

        Assert.assertEquals(media.publicUrl("img/pic.jpg"), "/img/pic.jpg");
    }

    @Test
    public void normalizeKeyStripsSlashesAndKeepsCase() {
        Assert.assertEquals(MediaCommands.normalizeKey("//Downloads/Guide.PDF "), "Downloads/Guide.PDF");
        Assert.assertNull(MediaCommands.normalizeKey(null));
        Assert.assertNull(MediaCommands.normalizeKey("  "));
    }

    @Test
    public void listKeysWithoutABucketIsEmptyWithoutS3() {
        System.clearProperty(BUCKET_PROP);
        if (System.getenv(MediaCommands.BUCKET_VAR) != null) {
            throw new org.testng.SkipException(MediaCommands.BUCKET_VAR + " is set in this environment");
        }

        Assert.assertEquals(media.listKeys("img/"), List.of());
        Mockito.verifyNoInteractions(s3);
    }

    @Test
    public void refreshFromDatabaseClearsTheCacheAndCounts() {
        Assert.assertTrue(media.refreshFromDatabase() >= 0);
    }

    @Test
    public void updateOfAMissingItemRefuses() {
        Assert.assertFalse(media.update("no-such-id", "k", "t", "d", null, 0, "e@x"));
    }
}
