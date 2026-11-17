package org.paulsens.trip.api.dto;

import java.time.LocalDateTime;
import org.paulsens.trip.action.AlbumPhotoSource;

/**
 * One trip album a chat's "Choose from Albums" offers, as {@code GET chat/channels/{channelId}/album-sources}
 * lists it (docs/api-contract.md, chat photos). {@code current} marks the chat's own trip, listed first even while
 * its album is empty; {@code photoCount} counts the photos offered, {@code withheld} those kept out of public view
 * (never offered, so a client can say why one the person saw in the album is missing).
 */
public record AlbumSourceDto(
        String tripId,
        String title,
        LocalDateTime startDate,
        boolean current,
        int photoCount,
        int withheld) {

    /** The wire shape of one album the website's dialog lists. */
    public static AlbumSourceDto of(final AlbumPhotoSource.Album album) {
        return new AlbumSourceDto(album.tripId(), album.title(), album.startDate(), album.current(),
                album.photos().size(), album.withheld());
    }
}
