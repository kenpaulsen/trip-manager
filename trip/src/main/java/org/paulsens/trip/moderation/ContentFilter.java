package org.paulsens.trip.moderation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The objectionable-content filter behind {@code chat.blockedTerms}: whole-word, case-insensitive matching of
 * a comma-separated term list against a message body.
 *
 * <p>Pure and process-local. The compiled pattern is memoized against the raw setting text, so a send costs
 * one string comparison when the list has not changed and one compile when it has; there is no per-send
 * config parse. A malformed term cannot break the filter -- every term is quoted before it enters the
 * pattern -- and an empty list matches nothing, which is how the filter is turned off.
 */
public final class ContentFilter {

    /** The last (raw list, compiled pattern) pair; null pattern means "matches nothing". */
    private record Compiled(String raw, Pattern pattern) {
    }

    private static final ContentFilter SHARED = new ContentFilter();

    private final AtomicReference<Compiled> compiled = new AtomicReference<>(new Compiled("", null));

    /** The one instance every transport shares, so the page and the API filter identically. */
    public static ContentFilter shared() {
        return SHARED;
    }

    /**
     * The first blocked term found in {@code text} against {@code rawTerms}, or {@code null} when the text is
     * clean (or the list is empty). Returned rather than a boolean so a refusal message and an audit record can
     * say WHAT matched without re-running the scan.
     */
    public String firstMatch(final String rawTerms, final String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        final Pattern pattern = patternFor(rawTerms == null ? "" : rawTerms);
        if (pattern == null) {
            return null;
        }
        final Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group().toLowerCase(Locale.ROOT) : null;
    }

    /** @return whether {@code text} contains any of the terms as a whole word. */
    public boolean blocks(final String rawTerms, final String text) {
        return firstMatch(rawTerms, text) != null;
    }

    /** The terms in a raw list, trimmed, lower-cased, blanks dropped -- the same parse the pattern uses. */
    public static List<String> parse(final String rawTerms) {
        final List<String> terms = new ArrayList<>();
        if (rawTerms == null) {
            return terms;
        }
        for (final String piece : rawTerms.split(",")) {
            final String term = piece.trim().toLowerCase(Locale.ROOT);
            if (!term.isEmpty() && !terms.contains(term)) {
                terms.add(term);
            }
        }
        return terms;
    }

    private Pattern patternFor(final String rawTerms) {
        final Compiled current = compiled.get();
        if (current.raw().equals(rawTerms)) {
            return current.pattern();
        }
        final Compiled fresh = new Compiled(rawTerms, compile(rawTerms));
        compiled.set(fresh);
        return fresh.pattern();
    }

    /**
     * One alternation of quoted terms between Unicode word boundaries. {@code \\b} rather than {@code \\s}
     * so "shit" is caught inside "shit." and "SHIT!" but not inside "Shitake" is NOT a goal -- a word boundary
     * is what people expect a "whole word" filter to mean, and longer terms cover the rest.
     */
    private static Pattern compile(final String rawTerms) {
        final List<String> terms = parse(rawTerms);
        if (terms.isEmpty()) {
            return null;
        }
        final StringBuilder alternation = new StringBuilder("(?<![\\p{L}\\p{N}])(?:");
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) {
                alternation.append('|');
            }
            alternation.append(Pattern.quote(terms.get(i)));
        }
        alternation.append(")(?![\\p{L}\\p{N}])");
        return Pattern.compile(alternation.toString(),
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
    }
}
