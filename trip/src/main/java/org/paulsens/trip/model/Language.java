package org.paulsens.trip.model;

import java.util.Locale;
import java.util.Optional;

/**
 * A language the product speaks: the one registry, for the language a trip is conducted in AND for the interface
 * ({@code docs/i18n.md}). Serialized by name on a trip -- never rename a constant without a data migration.
 * Everything about the interface (a person's preference, {@code Accept-Language}, a bundle or page-version
 * suffix) uses {@link #getTag()} instead, so the two spellings never mix. Declaration order is display order
 * (English first, the site default); pages that group trips by language iterate {@code values()} so a new
 * constant added here appears on the site automatically.
 */
public enum Language {
    English("English", "en"),
    Spanish("Español", "es"),
    Croatian("Hrvatski", "hr");

    /** The language's name in that language -- used for grouping labels. */
    private final String displayName;
    /** The BCP 47 language tag, language-only: a regional variant would split one translation in two. */
    private final String tag;
    private final Locale locale;

    Language(final String displayName, final String tag) {
        this.displayName = displayName;
        this.tag = tag;
        this.locale = Locale.forLanguageTag(tag);
    }

    /**
     * The constant a stored {@link #name()} names, or empty for null and for a name this build does not know (a
     * row written by a newer build). The one by-name lookup for code that holds the language as text, so a new
     * constant reaches it with no second list of names to keep.
     */
    public static Optional<Language> named(final String name) {
        for (final Language language : values()) {
            if (language.name().equals(name)) {
                return Optional.of(language);
            }
        }
        return Optional.empty();
    }

    /**
     * The constant for a language tag, or empty for null, blank and a language this build does not speak. Only
     * the tag's LANGUAGE counts and case is ignored, so a browser's {@code es-MX} or {@code HR} finds its
     * constant: the caller is always asking "which of our languages is this?", never for an exact match.
     */
    public static Optional<Language> forTag(final String tag) {
        if (tag == null || tag.isBlank()) {
            return Optional.empty();
        }
        final String wanted = Locale.forLanguageTag(tag.trim().replace('_', '-')).getLanguage();
        for (final Language language : values()) {
            if (language.tag.equals(wanted)) {
                return Optional.of(language);
            }
        }
        return Optional.empty();
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getTag() {
        return tag;
    }

    public Locale getLocale() {
        return locale;
    }
}
