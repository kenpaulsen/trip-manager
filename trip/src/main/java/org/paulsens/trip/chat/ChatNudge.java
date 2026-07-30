package org.paulsens.trip.chat;

/**
 * The pub/sub payload: {@code {"c":"trip:abc","upTo":1769558400123}}.
 *
 * <p><b>A nudge, never a message.</b> It says only "there is something new in this channel up to time T"; a
 * subscriber that receives one runs its ordinary cursor read to find out what. That is what makes a dropped nudge
 * cost latency rather than a message, and it is why two application builds subscribed to the same channel during a
 * blue/green deploy cannot corrupt each other -- neither carries content the other has to understand.
 *
 * <p>Encoding is hand-rolled rather than Jackson because the shape is two fields on the hot path, and because
 * decoding must be <b>total</b>: an unparseable payload still wakes the waiter. A future build that adds a field,
 * or garbage from an unrelated publisher, must degrade to "wake up and read the cursor" rather than throwing on a
 * pub/sub callback thread where nothing useful can catch it.
 */
public final class ChatNudge {

    private static final String UP_TO = "\"upTo\":";

    private ChatNudge() {
    }

    /** The payload to publish after a successful write. */
    public static String encode(final String channelId, final long upToMillis) {
        return "{\"c\":\"" + channelId + "\",\"upTo\":" + upToMillis + "}";
    }

    /**
     * The {@code upTo} watermark, or {@code 0} when the payload is absent, malformed, or from a build that shapes it
     * differently. Zero is safe: the waiter wakes and reads from its own cursor, which is the authority anyway.
     */
    public static long upTo(final String payload) {
        if (payload == null) {
            return 0L;
        }
        final int at = payload.indexOf(UP_TO);
        if (at < 0) {
            return 0L;
        }
        int i = at + UP_TO.length();
        while (i < payload.length() && payload.charAt(i) == ' ') {
            i++;
        }
        final int start = i;
        while (i < payload.length() && Character.isDigit(payload.charAt(i))) {
            i++;
        }
        if (i == start) {
            return 0L;
        }
        try {
            return Long.parseLong(payload.substring(start, i));
        } catch (final NumberFormatException ex) {
            return 0L;
        }
    }
}
