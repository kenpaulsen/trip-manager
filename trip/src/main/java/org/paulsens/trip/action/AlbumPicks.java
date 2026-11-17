package org.paulsens.trip.action;

import java.util.ArrayList;
import java.util.List;

/**
 * The "Choose from Albums" dialog's picks as its Add button sends them: {@code id@fx,fy} joined by {@code ;}, in the
 * order chosen. One parser for every host that takes several photos, each reading as many as IT takes: the trip
 * editor's pictures stop at a trip's limit, the media library one past its batch size (so an over-full Add is named,
 * not silently cut).
 */
final class AlbumPicks {

    /** One pick: an album photo's media id and where to cut it (0 to 1 on each axis, 0.5 = centered). */
    record Pick(String mediaId, double fx, double fy) {
    }

    private AlbumPicks() {
    }

    /**
     * The picks in {@code text}, at most {@code max} of them. Untrusted text: an entry with no id is dropped, and a
     * position that is missing or malformed centers.
     */
    static List<Pick> parse(final String text, final int max) {
        final List<Pick> parsed = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return parsed;
        }
        for (final String entry : text.split(";")) {
            final String[] parts = entry.trim().split("@", 2);
            if (!parts[0].isBlank() && parsed.size() < max) {
                final String[] at = (parts.length == 2 ? parts[1] : "").split(",", 2);
                final double fy = fraction(at.length == 2 ? at[1] : "");
                parsed.add(new Pick(parts[0].trim(), fraction(at[0]), fy));
            }
        }
        return parsed;
    }

    private static double fraction(final String text) {
        try {
            return Double.parseDouble(text.trim());
        } catch (final NumberFormatException ex) {
            return 0.5d;
        }
    }
}
