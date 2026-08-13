package org.paulsens.trip.content;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.paulsens.trip.model.Placeholder;

/**
 * Carries an instance's filled-in values from one template version's placeholders to another's.
 *
 * <p>A content instance pins the template version it was authored against, so a template edit never
 * reshapes published content behind the author's back; moving to a newer version is therefore an explicit
 * act, and this decides what survives it. Placeholder names that stayed the same need no work at all, which
 * is the overwhelmingly common case (a template revision usually only touches the body). The rest is
 * best-effort by design -- a renamed or retyped hole cannot be matched with certainty, so this tries the
 * evidence that is actually reliable, in order, and DROPS whatever is left rather than guessing:
 *
 * <ol>
 *   <li>same name -- the value is simply kept;</li>
 *   <li>same label, case-insensitively -- a rename that kept its prompt ("Caption" stays "Caption");</li>
 *   <li>a lone survivor pairing -- when exactly one unmatched old and one unmatched new share a type,
 *       they can only be each other.</li>
 * </ol>
 *
 * <p>Whatever the caller does with {@link Result#dropped()}, it must SAY so: silently losing an author's
 * text is worse than telling them which field it came from.
 */
public final class TemplateValueMigrator {

    private TemplateValueMigrator() {
    }

    /**
     * @param values the instance's current values, keyed by the OLD template's placeholder names.
     * @return the values re-keyed for {@code to}, plus what was renamed and what could not be placed.
     */
    public static Result migrate(final List<Placeholder> from, final List<Placeholder> to,
            final Map<String, String> values) {
        final Map<String, String> source = values == null ? Map.of() : values;
        final List<Placeholder> oldOnes = from == null ? List.of() : from;
        final List<Placeholder> newOnes = to == null ? List.of() : to;
        final Map<String, String> migrated = new HashMap<>();
        final Map<String, String> renamed = new LinkedHashMap<>();
        // Every hole in the target starts declared-but-empty, so the dialog renders a field for each.
        newOnes.forEach(ph -> migrated.put(ph.getName(), ""));

        final List<Placeholder> unmatchedOld = new ArrayList<>();
        final List<Placeholder> unmatchedNew = new ArrayList<>(newOnes);
        for (final Placeholder ph : oldOnes) {
            final Placeholder exact = findByName(unmatchedNew, ph.getName());
            if (exact == null) {
                unmatchedOld.add(ph);
            } else {
                migrated.put(exact.getName(), valueOf(source, ph));
                unmatchedNew.remove(exact);
            }
        }
        matchByLabel(unmatchedOld, unmatchedNew, source, migrated, renamed);
        matchLoneTypes(unmatchedOld, unmatchedNew, source, migrated, renamed);
        return new Result(migrated, renamed, droppedNames(unmatchedOld, source));
    }

    private static void matchByLabel(final List<Placeholder> unmatchedOld,
            final List<Placeholder> unmatchedNew, final Map<String, String> source,
            final Map<String, String> migrated, final Map<String, String> renamed) {
        final List<Placeholder> matched = new ArrayList<>();
        for (final Placeholder ph : unmatchedOld) {
            final Placeholder byLabel = findByLabel(unmatchedNew, ph.getLabel());
            if (byLabel != null) {
                migrated.put(byLabel.getName(), valueOf(source, ph));
                renamed.put(ph.getName(), byLabel.getName());
                unmatchedNew.remove(byLabel);
                matched.add(ph);
            }
        }
        unmatchedOld.removeAll(matched);
    }

    /** The last resort: one old and one new of the same type, with nothing else it could be. */
    private static void matchLoneTypes(final List<Placeholder> unmatchedOld,
            final List<Placeholder> unmatchedNew, final Map<String, String> source,
            final Map<String, String> migrated, final Map<String, String> renamed) {
        final List<Placeholder> matched = new ArrayList<>();
        for (final Placeholder ph : unmatchedOld) {
            if (countOfType(unmatchedOld, ph) != 1 || countOfType(unmatchedNew, ph) != 1) {
                continue;
            }
            final Placeholder byType = findByType(unmatchedNew, ph);
            migrated.put(byType.getName(), valueOf(source, ph));
            renamed.put(ph.getName(), byType.getName());
            unmatchedNew.remove(byType);
            matched.add(ph);
        }
        unmatchedOld.removeAll(matched);
    }

    /** Only a placeholder that HELD something counts as dropped -- an empty one loses nothing. */
    private static List<String> droppedNames(final List<Placeholder> unmatchedOld,
            final Map<String, String> source) {
        final List<String> dropped = new ArrayList<>();
        for (final Placeholder ph : unmatchedOld) {
            if (!valueOf(source, ph).isBlank()) {
                dropped.add(ph.getName());
            }
        }
        return dropped;
    }

    private static String valueOf(final Map<String, String> values, final Placeholder ph) {
        final String value = values.get(ph.getName());
        return value == null ? "" : value;
    }

    private static Placeholder findByName(final List<Placeholder> candidates, final String name) {
        return candidates.stream().filter(ph -> ph.getName().equals(name)).findFirst().orElse(null);
    }

    private static Placeholder findByLabel(final List<Placeholder> candidates, final String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        return candidates.stream().filter(ph -> sameLabel(ph, label)).findFirst().orElse(null);
    }

    private static boolean sameLabel(final Placeholder candidate, final String label) {
        return candidate.getLabel() != null
                && candidate.getLabel().trim().toLowerCase(Locale.ROOT).equals(label.trim().toLowerCase(Locale.ROOT));
    }

    private static Placeholder findByType(final List<Placeholder> candidates, final Placeholder like) {
        return candidates.stream().filter(ph -> ph.getType() == like.getType()).findFirst().orElseThrow();
    }

    private static long countOfType(final List<Placeholder> candidates, final Placeholder like) {
        return candidates.stream().filter(ph -> ph.getType() == like.getType()).count();
    }

    /**
     * @param values  the instance's new value map.
     * @param renamed old placeholder name to the new one it was matched onto.
     * @param dropped old placeholder names whose non-empty values had nowhere to go.
     */
    public record Result(Map<String, String> values, Map<String, String> renamed, List<String> dropped) {
    }
}
