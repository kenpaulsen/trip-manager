package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import org.paulsens.trip.dynamo.DAO;
import org.testng.Assert;
import org.testng.annotations.Test;

/** {@link MediaItem}'s rotation fields: the stored {@code rev}, the EL cache buster, and the re-key copy. */
public class MediaItemRotationTest {

    private static final ObjectMapper MAPPER = DAO.getInstance().getMapper();

    private static MediaItem item() {
        return new MediaItem("id1", "chat/t/a.jpg", "T", "D", "image/jpeg", 50L, "tripChat-t", 3,
                LocalDateTime.of(2026, 8, 1, 12, 0), "who", "chat/t/a-small.jpg", true, "org1");
    }

    @Test
    public void anUnrotatedItemHasNoBusterAndWritesNoRev() throws Exception {
        Assert.assertEquals(item().getRev(), 0);
        Assert.assertEquals(item().getCacheBuster(), "");
        final String json = MAPPER.writeValueAsString(item());
        Assert.assertFalse(json.contains("\"rev\""), json);
        Assert.assertFalse(json.contains("cacheBuster"), json);
    }

    @Test
    public void withRotatedBumpsRevAndKeepsEverythingElse() throws Exception {
        final MediaItem once = item().withRotated(40L, null);
        Assert.assertEquals(once.getRev(), 1);
        Assert.assertEquals(once.getCacheBuster(), "?r=1");
        Assert.assertEquals(once.getSize(), 40L);
        Assert.assertNull(once.getSmallKey());
        Assert.assertEquals(once.getS3Key(), "chat/t/a.jpg");
        Assert.assertTrue(once.getHidden());
        Assert.assertEquals(once.getOrgId(), "org1");
        Assert.assertEquals(once.getPosition(), 3);

        final MediaItem twice = once.withRotated(41L, "chat/t/a-small.jpg");
        Assert.assertEquals(twice.getCacheBuster(), "?r=2");
        Assert.assertEquals(twice.getSmallKey(), "chat/t/a-small.jpg");

        final MediaItem back = MAPPER.readValue(MAPPER.writeValueAsString(twice), MediaItem.class);
        Assert.assertEquals(back, twice);
    }

    @Test
    public void revSurvivesTheOtherCopiesAndWithKeysRepointsTheObject() {
        final MediaItem rotated = item().withRotated(40L, null);
        Assert.assertEquals(rotated.withHidden(false).getRev(), 1);
        Assert.assertEquals(rotated.withOrg(null).getRev(), 1);
        final MediaItem rekeyed = rotated.withKeys("lodging/a/new.jpg", null, 77L);
        Assert.assertEquals(rekeyed.getId(), "id1");
        Assert.assertEquals(rekeyed.getS3Key(), "lodging/a/new.jpg");
        Assert.assertEquals(rekeyed.getSize(), 77L);
        Assert.assertEquals(rekeyed.getRev(), 1);
    }
}
