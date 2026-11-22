package org.paulsens.trip.i18n;

import java.util.Optional;
import org.paulsens.trip.model.Language;

/**
 * The one spelling of "this, in that language" in a stored key: the key, {@code @}, an exact language tag
 * ({@code page:org:x:about@es}, {@code site.footer.text@hr}, a mail template {@code welcome@es}). Page versions
 * and translated settings both parse it here, so a change to what a tag may be reaches every kind of key at once.
 */
public final class LanguageMark {

    private static final char MARK = '@';

    private LanguageMark() {
    }

    /** {@code base} marked with {@code language}; {@code base} is taken as given (pass a key without a mark). */
    public static String keyFor(final String base, final Language language) {
        return base + MARK + language.getTag();
    }

    /** The key without its language mark: the key itself when it names no language. Null in, null out. */
    public static String baseOf(final String key) {
        return languageOf(key).isPresent() ? key.substring(0, key.lastIndexOf(MARK)) : key;
    }

    /** The language a key names; empty for an unmarked key and for anything that is not a key. */
    public static Optional<Language> languageOf(final String key) {
        final int mark = key == null ? -1 : key.lastIndexOf(MARK);
        if (mark <= 0) {
            return Optional.empty();
        }
        final String tag = key.substring(mark + 1);
        // Exact tags only: "@ES" or "@es-MX" is not a key anyone writes, so it must not parse as one.
        return Language.forTag(tag).filter(language -> language.getTag().equals(tag));
    }
}
