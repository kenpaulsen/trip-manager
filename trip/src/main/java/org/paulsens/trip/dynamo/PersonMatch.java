package org.paulsens.trip.dynamo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.paulsens.trip.cache.SearchIndex;
import org.paulsens.trip.model.Person;

/**
 * THE rule for "does this person match a people search", shared by the cached directory search
 * ({@link PersonDAO#searchPeople}) and every in-memory people list (trip Contacts, Org People), so a query finds
 * the same people on every page.
 *
 * <p>A query is split into words, and every word must match the person. A word matches when it is CONTAINED in
 * the first name, last name or nickname, when its digits are contained in the cell's digits (only for a
 * phone-like word, so "(555) 867" and "555867" both find "555-867-5309"), or when the email STARTS with it.
 * Email is prefix-only by owner decision (2026-11-12): "gmail" or "com" would otherwise match half the site.</p>
 *
 * <p>The directory search answers from a {@link SearchIndex}, which can only look up by prefix. Indexing every
 * SUFFIX of a field turns a prefix lookup into a contains lookup, so {@link #indexTokens} emits all suffixes of
 * the name fields and of the cell's digits, but only the whole email (and whole raw cell). The index lookup and
 * this matcher must agree, which is why both live here.</p>
 */
public final class PersonMatch {
    /** Punctuation a phone-like word may carry besides digits (spaces split words, so none here). */
    private static final String PHONE_PUNCTUATION = "()+.-";

    private PersonMatch() {
    }

    /** The normalized, non-empty words of a query (whitespace or comma separated). */
    public static List<String> queryWords(final String query) {
        final String normalized = SearchIndex.normalizeToken(query);
        return Stream.of(normalized.split("[\\s,]+"))
                .filter(word -> !word.isEmpty())
                .toList();
    }

    /** True when every word of {@code words} matches {@code person}; false for no person or no words. */
    public static boolean matchesAll(final Person person, final List<String> words) {
        return person != null && !words.isEmpty() && words.stream().allMatch(word -> matches(person, word));
    }

    /** True when {@code query} has words and every one matches {@code person}. */
    public static boolean matchesQuery(final Person person, final String query) {
        return matchesAll(person, queryWords(query));
    }

    /** One normalized word against one person; see the class comment for the rule. */
    static boolean matches(final Person person, final String word) {
        if (nameFields(person).anyMatch(field -> field.contains(word))) {
            return true;
        }
        if (isPhoneLike(word) && digits(person.getCell()).contains(digits(word))) {
            return true;
        }
        return SearchIndex.normalizeToken(person.getEmail()).startsWith(word);
    }

    /**
     * The word the directory search looks up in the index: the longest word (the most selective prefix), as
     * digits only when it is phone-like, because the index holds the cell as digit suffixes.
     */
    static String lookupWord(final List<String> words) {
        final String longest = words.stream().reduce((a, b) -> b.length() > a.length() ? b : a).orElse("");
        return isPhoneLike(longest) ? digits(longest) : longest;
    }

    /** The search-index tokens for one person: suffixes of names and cell digits, whole email and raw cell. */
    static Set<String> indexTokens(final Person person) {
        final Set<String> tokens = new HashSet<>();
        if (person == null) {
            return tokens;
        }
        nameFields(person).forEach(field -> addSuffixes(tokens, field));
        addSuffixes(tokens, digits(person.getCell()));
        Stream.of(person.getEmail(), person.getCell())
                .map(SearchIndex::normalizeToken)
                .filter(token -> !token.isEmpty())
                .forEach(tokens::add);
        return tokens;
    }

    private static Stream<String> nameFields(final Person person) {
        return Stream.of(person.getFirst(), person.getLast(), person.getNickname())
                .map(SearchIndex::normalizeToken)
                .filter(field -> !field.isEmpty());
    }

    private static void addSuffixes(final Set<String> tokens, final String value) {
        for (int start = 0; start < value.length(); start++) {
            final String suffix = value.substring(start).trim();
            if (!suffix.isEmpty()) {
                tokens.add(suffix);
            }
        }
    }

    /**
     * Digits and phone punctuation only, with at least one digit. A character loop rather than a regex: the
     * obvious pattern ({@code [0-9()+.-]*[0-9][0-9()+.-]*}) backtracks badly on long input (SpotBugs REDOS).
     */
    private static boolean isPhoneLike(final String word) {
        if (word == null || word.isEmpty()) {
            return false;
        }
        boolean digit = false;
        for (int i = 0; i < word.length(); i++) {
            final char c = word.charAt(i);
            if (c >= '0' && c <= '9') {
                digit = true;
            } else if (PHONE_PUNCTUATION.indexOf(c) < 0) {
                return false;
            }
        }
        return digit;
    }

    private static String digits(final String raw) {
        if (raw == null) {
            return "";
        }
        final StringBuilder out = new StringBuilder(raw.length());
        raw.chars().filter(c -> c >= '0' && c <= '9').forEach(c -> out.append((char) c));
        return out.toString();
    }
}
