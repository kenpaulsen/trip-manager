package org.paulsens.trip;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * No string literal under {@code src/main/java} carries an em dash: the owner's rule for every user-visible
 * string (mail bodies, growls, seeded demo content). The medjugorje {@code NoEmDashPolicyIT} covers the pages;
 * Java literals had no check, and two sweeps missed some (the local-mode seed's intro, an event and the demo
 * trip's description). Comments are not scanned: they never reach a user, and the house style writes "--" there
 * anyway.
 *
 * <p>The character itself, its {@code \}{@code u2014} escape and its HTML entities ({@code &mdash;},
 * {@code &#8212;}, {@code &#x2014;}) all count, since a literal holding any of them renders as a dash.
 */
public class NoEmDashLiteralTest {

    private static final Path MAIN = Path.of("src", "main", "java");

    private static final List<String> DASHES = List.of(Character.toString(0x2014), "\\u2014", "&mdash;", "&#8212;",
            "&#x2014;", "&#X2014;");

    /**
     * Literals allowed to hold a dash, per file, exactly as written in the source. WaiverHtml converts the
     * entity to the character when it turns a waiver's HTML into PDF text: it decodes what an organization
     * wrote, and adds no dash of its own.
     */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "org/paulsens/trip/action/WaiverHtml.java", Set.of("&mdash;", "\\u2014"));

    @Test
    public void noStringLiteralHoldsAnEmDash() throws IOException {
        final List<String> offenders = new ArrayList<>();
        for (final Path source : mainSources()) {
            final String relative = MAIN.relativize(source).toString().replace('\\', '/');
            final Set<String> allowed = ALLOWED.getOrDefault(relative, Set.of());
            for (final String literal : stringLiterals(Files.readString(source, StandardCharsets.UTF_8))) {
                if (hasDash(literal) && !allowed.contains(literal)) {
                    offenders.add(relative + ": \"" + literal + "\"");
                }
            }
        }
        // assertTrue, not assertEquals: TestNG's size-mismatch message never prints the offenders.
        Assert.assertTrue(offenders.isEmpty(), "No em dash in a Java string literal (owner rule): write \"--\", "
                + "or reword with a comma, colon or period: " + offenders);
    }

    /** The allow-list cannot go stale: each entry still names a literal its file really has. */
    @Test
    public void everyAllowedLiteralStillExists() throws IOException {
        for (final Map.Entry<String, Set<String>> entry : ALLOWED.entrySet()) {
            final List<String> literals = stringLiterals(Files.readString(MAIN.resolve(entry.getKey())));
            Assert.assertTrue(literals.containsAll(entry.getValue()),
                    entry.getKey() + " no longer has every allowed literal " + entry.getValue()
                            + "; drop the stale entry");
        }
    }

    @Test
    public void theScannerReadsLiteralsAndSkipsComments() {
        // Concatenated, not a text block: the snippet holds a text block of its own.
        final String source = String.join("\n",
                "// a comment \"with &mdash; a dash\"",
                "/* a block \"comment &mdash; here\" */",
                "String a = \"plain \\\" quote\";",
                "char c = '\"';",
                "String b = \"x &mdash; y\";",
                "String t = \"\"\"",
                "    block \\\"\"\" still inside",
                "    \"\"\";");
        Assert.assertEquals(stringLiterals(source),
                List.of("plain \\\" quote", "x &mdash; y", "\n    block \\\"\"\" still inside\n    "));
        Assert.assertTrue(hasDash("a " + Character.toString(0x2014) + " b"));
        Assert.assertTrue(hasDash("a &#8212; b"));
        Assert.assertFalse(hasDash("a -- b"));
    }

    private static boolean hasDash(final String literal) {
        return DASHES.stream().anyMatch(literal::contains);
    }

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> walk = Files.walk(MAIN)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    /**
     * The raw source text of every string literal and text block, escapes left as written. A small lexer rather
     * than a regex: it must skip comments and character literals, and a quote inside either would derail a
     * pattern.
     */
    static List<String> stringLiterals(final String source) {
        final List<String> literals = new ArrayList<>();
        int i = 0;
        while (i < source.length()) {
            if (source.startsWith("//", i)) {
                i = endOf(source, "\n", i + 2);
            } else if (source.startsWith("/*", i)) {
                i = endOf(source, "*/", i + 2);
            } else if (source.startsWith("\"\"\"", i)) {
                final int end = closing(source, "\"\"\"", i + 3);
                literals.add(source.substring(i + 3, end));
                i = end + 3;
            } else if (source.charAt(i) == '"' || source.charAt(i) == '\'') {
                final String quote = String.valueOf(source.charAt(i));
                final int end = closing(source, quote, i + 1);
                if (quote.equals("\"")) {
                    literals.add(source.substring(i + 1, end));
                }
                i = end + 1;
            } else {
                i++;
            }
        }
        return literals;
    }

    /** Where a comment ends: just past its terminator, or the end of the source. */
    private static int endOf(final String source, final String terminator, final int from) {
        final int at = source.indexOf(terminator, from);
        return at < 0 ? source.length() : at + terminator.length();
    }

    /** The index of the unescaped closing quote, or the end of the source when it never closes. */
    private static int closing(final String source, final String quote, final int from) {
        int i = from;
        while (i < source.length() && !source.startsWith(quote, i)) {
            i += source.charAt(i) == '\\' ? 2 : 1;
        }
        return Math.min(i, source.length());
    }
}
