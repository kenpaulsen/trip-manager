package org.paulsens.trip.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.model.Language;

/**
 * The product's own words, by key and language ({@code docs/i18n.md}). Every user-visible string the code or a
 * page emits comes through here, so there is one fallback rule and one place a missing key shows.
 *
 * <p>A key's first segment names its bundle: {@code common.save} lives in {@code i18n/common.properties}
 * (English, the source) and {@code i18n/common_es.properties}. Bundles are plain classpath resources, which is
 * what lets this repo hold the code's words and the pages repo hold the pages' ({@code WEB-INF/classes/i18n/})
 * with neither knowing the other's files. They are UTF-8 and read once.
 *
 * <p>Fallback: the language's own value, else the English one, else {@code ???key???} -- visible on the page
 * and logged once, never an exception, because a missing word must not take a page down.
 */
@Slf4j
public final class Text {

    private static final String ROOT = "";
    private static final Map<String, Map<String, String>> BUNDLES = new ConcurrentHashMap<>();
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private Text() {
    }

    /** The message in the language of the request in progress (English off a request). */
    public static String get(final String key, final Object... args) {
        return forLocale(RequestContext.currentLocale(), key, args);
    }

    /**
     * The message in an explicit language: the form for mail, push and anything else whose READER is not the
     * person making the request. Arguments are numbered, {@code {0}}; a plural reads its count the same way.
     */
    public static String forLocale(final Locale locale, final String key, final Object... args) {
        final String pattern = pattern(locale, key);
        if (pattern == null) {
            return missing(key);
        }
        // Formatting an argument-free message would still read its apostrophes as quoting, so plain text with
        // no placeholder is returned exactly as the translator wrote it.
        return args.length == 0 && pattern.indexOf('{') < 0 ? pattern
                : MessageText.format(pattern, locale, blankForNull(args));
    }

    /**
     * A missing value prints as nothing, not as the word "null": pages pass optional values (a name not yet
     * given), and the concatenation these messages replaced printed nothing for them.
     */
    private static Object[] blankForNull(final Object[] args) {
        return Arrays.stream(args).map(arg -> arg == null ? "" : arg).toArray();
    }

    /** Whether the key has a value in any language's fallback chain; the policy tests ask this. */
    public static boolean has(final String key) {
        return pattern(Locale.ENGLISH, key) != null;
    }

    private static String pattern(final Locale locale, final String key) {
        final int dot = key == null ? -1 : key.indexOf('.');
        if (dot <= 0) {
            return null;
        }
        final String bundle = key.substring(0, dot);
        final String tag = tagOf(locale);
        final String own = ROOT.equals(tag) ? null : load(bundle, tag).get(key);
        return own != null ? own : load(bundle, ROOT).get(key);
    }

    /**
     * Every key of one bundle with its RAW pattern in a language, the English value where the language has none,
     * sorted by key. This is what a browser script receives ({@code #{text.scriptMessages}}): the script fills
     * its own {@code {0}} arguments, so it needs the pattern, never a message formatted here.
     */
    public static Map<String, String> patterns(final String bundle, final Locale locale) {
        final Map<String, String> all = new TreeMap<>(load(bundle, ROOT));
        final String tag = tagOf(locale);
        if (!ROOT.equals(tag)) {
            all.putAll(load(bundle, tag));
        }
        return all;
    }

    /** A bundle file's suffix for a language: the root (English) file for English and anything unknown. */
    private static String tagOf(final Locale locale) {
        return Language.forTag(locale == null ? null : locale.getLanguage())
                .filter(language -> language != Language.English)
                .map(Language::getTag)
                .orElse(ROOT);
    }

    private static Map<String, String> load(final String bundle, final String tag) {
        return BUNDLES.computeIfAbsent(bundle + "_" + tag, name -> read(bundle, tag));
    }

    private static Map<String, String> read(final String bundle, final String tag) {
        final String resource = "i18n/" + bundle + (ROOT.equals(tag) ? "" : "_" + tag) + ".properties";
        final Map<String, String> values = new HashMap<>();
        try (InputStream in = loader().getResourceAsStream(resource)) {
            if (in != null) {
                copy(in, values);
            }
        } catch (final IOException ex) {
            log.error("Unable to read the message bundle " + resource, ex);
        }
        return Map.copyOf(values);
    }

    private static void copy(final InputStream in, final Map<String, String> values) throws IOException {
        final Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        for (final String name : properties.stringPropertyNames()) {
            values.put(name, properties.getProperty(name));
        }
    }

    /** The webapp's loader when there is one: the pages repo's bundles sit in WEB-INF/classes, beside ours. */
    private static ClassLoader loader() {
        final ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : Text.class.getClassLoader();
    }

    private static String missing(final String key) {
        if (REPORTED.add(String.valueOf(key))) {
            log.error("No message for key '{}' in any language", key);
        }
        return "???" + key + "???";
    }
}
