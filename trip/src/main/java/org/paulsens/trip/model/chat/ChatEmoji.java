package org.paulsens.trip.model.chat;

import java.io.Serializable;
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
    // TODO(config-store): promote to config.getString("chat.reactions.palette", ...) if a trip wants its own
    private static final List<String> PALETTE = List.of(
            "👍", "❤️", "😂", "🙏", "🎉", "😮", "😢", "✅");

    private static final Set<String> ALLOWED = Set.copyOf(PALETTE);

    private ChatEmoji() {
    }

    /** The palette, in display order. */
    public static List<String> palette() {
        return PALETTE;
    }

    public static boolean isAllowed(final String emoji) {
        return emoji != null && ALLOWED.contains(emoji);
    }
}
