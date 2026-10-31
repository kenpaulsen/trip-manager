package org.paulsens.trip.model.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.paulsens.trip.dynamo.DAO;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * {@link ChatAttachment#getRev()}: the rotation counter every client turns into {@code ?r=N}. Absent from the
 * JSON at 0 (old rows, and the wire, stay as they were) and round-tripped once set.
 */
public class ChatAttachmentTest {

    private static final ObjectMapper MAPPER = DAO.getInstance().getMapper();

    private static ChatAttachment photo() {
        return new ChatAttachment("image", "chat/t/20260801-120000000-abcdef.jpg", "image/jpeg", 100L, 1400, 1000,
                "chat/t/20260801-120000000-abcdef-small.jpg", "cap", true);
    }

    @Test
    public void anUnrotatedAttachmentWritesNoRev() throws Exception {
        final String json = MAPPER.writeValueAsString(photo());
        Assert.assertFalse(json.contains("\"rev\""), json);
        Assert.assertEquals(MAPPER.readValue(json, ChatAttachment.class).getRev(), 0);
    }

    @Test
    public void aRotationSwapsTheDimensionsAndBumpsRevThroughARoundTrip() throws Exception {
        final ChatAttachment once = photo().rotated(1000, 1400, 90L);
        Assert.assertEquals(once.getRev(), 1);
        Assert.assertEquals(once.getWidth(), Integer.valueOf(1000));
        Assert.assertEquals(once.getHeight(), Integer.valueOf(1400));
        Assert.assertEquals(once.getSize(), 90L);
        Assert.assertEquals(once.getThumbKey(), photo().getThumbKey(), "the small rendition keeps its key");
        Assert.assertTrue(once.isHidden(), "flags survive a rotation");
        Assert.assertEquals(once.getCaption(), "cap");

        final String json = MAPPER.writeValueAsString(once);
        Assert.assertTrue(json.contains("\"rev\":1"), json);
        final ChatAttachment back = MAPPER.readValue(json, ChatAttachment.class);
        Assert.assertEquals(back, once);
        Assert.assertEquals(back.rotated(1400, 1000, 80L, "other-small.jpg").getRev(), 2);
    }

    @Test
    public void revSurvivesTheVisibilityFlagAndATombstoneDropsIt() {
        final ChatAttachment rotated = photo().rotated(1000, 1400, 90L);
        Assert.assertEquals(rotated.withHidden(false).getRev(), 1);
        Assert.assertEquals(rotated.deleted("Ann").getRev(), 0);
    }

    @Test
    public void aNonPositiveRevNormalizesToNone() {
        final ChatAttachment negative = new ChatAttachment("image", "k", "image/jpeg", 1L, 1, 1, null, null, null,
                null, null, -3);
        Assert.assertEquals(negative.getRev(), 0);
        Assert.assertEquals(negative, new ChatAttachment("image", "k", "image/jpeg", 1L, 1, 1, null, null, null,
                null, null));
    }
}
