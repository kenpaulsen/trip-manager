package org.paulsens.trip.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.paulsens.trip.i18n.Text;

/**
 * What {@link PageHtml} is taking out of something an author typed, said so that the author can find it and fix
 * it: one line per kind of thing, naming the exact tag, attribute, style or comment, how many times it occurs,
 * and why it cannot stay.
 *
 * <p>The owner's rule (2026-11-21): nothing is ever stripped silently, and never later. The editor shows these
 * lines while the author is still typing, with the field still holding what they typed, so that they can change
 * it before it is stripped; a save that strips anything says the same lines again.
 */
public final class StripNotes {

    /** The sanitizer drops these with everything inside them; any other tag goes and leaves its contents. */
    private static final Set<String> GOES_WITH_CONTENTS = Set.of("script", "title", "noscript", "object");

    private static final Set<String> FORM_PARTS = Set.of("form", "input", "button", "select", "option", "textarea",
            "label", "fieldset", "legend");
    private static final Set<String> DOCUMENT_PARTS = Set.of("html", "head", "body", "meta", "base", "title");
    private static final Set<String> ADDRESSES = Set.of("href", "src", "poster");

    private StripNotes() {
    }

    /**
     * The lines for one field, each starting with the field's name; empty when nothing is removed.
     *
     * @param field   what the author calls the field ("Title", a prompt's label)
     * @param cleaned what the cleaner made of the field's text
     */
    public static List<String> lines(final String field, final PageHtml.Cleaned cleaned) {
        final Map<String, Integer> counted = new LinkedHashMap<>();
        for (final PageHtml.Drop drop : cleaned.drops()) {
            counted.merge(explain(drop), 1, Integer::sum);
        }
        final List<String> lines = new ArrayList<>(counted.size());
        for (final String what : withCounts(counted)) {
            lines.add(Text.get("server.stripNotes.line", field, what));
        }
        return lines;
    }

    /**
     * Each distinct thing once, in the order first found, followed by how many times it occurs when that is more
     * than once. Shared by every list of markup problems ({@link HtmlCheck} too), so that all of them count alike.
     */
    static List<String> withCounts(final Map<String, Integer> counted) {
        final List<String> out = new ArrayList<>(counted.size());
        // The count goes in twice: as a number, which picks the plural form, and as text, which is what shows
        // (a number would gain a thousands separator, and a pasted document can repeat a tag that often).
        counted.forEach((what, times) -> out.add(times > 1
                ? Text.get("server.stripNotes.repeated", what, times, String.valueOf(times)) : what));
        return out;
    }

    /** One removal in an author's words: what it is, as they typed it, then why. */
    static String explain(final PageHtml.Drop drop) {
        return switch (drop.kind()) {
            case TAG -> tag(drop.element().toLowerCase(Locale.ROOT));
            case ATTRIBUTE -> attribute(drop.detail().toLowerCase(Locale.ROOT), drop.element());
            case COMMENT -> Text.get("server.stripNotes.comment", drop.detail());
            case CSS_RULE -> cssRule(drop.detail());
        };
    }

    private static String tag(final String element) {
        if ("script".equals(element)) {
            return Text.get("server.stripNotes.tagScript");
        }
        if (GOES_WITH_CONTENTS.contains(element)) {
            return Text.get("server.stripNotes.tagWithContents", element);
        }
        if (FORM_PARTS.contains(element)) {
            return Text.get("server.stripNotes.tagFormPart", element);
        }
        if (DOCUMENT_PARTS.contains(element)) {
            return Text.get("server.stripNotes.tagDocumentPart", element);
        }
        if ("link".equals(element)) {
            return Text.get("server.stripNotes.tagLink");
        }
        if ("style".equals(element)) {
            return Text.get("server.stripNotes.tagStyle");
        }
        return Text.get("server.stripNotes.tagNotAllowed", element);
    }

    private static String attribute(final String name, final String element) {
        if (name.startsWith("on")) {
            return Text.get("server.stripNotes.attrEventHandler", name, element);
        }
        if (ADDRESSES.contains(name)) {
            return Text.get("server.stripNotes.attrAddress", name, element);
        }
        final boolean linkName = "name".equals(name) && "a".equalsIgnoreCase(element);
        if ("id".equals(name) || linkName) {
            return Text.get("server.stripNotes.attrId", name, element);
        }
        if ("name".equals(name)) {
            return Text.get("server.stripNotes.attrName", name, element);
        }
        if ("srcdoc".equals(name)) {
            return Text.get("server.stripNotes.attrSrcdoc", name, element);
        }
        if (name.startsWith("data-")) {
            return Text.get("server.stripNotes.attrData", name, element);
        }
        return Text.get("server.stripNotes.attrNotAllowed", name, element);
    }

    private static String cssRule(final String opening) {
        final String lower = opening.toLowerCase(Locale.ROOT);
        if (lower.contains("import")) {
            return Text.get("server.stripNotes.cssImport", opening);
        }
        if (lower.contains("data-") && !lower.contains("[value")) {
            return Text.get("server.stripNotes.cssDataTest", opening);
        }
        return Text.get("server.stripNotes.cssValueTest", opening);
    }

    /** The one sentence a page says after a save that removed something; empty lines, no sentence. */
    public static String savedNotice(final List<String> lines) {
        return lines.isEmpty() ? "" : Text.get("server.stripNotes.savedNotice", String.join(" ", lines));
    }
}
