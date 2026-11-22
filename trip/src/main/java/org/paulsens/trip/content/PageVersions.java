package org.paulsens.trip.content;

import java.util.Optional;
import java.util.function.Predicate;
import org.paulsens.trip.i18n.LanguageMark;
import org.paulsens.trip.model.Language;

/**
 * The language versions of a content page ({@code docs/i18n.md}, "Language versions of a page").
 *
 * <p>A page in another language is a WHOLE, independent page: its own section key, its own rows, its own
 * history. Its key is the page's key plus {@code @} and the language tag ({@code page:org:{uuid}:about@es}).
 * The page written first keeps the key it always had, so adding versions moves no stored row, and it is the one
 * every reader falls back to. A container's children still hang off the container's instance id, so everything
 * below a page's top-level rows needs no notion of language at all.
 *
 * <p>{@code @} cannot occur in a page name or an id, so a key has at most one mark and {@link #baseOf} is exact.
 * Every parser of a page key ({@code OrgPageBootstrap.orgOf}, {@code tripOf}) strips the mark first: a version
 * belongs to the same organization or trip as its page, and is gated by exactly the same rights.
 */
public final class PageVersions {

    private PageVersions() {
    }

    /** The key of {@code baseKey}'s version in {@code language}. */
    public static String keyFor(final String baseKey, final Language language) {
        return LanguageMark.keyFor(baseOf(baseKey), language);
    }

    /** The page a key belongs to: the key itself, without a version mark. Null in, null out. */
    public static String baseOf(final String key) {
        return LanguageMark.baseOf(key);
    }

    /** The language a version key names; empty for a page's own key and for anything that is not a key. */
    public static Optional<Language> languageOf(final String key) {
        return LanguageMark.languageOf(key);
    }

    /**
     * The key a reader who wants {@code wanted} is shown.
     *
     * <ul>
     *   <li>The page's own language reads the page's own key.</li>
     *   <li>Another language reads its version when one has been written.</li>
     *   <li>Otherwise the WHOLE page falls back to the page's own key: a page is never half one language and
     *       half another.</li>
     *   <li>An author who asked for a version by name ({@code explicit}) gets its key even while it is empty:
     *       that is how a version is started.</li>
     * </ul>
     *
     * @param ownLanguage the language the page itself (the unmarked key) is written in
     * @param hasRows whether a section key holds any row
     */
    public static String resolve(final String baseKey, final Language ownLanguage, final Language wanted,
            final boolean explicit, final Predicate<String> hasRows) {
        if (baseKey == null || wanted == null || wanted == ownLanguage) {
            return baseKey;
        }
        final String version = keyFor(baseKey, wanted);
        return explicit || hasRows.test(version) ? version : baseKey;
    }
}
