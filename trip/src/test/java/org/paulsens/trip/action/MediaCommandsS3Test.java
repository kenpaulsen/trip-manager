package org.paulsens.trip.action;

import java.io.ByteArrayInputStream;
import java.util.List;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.model.MediaItem;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

/**
 * {@link MediaCommands}' S3-touching paths, with the client injected so no bucket is ever contacted.
 *
 * <p>The bucket name comes from the {@code trip.media.bucket} system property (the sysprop face of
 * {@code TRIP_MEDIA_BUCKET}), and the lazily-built client is replaced by reflection -- the field is a plain
 * lazy singleton with no constructor seam, and adding one only for tests would push an S3 type into every
 * caller's view of the class.
 */
public class MediaCommandsS3Test {

    private MediaCommands media;
    private S3Client s3;

    @BeforeClass
    public void injectClientAndBucket() throws Exception {
        System.setProperty("trip.media.bucket", "test-bucket");
        media = new MediaCommands();
        s3 = Mockito.mock(S3Client.class);
        final java.lang.reflect.Field field = MediaCommands.class.getDeclaredField("s3");
        field.setAccessible(true);
        field.set(media, s3);
    }

    @AfterClass(alwaysRun = true)
    public void clearBucket() {
        System.clearProperty("trip.media.bucket");
    }

    private String upload(final String key, final String slot) {
        final byte[] bytes = "pdf-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Assert.assertTrue(media.upload(key, new ByteArrayInputStream(bytes), bytes.length,
                "application/pdf", "Title", "desc", slot, 1, "admin@example.org"));
        return media.getAll().stream().filter(m -> key.equals(m.getS3Key()))
                .map(MediaItem::getId).findFirst().orElseThrow();
    }

    @Test
    public void uploadPutsTheObjectWithCachingHeadersAndSavesTheRow() {
        final String id = upload("downloads/s3-test-" + System.nanoTime() + ".pdf", "downloads");

        final org.mockito.ArgumentCaptor<PutObjectRequest> put =
                org.mockito.ArgumentCaptor.forClass(PutObjectRequest.class);
        Mockito.verify(s3, Mockito.atLeastOnce()).putObject(put.capture(),
                ArgumentMatchers.any(software.amazon.awssdk.core.sync.RequestBody.class));
        Assert.assertEquals(put.getValue().bucket(), "test-bucket");
        Assert.assertTrue(put.getValue().cacheControl().startsWith("public, max-age="),
                "cache headers are set at upload time so CloudFront and browsers can cache");
        Assert.assertNotNull(media.get(id));
    }

    @Test
    public void uploadRefusesABlankKeyOrAMissingBucket() {
        Assert.assertFalse(media.upload("   ", new ByteArrayInputStream(new byte[0]), 0,
                "application/pdf", "T", null, null, 0, "admin"));

        System.clearProperty("trip.media.bucket");
        try {
            if (System.getenv(MediaCommands.BUCKET_VAR) == null) {
                Assert.assertFalse(media.upload("downloads/x.pdf", new ByteArrayInputStream(new byte[0]), 0,
                        "application/pdf", "T", null, null, 0, "admin"));
            }
        } finally {
            System.setProperty("trip.media.bucket", "test-bucket");
        }
    }

    /** A rename is copy-then-delete (S3 has no rename), and both file paths must fire media events. */
    @Test
    public void updateWithANewKeyMovesTheObject() {
        final String key = "downloads/move-src-" + System.nanoTime() + ".pdf";
        final String id = upload(key, "downloads");
        final String newKey = key.replace("move-src", "move-dst");

        Assert.assertTrue(media.update(id, newKey, "Renamed", null, "downloads", 2, "admin@example.org"));

        Mockito.verify(s3).copyObject(ArgumentMatchers.any(CopyObjectRequest.class));
        Mockito.verify(s3, Mockito.atLeastOnce()).deleteObject(ArgumentMatchers.any(DeleteObjectRequest.class));
        Assert.assertEquals(media.get(id).getS3Key(), newKey);
    }

    /** Copy OK but delete-old fails: the rename stands (the new URL works); the leftover is only untidy. */
    @Test
    public void aFailedOldObjectDeleteDoesNotFailTheRename() {
        final String key = "downloads/half-move-" + System.nanoTime() + ".pdf";
        final String id = upload(key, "downloads");
        Mockito.doThrow(new IllegalStateException("delete failed")).when(s3)
                .deleteObject(ArgumentMatchers.any(DeleteObjectRequest.class));
        try {
            Assert.assertTrue(media.update(id, key + ".moved", "T", null, null, null, "admin"),
                    "the copy succeeded, so the rename must stand");
            Assert.assertEquals(media.get(id).getS3Key(), key + ".moved");
        } finally {
            Mockito.reset(s3);
        }
    }

    @Test
    public void updateOfAnUnknownIdFailsWithoutTouchingS3() {
        Assert.assertFalse(media.update("no-such-id", null, "T", null, null, null, "admin"));
    }

    /** A failed copy must abort the rename: the row must keep pointing at the object that still exists. */
    @Test
    public void aFailedMoveLeavesTheRowOnTheOldKey() {
        final String key = "downloads/badmove-" + System.nanoTime() + ".pdf";
        final String id = upload(key, "downloads");
        Mockito.doThrow(new IllegalStateException("copy failed")).when(s3)
                .copyObject(ArgumentMatchers.any(CopyObjectRequest.class));
        try {
            Assert.assertFalse(media.update(id, key + ".renamed", "T", null, null, null, "admin"));
            Assert.assertEquals(media.get(id).getS3Key(), key);
        } finally {
            Mockito.reset(s3);
        }
    }

    @Test
    public void deleteRemovesTheRowAndTheObjectAndSurvivesAnObjectFailure() {
        final String key = "downloads/del-" + System.nanoTime() + ".pdf";
        final String id = upload(key, "downloads");

        Assert.assertTrue(media.delete(id, "admin@example.org"));
        Assert.assertNull(media.get(id));
        Assert.assertFalse(media.delete(id, "admin@example.org"), "already gone");

        // An object-delete failure demotes to a warning: the row is gone, so the site no longer links it.
        final String key2 = "downloads/del2-" + System.nanoTime() + ".pdf";
        final String id2 = upload(key2, "downloads");
        Mockito.doThrow(new IllegalStateException("s3 down")).when(s3)
                .deleteObject(ArgumentMatchers.any(DeleteObjectRequest.class));
        try {
            Assert.assertTrue(media.delete(id2, "admin@example.org"),
                    "an orphaned object is tidiness, not correctness");
        } finally {
            Mockito.reset(s3);
        }
    }

    /** A throwing putObject fails the upload cleanly -- no row is written for an object that never landed. */
    @Test
    public void aThrowingPutObjectFailsTheUploadWithoutARow() {
        Mockito.doThrow(new IllegalStateException("s3 down")).when(s3)
                .putObject(ArgumentMatchers.any(PutObjectRequest.class),
                        ArgumentMatchers.any(software.amazon.awssdk.core.sync.RequestBody.class));
        try {
            final byte[] bytes = "x".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Assert.assertFalse(media.upload("downloads/never-lands.pdf", new java.io.ByteArrayInputStream(bytes),
                    bytes.length, "application/pdf", "T", null, null, 0, "admin"));
        } finally {
            Mockito.reset(s3);
        }
    }

    /** A store failure during update/delete maps to false -- the page shows a message, not a stack. */
    @Test
    public void aFailingStoreMapsUpdateAndDeleteToFalse() {
        final String key = "downloads/store-fail-" + System.nanoTime() + ".pdf";
        final String id = upload(key, "downloads");
        final org.paulsens.trip.dynamo.DAO failing = Mockito.mock(org.paulsens.trip.dynamo.DAO.class,
                invocation -> java.util.concurrent.CompletableFuture.class
                        .isAssignableFrom(invocation.getMethod().getReturnType())
                        ? java.util.concurrent.CompletableFuture.failedFuture(
                                new IllegalStateException("store down"))
                        : Mockito.RETURNS_DEFAULTS.answer(invocation));
        try (org.mockito.MockedStatic<org.paulsens.trip.dynamo.DAO> daoStatic =
                Mockito.mockStatic(org.paulsens.trip.dynamo.DAO.class)) {
            daoStatic.when(org.paulsens.trip.dynamo.DAO::getInstance).thenReturn(failing);

            Assert.assertFalse(media.delete(id, "admin"), "an unreadable row cannot be deleted");
            Mockito.when(failing.getMedia(id)).thenReturn(java.util.Optional.of(new MediaItem(id, key, "T", null,
                    "application/pdf", 1L, "downloads", 0, null, null)));
            Assert.assertFalse(media.delete(id, "admin"), "a failing row delete maps to false");
            Assert.assertFalse(media.update(id, null, "T", null, null, null, "admin"),
                    "a failing save maps to false");
        }
    }

    @Test
    public void listKeysWalksThePaginator() {
        final ListObjectsV2Iterable pages = Mockito.mock(ListObjectsV2Iterable.class);
        Mockito.when(pages.contents()).thenReturn(
                () -> List.of(S3Object.builder().key("downloads/a.pdf").build(),
                        S3Object.builder().key("downloads/b.pdf").build()).iterator());
        Mockito.when(s3.listObjectsV2Paginator(ArgumentMatchers.any(ListObjectsV2Request.class)))
                .thenReturn(pages);

        Assert.assertEquals(media.listKeys("downloads/"), List.of("downloads/a.pdf", "downloads/b.pdf"));

        System.clearProperty("trip.media.bucket");
        try {
            if (System.getenv(MediaCommands.BUCKET_VAR) == null) {
                Assert.assertEquals(media.listKeys("downloads/"), List.of());
            }
        } finally {
            System.setProperty("trip.media.bucket", "test-bucket");
        }
    }
}
