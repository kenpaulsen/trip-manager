package org.paulsens.trip.i18n;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The code's own words ({@code docs/i18n.md}): every key the code asks {@link Text} for exists, every key exists
 * in every language with the same arguments, and a Croatian count has its three forms.
 *
 * <p>Nothing else would notice. A missing key renders {@code ???key???} instead of failing, and a missing
 * translation silently reads English, so a suite that runs in English passes either way. (The pages repo runs
 * the same checks over both repos' bundles, and adds the ones about pages.)
 */
public class BundlePolicyTest {

    private static final Path BUNDLES = Path.of("src", "main", "resources", "i18n");
    private static final Path MAIN = Path.of("src", "main", "java");
    private static final List<String> TRANSLATIONS = List.of("es", "hr");
    /** A literal key handed straight to Text: {@code Text.get("a.b"} or {@code Text.forLocale(locale, "a.b"}. */
    private static final Pattern ASKED = Pattern.compile(
            "Text\\s*\\.\\s*(?:get\\(\\s*|forLocale\\(\\s*[^,()]+(?:\\([^()]*\\))?\\s*,\\s*)\"([^\"]+)\"");
    private static final Pattern ARGUMENT = Pattern.compile("\\{\\s*([A-Za-z0-9_]+)");
    private static final Pattern PLURAL = Pattern.compile("\\{\\s*[A-Za-z0-9_]+\\s*,\\s*plural\\s*,");
    private static final Pattern KEY_SHAPE = Pattern.compile("[a-z][A-Za-z0-9]*(\\.[A-Za-z0-9]+)+");

    @Test
    public void everyKeyTheCodeAsksForExists() throws IOException {
        final Set<String> known = all("").keySet();
        final Map<String, Set<String>> missing = new TreeMap<>();
        try (Stream<Path> sources = Files.walk(MAIN)) {
            for (final Path source : sources.filter(file -> file.toString().endsWith(".java")).toList()) {
                final Matcher asked = ASKED.matcher(Files.readString(source, StandardCharsets.UTF_8));
                while (asked.find()) {
                    if (!known.contains(asked.group(1))) {
                        missing.computeIfAbsent(MAIN.relativize(source).toString(), name -> new TreeSet<>())
                                .add(asked.group(1));
                    }
                }
            }
        }
        Assert.assertTrue(missing.isEmpty(), "the code asks for keys no English bundle has: " + missing);
    }

    @Test
    public void everyKeyIsInItsOwnBundleAndEveryLanguageHasItWithTheSameArguments() throws IOException {
        final Map<String, String> english = all("");
        final List<String> problems = new ArrayList<>();
        english.keySet().stream().filter(key -> !KEY_SHAPE.matcher(key).matches())
                .forEach(key -> problems.add(key + " is not a dotted lowerCamel key"));
        for (final String tag : TRANSLATIONS) {
            final Map<String, String> translated = all(tag);
            for (final Map.Entry<String, String> source : english.entrySet()) {
                final String value = translated.get(source.getKey());
                if (value == null || value.isBlank()) {
                    problems.add(tag + " lacks " + source.getKey());
                } else if (!argumentsOf(source.getValue()).equals(argumentsOf(value))) {
                    problems.add(tag + " " + source.getKey() + " takes " + argumentsOf(value)
                            + ", English takes " + argumentsOf(source.getValue()));
                }
            }
            translated.keySet().stream().filter(key -> !english.containsKey(key))
                    .forEach(key -> problems.add(tag + " has " + key + ", which English does not"));
        }
        Assert.assertTrue(problems.isEmpty(), problems.size() + " bundle problems: " + problems);
    }

    @Test
    public void aCroatianCountHasItsThreeForms() throws IOException {
        final List<String> problems = all("hr").entrySet().stream()
                .filter(entry -> PLURAL.matcher(entry.getValue()).find())
                .filter(entry -> !List.of("one {", "few {", "other {").stream().allMatch(entry.getValue()::contains))
                .map(Map.Entry::getKey)
                .toList();
        Assert.assertTrue(problems.isEmpty(), "Croatian counts need one, few and other (1 dan, 2 dana, 5 dana): "
                + problems);
    }

    @Test
    public void everyMessageFormatsInEveryLanguage() throws IOException {
        // A pattern ICU cannot parse (an unbalanced brace, a stray quote before one) throws when it is USED,
        // on the one page that shows it. Format each once here instead, with a number for every argument.
        final List<String> problems = new ArrayList<>();
        for (final String tag : List.of("", "es", "hr")) {
            for (final Map.Entry<String, String> entry : all(tag).entrySet()) {
                try {
                    MessageText.format(entry.getValue(), java.util.Locale.forLanguageTag(tag.isEmpty() ? "en" : tag),
                            3, 3, 3, 3, 3, 3, 3, 3);
                } catch (final RuntimeException ex) {
                    problems.add(tag + " " + entry.getKey() + ": " + ex.getMessage());
                }
            }
        }
        Assert.assertTrue(problems.isEmpty(), "messages that do not format: " + problems);
    }

    /** Every key of one language ({@code tag} empty for English); a key outside its file's bundle fails here. */
    private static Map<String, String> all(final String tag) throws IOException {
        final Map<String, String> values = new TreeMap<>();
        if (!Files.isDirectory(BUNDLES)) {
            return values;
        }
        try (Stream<Path> files = Files.list(BUNDLES)) {
            for (final Path file : files.filter(path -> languageOf(path).equals(tag)).sorted().toList()) {
                final String bundle = file.getFileName().toString().replaceAll("(_[a-z]+)?\\.properties$", "");
                final Properties properties = new Properties();
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
                for (final String key : properties.stringPropertyNames()) {
                    Assert.assertTrue(key.startsWith(bundle + "."), file.getFileName() + ": " + key
                            + " does not start with its bundle's name");
                    values.put(key, properties.getProperty(key));
                }
            }
        }
        return values;
    }

    private static String languageOf(final Path file) {
        final String name = file.getFileName().toString();
        if (!name.endsWith(".properties")) {
            return "not-a-bundle";
        }
        final String base = name.substring(0, name.length() - ".properties".length());
        final int underscore = base.lastIndexOf('_');
        return underscore < 0 ? "" : base.substring(underscore + 1);
    }

    /** The names of a message's TOP-LEVEL arguments; a plural's branches are braces too, and differ by language. */
    private static Set<String> argumentsOf(final String pattern) {
        final Set<String> names = new TreeSet<>();
        int depth = 0;
        for (int at = 0; at < pattern.length(); at++) {
            final char c = pattern.charAt(at);
            if (c == '{') {
                final Matcher argument = ARGUMENT.matcher(pattern).region(at, pattern.length());
                if (depth == 0 && argument.lookingAt()) {
                    names.add(argument.group(1));
                }
                depth++;
            } else if (c == '}') {
                depth--;
            }
        }
        return names;
    }
}
