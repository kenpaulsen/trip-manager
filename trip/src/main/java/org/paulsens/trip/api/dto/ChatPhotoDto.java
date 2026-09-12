package org.paulsens.trip.api.dto;

/**
 * One staged chat photo, as the upload endpoint answers it: the keys a send attaches by
 * ({@code attachments:[{key,...}]} on {@code POST messages}) and the URLs a client shows the preview from.
 * URLs are absolute (the CDN, or this server's photo servlet in local mode).
 */
public record ChatPhotoDto(
        String key,
        String smallKey,
        String contentType,
        long size,
        int width,
        int height,
        String url,
        String smallUrl) {
}
