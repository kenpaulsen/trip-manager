package org.paulsens.trip.model.chat;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The reaction palette, and the only thing accepted as a reaction.
 *
 * <p><b>An allowlist, not a validator.</b> A reaction is written by any member, is not rate limited, is not
 * moderatable and renders in everyone's browser — so accepting arbitrary text here would create a second message
 * channel that bypasses the message size limit, the rate limiter, mute enforcement and admin delete. Restricting
 * it to a fixed palette closes all of that at once, and it costs nothing: the client renders a picker, so a
 * closed set is what the interface offers anyway.
 *
 * <p>The palette is also what keeps the Dynamo sort key well formed. Reaction rows key on
 * {@code {msgId}#{personId}#{emoji}}, so an emoji containing {@code '#'} would shift the field boundaries and let
 * two different (person, emoji) pairs collide on one key — silently overwriting each other's reaction. Nothing in
 * the palette contains {@code '#'}, and {@link #isAllowed} is the guard that keeps it that way.
 *
 * <p>Pure functions only — no instance state. {@code Serializable} only to satisfy the model package sweep.
 */
public final class ChatEmoji implements Serializable {

    /**
     * Deliberately small, and compared as exact strings rather than normalised.
     *
     * <p>{@code ❤️} is U+2764 plus a U+FE0F variation selector, so it is two code points where the others are one.
     * That is fine — and it is the reason matching is exact: a client that sent bare U+2764 would be rejected
     * rather than silently stored as a third distinct "emoji" that renders the same but counts separately. The
     * palette is the wire format, so whatever a client sends must equal an entry byte for byte.
     */
    private static final List<String> PALETTE = List.of(
            "👍", "❤️", "😂", "🙏", "\uD83D\uDCFF", "🎉", "😮", "😢", "😊", "✅");

    private static final Set<String> ALLOWED = Set.copyOf(PALETTE);

    private ChatEmoji() {
    }

    /** The built-in palette, in display order, used when the configured one is missing or unusable. */
    public static List<String> palette() {
        return PALETTE;
    }

    /**
     * Reads a configured palette from its comma-separated form.
     *
     * <p>Parsing lives here rather than at the config call site so that the {@code '#'} rule above is enforced in
     * the same class that states it: an administrator typing an emoji containing {@code '#'} would otherwise
     * shift the reaction sort key's field boundaries and let two people's reactions overwrite each other. Such an
     * entry is dropped rather than failing the whole list, so one bad character does not empty the picker.
     *
     * <p>An unusable list falls back to the built-in palette. Falling back to nothing would leave the picker
     * empty and every incoming reaction invalid, which reads as "reactions are broken" rather than as a typo in
     * a setting.
     */
    public static List<String> parsePalette(final String csv) {
        if (csv == null || csv.isBlank()) {
            return PALETTE;
        }
        final List<String> parsed = new ArrayList<>();
        for (final String part : csv.split(",")) {
            final String emoji = part.trim();
            if (!emoji.isEmpty() && !emoji.contains("#") && !parsed.contains(emoji)) {
                parsed.add(emoji);
            }
        }
        return parsed.isEmpty() ? PALETTE : List.copyOf(parsed);
    }

    /** Whether a client's reaction is one the built-in palette offers. */
    public static boolean isAllowed(final String emoji) {
        return emoji != null && ALLOWED.contains(emoji);
    }

    /** Whether a client's reaction is one the given palette offers. Exact match, never normalised. */
    public static boolean isAllowed(final String emoji, final List<String> palette) {
        return emoji != null && palette != null && palette.contains(emoji);
    }
}
