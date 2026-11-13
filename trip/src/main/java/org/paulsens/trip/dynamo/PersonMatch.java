package org.paulsens.trip.dynamo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.paulsens.trip.cache.SearchIndex;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PrivacySettings;

/**
 * THE rule for "does this person match a people search", shared by the cached directory search
 * ({@link PersonDAO#searchPeople}) and every in-memory people list (trip Contacts, Org People, the admin People
 * page, check-in), so a query finds the same people on every page.
 *
 * <p>A query is split into words, and every word must match the person. A word matches when it is CONTAINED in
 * the first name, last name or nickname, when its digits are contained in the cell's digits (only for a
 * phone-like word, so "(555) 867" and "555867" both find "555-867-5309"), or when the email STARTS with it.
 * Email is prefix-only by owner decision (2026-11-12): "gmail" or "com" would otherwise match half the site.</p>
 *
 * <p><b>Cell and email are only searched where the viewer may see them</b> ({@link Reach}). A row that appears
 * or vanishes as digits are added to a query answers "is this in their number?", so a value the page hides from
 * this viewer would leak one digit at a time through the search (review finding, 2026-11-12). Names are never
 * private, so they are always searched.</p>
 *
 * <p>The directory search answers from a {@link SearchIndex}, which can only look up by prefix. Indexing every
 * SUFFIX of a field turns a prefix lookup into a contains lookup, so {@link #indexTokens} emits all suffixes of
 * the name fields and of the cell's digits, but only the whole email. The index lookup and this matcher must
 * agree, which is why both live here.</p>
 */
public final class PersonMatch {
    /** Punctuation a phone-like word may carry besides digits (spaces split words, so none here). */
    private static final String PHONE_PUNCTUATION = "()+.-";

    /** The fields a viewer's privacy may withhold from a search. Names are never withheld. */
    public enum Field {
        CELL, EMAIL
    }

    /** Whether a search may look at one subject's withheld-able field: true only when the viewer may see it. */
    @FunctionalInterface
    public interface Reach {
        boolean sees(Person subject, Field field);
    }

    /** For a viewer who sees every field, at least masked: site admins, and org admins over their own members. */
    public static final Reach EVERY_FIELD = (subject, field) -> true;

    /**
     * Only what the subject shares with every signed-in user: the safe default for any search whose viewer is
     * not known to hold an admin view of the people it searches (the mention typeahead, content pickers).
     */
    public static final Reach SHARED_FIELDS = PersonMatch::sharedWithEveryone;

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
    public static boolean matchesAll(final Person person, final List<String> words, final Reach reach) {
        return person != null && !words.isEmpty() && words.stream().allMatch(word -> matches(person, word, reach));
    }

    /** True when {@code query} has words and every one matches {@code person}. */
    public static boolean matchesQuery(final Person person, final String query, final Reach reach) {
        return matchesAll(person, queryWords(query), reach);
    }

    /** One normalized word against one person; see the class comment for the rule. */
    static boolean matches(final Person person, final String word, final Reach reach) {
        if (nameFields(person).anyMatch(field -> field.contains(word))) {
            return true;
        }
        if (isPhoneLike(word) && reach.sees(person, Field.CELL)
                && digits(person.getCell()).contains(digits(word))) {
            return true;
        }
        return reach.sees(person, Field.EMAIL) && SearchIndex.normalizeToken(person.getEmail()).startsWith(word);
    }

    /**
     * The word the directory search looks up in the index: the longest word (the most selective prefix), as
     * digits only when it is phone-like, because the index holds the cell as digit suffixes.
     */
    static String lookupWord(final List<String> words) {
        final String longest = words.stream().reduce((a, b) -> b.length() > a.length() ? b : a).orElse("");
        return isPhoneLike(longest) ? digits(longest) : longest;
    }

    /** The search-index tokens for one person: suffixes of the names and of the cell's digits, and the email. */
    static Set<String> indexTokens(final Person person) {
        final Set<String> tokens = new HashSet<>();
        if (person == null) {
            return tokens;
        }
        nameFields(person).forEach(field -> addSuffixes(tokens, field));
        addSuffixes(tokens, digits(person.getCell()));
        final String email = SearchIndex.normalizeToken(person.getEmail());
        if (!email.isEmpty()) {
            tokens.add(email);
        }
        return tokens;
    }

    private static boolean sharedWithEveryone(final Person subject, final Field field) {
        final PrivacySettings privacy = subject.getPrivacy();
        return (field == Field.CELL) ? privacy.isCellVisible() : privacy.isEmailVisible();
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
