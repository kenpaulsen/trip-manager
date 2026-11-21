package org.paulsens.trip.content;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Remembers what {@link PageHtml} made of a body, so that a page which writes the same stored values on every
 * request (a home page holds twenty) parses each one once and not once per visitor.
 *
 * <p>Safe to keep for as long as the process runs: a clean is a pure function of its input, with no clock, no
 * caller and no setting in it, so an entry never goes stale and nothing ever has to invalidate one. The key is
 * the body itself, not a hash of it, so two bodies cannot share an entry. Bounded two ways: the least recently
 * used entry goes when there are {@value #MAX_ENTRIES}, and a body longer than {@value #MAX_LENGTH} characters is
 * cleaned every time and never held (a few of those would otherwise be most of the memory).
 */
final class CleanMemo {

    static final int MAX_ENTRIES = 2000;
    static final int MAX_LENGTH = 60_000;

    private final Map<String, PageHtml.Cleaned> held = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, PageHtml.Cleaned> eldest) {
            return super.size() > MAX_ENTRIES;
        }
    };

    /** What {@code cleaner} makes of {@code html}: remembered, or worked out now and remembered. */
    PageHtml.Cleaned get(final String html, final Function<String, PageHtml.Cleaned> cleaner) {
        if (html.length() > MAX_LENGTH) {
            return cleaner.apply(html);
        }
        final PageHtml.Cleaned known = lookUp(html);
        if (known != null) {
            return known;
        }
        // Cleaned outside the lock: two requests may clean one new body at once, and both get the same answer.
        final PageHtml.Cleaned cleaned = cleaner.apply(html);
        remember(html, cleaned);
        return cleaned;
    }

    private synchronized PageHtml.Cleaned lookUp(final String html) {
        return held.get(html);
    }

    private synchronized void remember(final String html, final PageHtml.Cleaned cleaned) {
        held.put(html, cleaned);
    }

    synchronized int size() {
        return held.size();
    }
}
