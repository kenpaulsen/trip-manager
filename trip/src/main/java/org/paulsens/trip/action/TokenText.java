package org.paulsens.trip.action;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.paulsens.trip.chat.MailTemplates;

/**
 * The {@code {{token}}} substitution behind every message a person writes for the site to personalize: a trip
 * reminder ({@link ReminderText}) and the mail merge ({@link MergeText}). Each caller brings its own vocabulary;
 * this class only finds the tokens and fills them.
 *
 * <p>Java-only, never EL: the text is written by a tenant's staff, and evaluating it once let an org-scoped
 * {@code emailAdmin} run code on the shared server (medjugorje#79). A value is written into the result in ONE
 * pass and never read again, so a value that itself contains {@code {{...}}} or {@code #{...}} is just text.
 */
final class TokenText {
    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([^{}]*?)\\s*\\}\\}");

    private TokenText() {
    }

    /** Every {@code {{...}}} name in {@code text}, in order, each once; empty for null. */
    static Set<String> namesIn(final String text) {
        final Set<String> names = new LinkedHashSet<>();
        if (text != null) {
            final Matcher matcher = TOKEN.matcher(text);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    /** The {@code {{...}}} names in {@code text} that {@code known} does not hold, in order, each once. */
    static List<String> unknown(final String text, final Set<String> known) {
        final List<String> unknown = new ArrayList<>();
        for (final String name : namesIn(text)) {
            if (!known.contains(name)) {
                unknown.add(name);
            }
        }
        return unknown;
    }

    /**
     * {@code text} with every token {@code known} holds replaced by its value: HTML-escaped when {@code html}
     * (a mail body), as is otherwise (a subject, a push line). A known token with no value becomes empty; an
     * unknown one is left as typed, which only text that skipped {@link #unknown} can contain.
     */
    static String substitute(final String text, final Map<String, String> values, final Set<String> known,
            final boolean html) {
        if (text == null) {
            return "";
        }
        final Matcher matcher = TOKEN.matcher(text);
        final StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            final String name = matcher.group(1);
            final String raw = valueOf(values, name);
            final String value = !known.contains(name) ? matcher.group() : html ? MailTemplates.escape(raw) : raw;
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String valueOf(final Map<String, String> values, final String name) {
        final String value = (values == null) ? null : values.get(name);
        return (value == null) ? "" : value;
    }
}
