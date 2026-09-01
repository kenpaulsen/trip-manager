package org.paulsens.trip.action;

import java.time.LocalDateTime;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.MediaItem;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Editing a media item's details.
 *
 * <p>The property worth pinning is what a save must NOT do: change the upload date. That field records when the
 * bytes were written, and the whole reason there is no separate "modified" column is that the audit trail
 * answers "who changed this and when" properly, with an actor and a history. If a save quietly restamped
 * {@code uploaded}, the one date on the row would start meaning neither thing.
 *
 * <p>Runs against local mode, where no bucket is configured -- so the row is edited and the object move is a
 * no-op. That is the interesting half here; the S3 copy/delete is exercised by MediaUploadIT against a real
 * bucket.
 */
public class MediaEditTest {

    private final MediaCommands media = TestCallers.mediaAsSiteAdmin();

    private MediaItem seed(final String key) {
        final MediaItem item = new MediaItem("edit-test-" + System.nanoTime(), key, "Original title",
                "Original description", "image/jpeg", 2048L, "homepage", 3,
                LocalDateTime.of(2021, 5, 4, 9, 30), "someone@example.com");
        Assert.assertTrue(DAO.getInstance().saveMedia(item), "seed should save");
        return item;
    }

    @BeforeMethod
    public void warmUp() {
        DAO.getInstance();
    }

    @Test
    public void editingDetailsLeavesTheUploadDateAlone() {
        final MediaItem original = seed("content/original-" + System.nanoTime() + ".jpg");

        Assert.assertTrue(media.update(original.getId(), original.getS3Key(), "New title", "New description",
                "downloads", 7, "editor@example.com"));

        final MediaItem saved = media.get(original.getId());
        Assert.assertEquals(saved.getTitle(), "New title");
        Assert.assertEquals(saved.getDescription(), "New description");
        Assert.assertEquals(saved.getSlot(), "downloads");
        Assert.assertEquals(saved.getPosition(), 7);
        Assert.assertEquals(saved.getUploaded(), original.getUploaded(),
                "Editing the title must not restamp the upload date");
        Assert.assertEquals(saved.getUploadedBy(), original.getUploadedBy(),
                "Nor rewrite who uploaded it");
    }

    @Test
    public void sizeAndTypeAreNotEditable() {
        // They describe the BYTES. Letting a form overwrite them would let the row lie about the object.
        final MediaItem original = seed("content/fixed-" + System.nanoTime() + ".jpg");

        media.update(original.getId(), original.getS3Key(), "t", "d", null, 0, "editor@example.com");

        final MediaItem saved = media.get(original.getId());
        Assert.assertEquals(saved.getContentType(), original.getContentType());
        Assert.assertEquals(saved.getSize(), original.getSize());
    }

    @Test
    public void renamingChangesTheKeyAndKeepsTheRowIdentity() {
        // id is the row identity and s3Key is the address; a rename moves the address only, so anything
        // referencing the item by id survives it.
        final MediaItem original = seed("content/before-" + System.nanoTime() + ".jpg");
        final String newKey = "content/after-" + System.nanoTime() + ".jpg";

        Assert.assertTrue(media.update(original.getId(), newKey, "t", "d", null, 0, "editor@example.com"));

        final MediaItem saved = media.get(original.getId());
        Assert.assertEquals(saved.getId(), original.getId(), "The row identity must not change on a rename");
        Assert.assertEquals(saved.getS3Key(), newKey);
        Assert.assertEquals(saved.getUploaded(), original.getUploaded(), "A rename is not a re-upload");
    }

    @Test
    public void aBlankKeyKeepsTheExistingOne() {
        // An empty field must not blank the address and orphan the object.
        final MediaItem original = seed("content/keep-" + System.nanoTime() + ".jpg");

        media.update(original.getId(), "  ", "t", "d", null, 0, "editor@example.com");

        Assert.assertEquals(media.get(original.getId()).getS3Key(), original.getS3Key());
    }

    @Test
    public void editingSomethingThatDoesNotExistFailsQuietly() {
        Assert.assertFalse(media.update("no-such-id", "k", "t", "d", null, 0, "editor@example.com"));
    }

    @Test
    public void getReturnsNullRatherThanThrowingForABadId() {
        Assert.assertNull(media.get(null));
        Assert.assertNull(media.get(""));
        Assert.assertNull(media.get("no-such-id"));
    }
}
