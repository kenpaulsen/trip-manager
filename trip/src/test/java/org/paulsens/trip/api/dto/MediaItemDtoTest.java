package org.paulsens.trip.api.dto;

import java.time.LocalDateTime;
import org.paulsens.trip.model.MediaItem;
import org.testng.Assert;
import org.testng.annotations.Test;

/** {@link MediaItemDto}: the wire shape must carry the row faithfully, plus the server-resolved URL. */
public class MediaItemDtoTest {

    @Test
    public void theFactoryCarriesEveryFieldAndTheResolvedUrl() {
        final LocalDateTime uploaded = LocalDateTime.of(2026, 8, 1, 12, 0);
        final MediaItem item = new MediaItem("m1", "downloads/a.pdf", "Guide", "desc", "application/pdf",
                9L, "home-docs", 2, uploaded, "who@example.com", null, true);

        final MediaItemDto dto = MediaItemDto.of(item, "https://cdn.example/downloads/a.pdf");

        Assert.assertEquals(dto, new MediaItemDto("m1", "downloads/a.pdf", "Guide", "desc",
                "application/pdf", 9L, "home-docs", 2, uploaded, "who@example.com", true,
                "https://cdn.example/downloads/a.pdf", 0, null));
        Assert.assertTrue(dto.toString().contains("downloads/a.pdf"));
    }

    /** A rotated row carries its rev and the separately resolved thumbnail URL. */
    @Test
    public void aRotatedRowCarriesItsRevAndThumbnail() {
        final MediaItem item = new MediaItem("m1", "chat/t/a.jpg", null, null, "image/jpeg", 9L, "tripChat-t", 0,
                null, null, "chat/t/a-small.jpg", null, null).withRotated(7L, "chat/t/a-small.jpg");

        final MediaItemDto dto = MediaItemDto.of(item, "https://cdn.example/chat/t/a.jpg?r=1",
                "https://cdn.example/chat/t/a-small.jpg?r=1");

        Assert.assertEquals(dto.rev(), 1);
        Assert.assertEquals(dto.size(), 7L);
        Assert.assertEquals(dto.thumbUrl(), "https://cdn.example/chat/t/a-small.jpg?r=1");
    }

    /** Null in, null out -- so call sites can map optional lookups without their own guard. */
    @Test
    public void aMissingItemMapsToNull() {
        Assert.assertNull(MediaItemDto.of(null, "https://cdn.example/x"));
    }

    /** The model's null-means-visible convention flattens to a plain boolean on the wire. */
    @Test
    public void hiddenFlattensToABoolean() {
        final MediaItem visible = new MediaItem("m1", "k", "t", null, null, 0L, null, 0, null, null, null,
                null);
        Assert.assertFalse(MediaItemDto.of(visible, null).hidden());
    }
}
