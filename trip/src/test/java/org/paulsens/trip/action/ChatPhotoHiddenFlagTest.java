package org.paulsens.trip.action;

import java.util.List;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.chat.ChatAttachment;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * The uploader's "keep off the public site" choice, end to end through the send-time plumbing: the tray JSON
 * carries it, and the album row lands with it -- which is what the landing page filters on.
 */
public class ChatPhotoHiddenFlagTest {

    @BeforeClass
    public void warmUp() {
        DAO.getInstance();
    }

    @Test
    public void parseRefsReadsTheHiddenFlag() {
        final List<ChatPhotos.AttachmentRef> refs = ChatPhotos.parseRefs(
                "[{\"key\":\"a\",\"title\":\"t\",\"hidden\":true},"
                        + "{\"key\":\"b\",\"hidden\":false},"
                        + "{\"key\":\"c\"},"
                        + "{\"key\":\"d\",\"hidden\":\"yes\"}]");
        Assert.assertEquals(refs.size(), 4);
        Assert.assertEquals(refs.get(0).hidden(), Boolean.TRUE);
        Assert.assertEquals(refs.get(1).hidden(), Boolean.FALSE);
        Assert.assertNull(refs.get(2).hidden(), "absent means visible");
        Assert.assertNull(refs.get(3).hidden(), "non-boolean junk is ignored, not trusted");
    }

    @Test
    public void twoArgRefCompatibilityMeansVisible() {
        Assert.assertNull(new ChatPhotos.AttachmentRef("k", "t").hidden());
    }

    @Test
    public void albumRowsCarryTheUploaderChoice() {
        final ChatAttachment hidden = new ChatAttachment("image", "chat/hidden-flag-trip/one.jpg",
                "image/jpeg", 10L, 100, 100, "chat/hidden-flag-trip/one-small.jpg", "cap", true);
        final ChatAttachment visible = new ChatAttachment("image", "chat/hidden-flag-trip/two.jpg",
                "image/jpeg", 10L, 100, 100, null, null);
        ChatPhotos.getChatPhotos().recordAlbumRows("hidden-flag-trip", "Hidden Flag Trip",
                Person.Id.from("uploader"), "Uploader", List.of(hidden, visible),
                new AuditActor("uploader@example.com", "uploader"));

        final List<MediaItem> album = DAO.getInstance().getMediaInSlot("tripChat-hidden-flag-trip");
        Assert.assertEquals(album.size(), 2);
        final MediaItem hiddenRow = album.stream()
                .filter(item -> item.getS3Key().equals("chat/hidden-flag-trip/one.jpg"))
                .findFirst().orElseThrow();
        Assert.assertTrue(hiddenRow.getHidden(), "the uploader's opt-out must reach the album row");
        final MediaItem visibleRow = album.stream()
                .filter(item -> item.getS3Key().equals("chat/hidden-flag-trip/two.jpg"))
                .findFirst().orElseThrow();
        Assert.assertFalse(visibleRow.getHidden());
    }
}
