package org.paulsens.trip.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
        counted.forEach((what, times) -> lines.add(field + ": " + what + (times > 1 ? " (" + times + " times)" : "")));
        return lines;
    }

    /** One removal in an author's words: what it is, as they typed it, then why. */
    static String explain(final PageHtml.Drop drop) {
        return switch (drop.kind()) {
            case TAG -> tag(drop.element().toLowerCase(Locale.ROOT));
            case ATTRIBUTE -> attribute(drop.detail().toLowerCase(Locale.ROOT), drop.element());
            case COMMENT -> "the comment \"" + drop.detail()
                    + "\": it is written in a way a browser could read as markup.";
            case CSS_RULE -> cssRule(drop.detail());
        };
    }

    private static String tag(final String element) {
        final String named = "the <" + element + "> tag";
        if ("script".equals(element)) {
            return named + " and everything inside it: script cannot be saved here.";
        }
        if (GOES_WITH_CONTENTS.contains(element)) {
            return named + " and everything inside it: it is not allowed here.";
        }
        if (FORM_PARTS.contains(element)) {
            return named + " (what is inside it stays): a form or form field cannot be saved here.";
        }
        if (DOCUMENT_PARTS.contains(element)) {
            return named + " (what is inside it stays): it belongs to a whole page, and this is one part of a page.";
        }
        if ("link".equals(element)) {
            return named + ": a stylesheet is linked from the organization's stylesheets, not from here.";
        }
        if ("style".equals(element)) {
            return "a <style> block: it sits where a browser would not read it as a style block.";
        }
        return named + " (what is inside it stays): it is not on the list of allowed tags.";
    }

    private static String attribute(final String name, final String element) {
        final String named = "the " + name + " attribute on <" + element + ">";
        if (name.startsWith("on")) {
            return named + ": an event handler runs script, which cannot be saved here.";
        }
        if (ADDRESSES.contains(name)) {
            return named + ": that address is not allowed (use https, or a path on this site that starts with /).";
        }
        final boolean linkName = "name".equals(name) && "a".equalsIgnoreCase(element);
        if ("id".equals(name) || linkName) {
            return named + ": use letters, digits, - and _ only, starting with a letter, and not an id the page"
                    + " itself uses.";
        }
        if ("name".equals(name)) {
            return named + ": only a link (<a>) takes a name here.";
        }
        if ("srcdoc".equals(name)) {
            return named + ": a frame shows an https address, not markup typed here.";
        }
        if (name.startsWith("data-")) {
            return named + ": a script on this site's pages uses that name (pick another data- name), or its value"
                    + " is too long, or a comment or style block was typed inside the name.";
        }
        return named + ": that attribute, or its value, is not allowed there.";
    }

    private static String cssRule(final String opening) {
        final String lower = opening.toLowerCase(Locale.ROOT);
        final String why;
        if (lower.contains("import")) {
            why = "a stylesheet cannot load another one (@import).";
        } else if (lower.contains("data-") && !lower.contains("[value")) {
            why = "a rule cannot test a data- attribute this site's own pages use.";
        } else {
            why = "a rule cannot test a form field's value ([value...]).";
        }
        return "the rule \"" + opening + "\" in a <style> block: " + why;
    }

    /** The one sentence a page says after a save that removed something; empty lines, no sentence. */
    public static String savedNotice(final List<String> lines) {
        return lines.isEmpty() ? "" : "Saved, with some markup removed. " + String.join(" ", lines);
    }
}
