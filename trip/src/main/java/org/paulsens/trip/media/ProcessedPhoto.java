package org.paulsens.trip.media;

/**
 * The two stored renditions of one uploaded chat photo, plus its display dimensions.
 *
 * <p>{@code width}/{@code height} are the dimensions of the image <em>as displayed</em> (EXIF orientation
 * already applied), which for a portrait phone photo are the transpose of what the pixel data says. They are
 * what the chat client needs to reserve layout space, so the oriented pair is the only one worth carrying.
 *
 * <p>{@code smallBytes} may be the same array as {@code fullBytes} (see {@link #smallIsFull()}): an image
 * already at or under the display width, or an animated GIF, needs no second rendition — the caller should
 * then store ONE object and point both keys at it rather than paying for a byte-identical twin.
 */
public record ProcessedPhoto(
        byte[] fullBytes,
        String fullContentType,
        String fullExtension,
        byte[] smallBytes,
        String smallContentType,
        String smallExtension,
        int width,
        int height) {

    /** @return true when the small rendition is literally the full one, so only one object needs storing. */
    public boolean smallIsFull() {
        return smallBytes == fullBytes;
    }
}
