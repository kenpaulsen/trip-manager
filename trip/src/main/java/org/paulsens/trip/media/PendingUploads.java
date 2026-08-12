package org.paulsens.trip.media;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Uploads that are mid-dialog: the bytes arrive in one request (the upload listener), the crop decision in a
 * later one, and this registry is what connects them. An entry is claimable only by the person who created it
 * — {@link #peek} checks the owner — so a token leaking (or being guessed) buys nothing but that person's own
 * pending upload. Same authorization idea as {@link ChatPhotoStaging}, one step earlier in the pipeline.
 *
 * <p>Held OUTSIDE any JSF scope on purpose: sessions serialize to Valkey on every request, and a multi-MB
 * original riding along in viewScope would make each postback pay to ship it there and back. Here the bytes
 * stay in this process's heap, capped by {@link #MAX_TOTAL_BYTES} with oldest-first eviction, and expire
 * after {@link #TTL} — abandoning the dialog costs nothing and leaks nothing.
 *
 * <p>ONE shared instance ({@link #getPendingUploads()}), the {@code ChatPhotos.getChatPhotos()} lesson: the
 * requests that write and read this map arrive through different edges, and any context-sensitive lookup
 * risks handing them different instances.
 */
public final class PendingUploads {

    /**
     * The session key every upload dialog parks its current token under. Shared (rather than per-feature)
     * because the crop dialog's image source must be reachable through ONE fixed EL name: PrimeFaces stores
     * the dynamic-content expression as a STRING and re-evaluates it on the streaming request, where a
     * Facelets {@code ui:param} alias no longer exists — an aliased expression 404s every image.
     */
    public static final String SESSION_TOKEN_KEY = "pendingPhotoToken";

    /** Long enough to crop at leisure, short enough that abandoned bytes don't squat on the heap. */
    static final Duration TTL = Duration.ofMinutes(15);

    /** Total-bytes budget across all pending entries. */
    static final long MAX_TOTAL_BYTES = 100L * 1024 * 1024;

    private static volatile PendingUploads shared;

    /**
     * One pending upload. {@code previewJpeg} is what the crop dialog displays (browser-renderable even when
     * the original is HEIC); {@code original} is what the confirmed crop is applied to. {@code purpose} and
     * {@code targetId} record what the upload was FOR ("profile"/person id, "chat"/trip id) so a token minted
     * in one dialog cannot be finalized as something else.
     */
    public record Pending(String token, byte[] original, byte[] previewJpeg,
            int previewWidth, int previewHeight, int fullWidth, int fullHeight,
            String ownerId, String purpose, String targetId, Instant createdAt) {

        /**
         * Scales a crop selection made against the PREVIEW into the full-resolution space the original will
         * actually be cropped in — the one place that ratio is computed, shared by every crop dialog.
         */
        public PhotoProcessor.CropRect scaleToFull(final int left, final int top,
                final int width, final int height) {
            final double scale = fullWidth / (double) previewWidth;
            return new PhotoProcessor.CropRect(
                    (int) Math.round(left * scale),
                    (int) Math.round(top * scale),
                    (int) Math.round(width * scale),
                    (int) Math.round(height * scale));
        }
    }

    private final SecureRandom random = new SecureRandom();

    /** Insertion-ordered so eviction is oldest-first. All access synchronized on this map. */
    private final Map<String, Pending> byToken = new LinkedHashMap<>();
    private long totalBytes;
    /** Instance rather than the constant so a test can exercise eviction without 100 MB of fixtures. */
    private long maxTotalBytes = MAX_TOTAL_BYTES;

    public static PendingUploads getPendingUploads() {
        PendingUploads instance = shared;
        if (instance == null) {
            synchronized (PendingUploads.class) {
                instance = shared;
                if (instance == null) {
                    instance = new PendingUploads();
                    shared = instance;
                }
            }
        }
        return instance;
    }

    void maxTotalBytesForTest(final long maxBytes) {
        this.maxTotalBytes = maxBytes;
    }

    /** Registers an upload and returns its entry, token freshly minted. */
    public Pending put(final byte[] original, final PhotoProcessor.PreviewImage preview,
            final String ownerId, final String purpose, final String targetId) {
        return put(new Pending(newToken(), original, preview.previewJpeg(), preview.previewWidth(),
                preview.previewHeight(), preview.fullWidth(), preview.fullHeight(),
                ownerId, purpose, targetId, Instant.now()));
    }

    /** Test seam: lets a test supply {@code createdAt} so expiry is exercisable without sleeping. */
    Pending put(final Pending pending) {
        synchronized (byToken) {
            drainExpired();
            byToken.put(pending.token(), pending);
            totalBytes += bytesOf(pending);
            final var iterator = byToken.entrySet().iterator();
            while (totalBytes > maxTotalBytes && iterator.hasNext()) {
                totalBytes -= bytesOf(iterator.next().getValue());
                iterator.remove();
            }
        }
        return pending;
    }

    /** @return the entry, only when it exists, has not expired, and belongs to {@code ownerId}. */
    public Optional<Pending> peek(final String token, final String ownerId) {
        if (token == null || ownerId == null) {
            return Optional.empty();
        }
        synchronized (byToken) {
            final Pending pending = byToken.get(token);
            if (pending == null || isExpired(pending) || !ownerId.equals(pending.ownerId())) {
                return Optional.empty();
            }
            return Optional.of(pending);
        }
    }

    /** Discards an entry once its crop is applied (or the dialog is cancelled). */
    public void consume(final String token) {
        if (token == null) {
            return;
        }
        synchronized (byToken) {
            final Pending removed = byToken.remove(token);
            if (removed != null) {
                totalBytes -= bytesOf(removed);
            }
        }
    }

    int size() {
        synchronized (byToken) {
            return byToken.size();
        }
    }

    private void drainExpired() {
        final var iterator = byToken.entrySet().iterator();
        while (iterator.hasNext()) {
            final Pending pending = iterator.next().getValue();
            if (isExpired(pending)) {
                totalBytes -= bytesOf(pending);
                iterator.remove();
            }
        }
    }

    private static boolean isExpired(final Pending pending) {
        return pending.createdAt().isBefore(Instant.now().minus(TTL));
    }

    private static long bytesOf(final Pending pending) {
        return (long) pending.original().length + pending.previewJpeg().length;
    }

    private String newToken() {
        final byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
