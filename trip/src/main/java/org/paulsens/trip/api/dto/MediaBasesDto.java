package org.paulsens.trip.api.dto;

/**
 * Where photo keys resolve to URLs: {@code chatPhotos + attachment.s3Key}, {@code profilePhotos + slot key}.
 * Both end in a slash. {@code remote} says whether that is the CDN (production) or this server's own
 * local-mode servlets, which a client may use to decide how aggressively to cache.
 */
public record MediaBasesDto(String chatPhotos, String profilePhotos, boolean remote) {
}
