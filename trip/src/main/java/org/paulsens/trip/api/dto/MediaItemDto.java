package org.paulsens.trip.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import org.paulsens.trip.model.MediaItem;

/**
 * One managed media file over the wire.
 *
 * <p>{@code key} is the S3 object key (the file's identity in its URL); {@code id} is the row key, stable
 * across renames. {@code url} is the CDN URL a client should actually fetch, precomputed here because deriving
 * it needs the deployment's base URL, which only the server knows; {@code thumbUrl} is the same for the small
 * display rendition (the full one when there is none). Both already end in {@code ?r={rev}} once the picture
 * was rotated in place ({@code rev} > 0), so a client that just uses them never shows a stale cached copy.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MediaItemDto(
        String id,
        String key,
        String title,
        String description,
        String contentType,
        long size,
        String slot,
        int position,
        LocalDateTime uploaded,
        String uploadedBy,
        boolean hidden,
        String url,
        int rev,
        String thumbUrl) {

    /** {@link #of(MediaItem, String, String)} with no separate thumbnail URL. */
    public static MediaItemDto of(final MediaItem item, final String url) {
        return of(item, url, null);
    }

    /**
     * The wire shape of {@code item}, with {@code url} and {@code thumbUrl} as the server resolved them (cache
     * buster included); a null item maps to null.
     */
    public static MediaItemDto of(final MediaItem item, final String url, final String thumbUrl) {
        if (item == null) {
            return null;
        }
        return new MediaItemDto(item.getId(), item.getS3Key(), item.getTitle(), item.getDescription(),
                item.getContentType(), item.getSize(), item.getSlot(), item.getPosition(), item.getUploaded(),
                item.getUploadedBy(), item.getHidden(), url, item.getRev(), thumbUrl);
    }
}
