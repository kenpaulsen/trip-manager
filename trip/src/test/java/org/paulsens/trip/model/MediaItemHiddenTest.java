package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import org.paulsens.trip.dynamo.DAO;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The serialization-compatibility contract of the {@code hidden} flag: every pre-flag row (no JSON property)
 * MUST read back visible -- the flag is stored inverted precisely so the missing-property default is the
 * safe one.
 */
public class MediaItemHiddenTest {

    private static final ObjectMapper MAPPER = DAO.getInstance().getMapper();

    private static MediaItem item(final Boolean hidden) {
        return new MediaItem("id1", "downloads/x.pdf", "T", "D", "application/pdf", 5L, "home-docs", 0,
                LocalDateTime.of(2026, 8, 1, 12, 0), "who", null, hidden);
    }

    @Test
    public void preFlagJsonReadsVisible() throws Exception {
        final String legacy = "{\"id\":\"id1\",\"s3Key\":\"downloads/x.pdf\",\"size\":5,\"position\":0}";
        Assert.assertFalse(MAPPER.readValue(legacy, MediaItem.class).getHidden(),
                "a row written before the flag existed must stay publicly visible");
    }

    @Test
    public void hiddenRoundTrips() throws Exception {
        final MediaItem hidden = item(true);
        Assert.assertTrue(hidden.getHidden());
        final MediaItem back = MAPPER.readValue(MAPPER.writeValueAsString(hidden), MediaItem.class);
        Assert.assertTrue(back.getHidden());
        Assert.assertEquals(back, hidden);
    }

    @Test
    public void falseNormalizesToNullForStableEquality() throws Exception {
        final MediaItem explicitFalse = item(false);
        final MediaItem nullHidden = item(null);
        Assert.assertEquals(explicitFalse, nullHidden,
                "FALSE and absent must be the same value or round trips break equality");
        final MediaItem back = MAPPER.readValue(MAPPER.writeValueAsString(explicitFalse), MediaItem.class);
        Assert.assertEquals(back, nullHidden);
        Assert.assertFalse(back.getHidden());
    }

    @Test
    public void compatibilityConstructorsAreVisible() {
        final MediaItem tenArg = new MediaItem("i", "k", "t", "d", "ct", 1L, "s", 0,
                LocalDateTime.now(), "u");
        Assert.assertFalse(tenArg.getHidden());
        final MediaItem elevenArg = new MediaItem("i", "k", "t", "d", "ct", 1L, "s", 0,
                LocalDateTime.now(), "u", "small");
        Assert.assertFalse(elevenArg.getHidden());
        Assert.assertEquals(elevenArg.getSmallKey(), "small");
    }

    @Test
    public void withHiddenFlipsOnlyTheFlag() {
        final MediaItem visible = item(null);
        final MediaItem hidden = visible.withHidden(true);
        Assert.assertTrue(hidden.getHidden());
        Assert.assertEquals(hidden.getId(), visible.getId());
        Assert.assertEquals(hidden.getS3Key(), visible.getS3Key());
        Assert.assertEquals(hidden.getUploaded(), visible.getUploaded());
        Assert.assertEquals(hidden.withHidden(false), visible, "a round trip restores the original");
    }
}
