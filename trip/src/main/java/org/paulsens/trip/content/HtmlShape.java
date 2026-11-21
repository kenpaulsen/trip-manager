package org.paulsens.trip.content;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.owasp.html.HtmlSanitizer;

/**
 * What a piece of HTML amounts to once spelling is set aside: its tags in order, each with its attributes, and its
 * text. Two bodies with the same shape hold the same content, however differently they are written, which is the
 * question a clean has to answer before a stored row is rewritten ({@code migrate.HtmlCleanCli}) and the one
 * {@code PageHtmlTest} asks of every production construct.
 *
 * <p>{@link PageHtml#cleanNoting} already lists what it removed, but that list is the cleaner's own account. This
 * is the second opinion: it reads the body before and the body after the same way and compares them, so a loss
 * the cleaner did not report still shows.
 *
 * <p>Set aside, because a browser reads both spellings alike: quoting and entity forms, attribute order, the
 * order of a class list and of a style's declarations, end tags (written or implied), the {@code tbody} a table
 * gets whether typed or not, the {@code noopener} a clean adds to a link that opens a new tab, and white space.
 * Comments are NOT set aside: an author's parked text is content, though no page shows it, so each comment's
 * words are part of the shape (their place is not: a comment between two list items is moved into the next one).
 * White space is set aside WHOLE, because the sanitizer drops it between list items and table rows, where it
 * shows nothing; whether a space that matters went missing is the screenshot comparison's question, not this one's.
 */
public record HtmlShape(List<String> tags, String text, List<String> comments) {

    /** Written by every table whether its author typed it or not, so its presence says nothing. */
    private static final Set<String> IMPLIED = Set.of("tbody");

    public static HtmlShape of(final String html) {
        final Reader reader = new Reader();
        HtmlSanitizer.sanitize(html == null ? "" : html, reader);
        return new HtmlShape(List.copyOf(reader.tags), reader.text.toString(), commentsOf(html == null ? "" : html));
    }

    /**
     * Each comment's text, read the plainest way there is: from {@code <!--} to the next {@code -->}. On purpose
     * not the cleaner's own reading ({@code PageAsides}), so that the two can disagree.
     */
    private static List<String> commentsOf(final String html) {
        final List<String> comments = new ArrayList<>();
        int open = html.indexOf("<!--");
        while (open >= 0) {
            final int close = html.indexOf("-->", open + 4);
            if (close < 0) {
                break;
            }
            comments.add(html.substring(open + 4, close));
            open = html.indexOf("<!--", close + 3);
        }
        return List.copyOf(comments);
    }

    /** The tags of {@code this} that {@code other} lacks, each once per missing occurrence, in order. */
    public List<String> tagsMissingFrom(final HtmlShape other) {
        final List<String> remaining = new ArrayList<>(other.tags);
        final List<String> missing = new ArrayList<>();
        for (final String tag : tags) {
            if (!remaining.remove(tag)) {
                missing.add(tag);
            }
        }
        return missing;
    }

    private static String describe(final String element, final List<String> attributes) {
        final Map<String, String> sorted = new TreeMap<>();
        for (int i = 0; i + 1 < attributes.size(); i += 2) {
            final String name = attributes.get(i).toLowerCase(Locale.ROOT);
            // A browser takes the first of two attributes with one name, and so does the cleaner.
            sorted.putIfAbsent(name, canonical(name, attributes.get(i + 1)));
        }
        sorted.values().removeIf(String::isEmpty);
        final String listed = sorted.entrySet().stream()
                .map(attribute -> attribute.getKey() + "=" + attribute.getValue())
                .collect(Collectors.joining(" "));
        return listed.isEmpty() ? element : element + " " + listed;
    }

    private static String canonical(final String name, final String value) {
        return switch (name) {
            case "class" -> words(value, "");
            case "rel" -> words(value, "noopener");
            case "style" -> Arrays.stream(value.split(";")).map(HtmlShape::declaration)
                    .filter(rule -> !rule.isEmpty()).sorted().collect(Collectors.joining(";"));
            default -> value.trim();
        };
    }

    private static String words(final String value, final String ignored) {
        return Arrays.stream(value.trim().split("\\s+")).filter(word -> !word.isEmpty() && !word.equals(ignored))
                .sorted().collect(Collectors.joining(" "));
    }

    private static String declaration(final String rule) {
        final int colon = rule.indexOf(':');
        if (colon < 0) {
            return rule.trim();
        }
        return rule.substring(0, colon).trim().toLowerCase(Locale.ROOT) + ":"
                + rule.substring(colon + 1).trim().replaceAll("\\s+", " ");
    }

    /** Receives the sanitizer's reading of a body, tag by tag, with nothing filtered and nothing written. */
    private static final class Reader implements HtmlSanitizer.Policy {
        private final List<String> tags = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();

        @Override
        public void openDocument() {
            // Nothing to prepare: the shape is only what the tags and the text add up to.
        }

        @Override
        public void closeDocument() {
            // Nothing to finish.
        }

        @Override
        public void openTag(final String element, final List<String> attributes) {
            final String name = element.toLowerCase(Locale.ROOT);
            if (!IMPLIED.contains(name)) {
                tags.add(describe(name, attributes));
            }
        }

        @Override
        public void closeTag(final String element) {
            // End tags are spelling: a browser supplies the ones a body leaves out.
        }

        @Override
        public void text(final String content) {
            text.append(content.replaceAll("[\\s\\u00a0]+", ""));
        }
    }
}
