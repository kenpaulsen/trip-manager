package org.paulsens.trip;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The virtual-threads migration's end-state invariants, as greps that fail the build instead of a code-review
 * checklist that goes stale.
 *
 * <p>The architecture is blocking-first: every DAO, cache and client call blocks its (virtual) caller, and the
 * ONLY {@code CompletableFuture} in the main tree is Lettuce's, consumed inside {@code ValkeyCacheClient}'s
 * single await point. A new {@code join()} or CF import anywhere else is how the old world -- nested joins, an
 * unbounded rescue pool, actor loss across completion threads -- starts growing back.
 */
public class ArchitectureTest {

    private static final Path MAIN = Path.of("src", "main", "java");

    /** The one file allowed to touch CompletableFuture: the Lettuce await utility. */
    private static final String AWAIT_POINT = "cache/ValkeyCacheClient.java";

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> walk = Files.walk(MAIN)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    public void theOnlyCompletableFutureIsTheLettuceAwaitPoint() throws IOException {
        final List<String> offenders = new ArrayList<>();
        for (final Path source : mainSources()) {
            if (source.toString().replace('\\', '/').endsWith(AWAIT_POINT)) {
                continue;
            }
            offenders.addAll(linesMatching(source, "import java.util.concurrent.CompletableFuture"));
        }
        Assert.assertEquals(offenders, List.of(),
                "CompletableFuture belongs ONLY in ValkeyCacheClient's await point; block on the caller instead");
    }

    /** {@code scope.join()} (StructuredTaskScope) and {@code thread.join(} are fine; future joins are not. */
    @Test
    public void noFutureIsEverJoined() throws IOException {
        final List<String> offenders = new ArrayList<>();
        for (final Path source : mainSources()) {
            for (final String hit : linesMatching(source, ".join()")) {
                if (!hit.contains("scope.join()")) {
                    offenders.add(hit);
                }
            }
        }
        Assert.assertEquals(offenders, List.of(),
                "join() is uninterruptible and belongs to the CF era; interruption must propagate");
    }

    @Test
    public void theRescuePoolStaysDead() throws IOException {
        final List<String> offenders = new ArrayList<>();
        for (final Path source : mainSources()) {
            offenders.addAll(linesMatching(source, "PersistenceExecutors"));
            offenders.addAll(linesMatching(source, "newCachedThreadPool"));
        }
        Assert.assertEquals(offenders, List.of(),
                "the unbounded platform pool (and its class of OOM) must not come back; spawn via TripThreads");
    }

    /**
     * Stripe keys travel per request ({@code RequestOptions.setApiKey}): one organization's key set on the
     * SDK's process-global {@code Stripe.apiKey} would bill or refund another organization's payers.
     */
    @Test
    public void stripeKeysAreNeverProcessGlobal() throws IOException {
        final List<String> offenders = new ArrayList<>();
        for (final Path source : mainSources()) {
            offenders.addAll(linesMatching(source, "Stripe.apiKey"));
            offenders.addAll(linesMatching(source, "Stripe.overrideApiBase"));
        }
        Assert.assertEquals(offenders, List.of(),
                "every Stripe call passes its organization's key in RequestOptions; never the global one");
    }

    /**
     * The EL evaluator ({@code com.sun.jsft.util.ELUtil}) runs whatever it is given with the full EL context:
     * static calls on {@code java.lang} included. Stored private notes (medjugorje#116) and the mail merge's text
     * (medjugorje#79) reached it, which was code execution for any trip member and any org {@code emailAdmin}.
     * Only these may use it, each on text that ships with the application, never text a person typed:
     * classpath mail templates with escaped scalar values, and one constant expression. The list is FILES, so it
     * stays this short: a whole bean on it would let a new evaluating method in that bean pass unseen (two
     * unused ones, {@code tripUtil.mapList} and {@code people.toSortedList}, were deleted for that reason). A new
     * user of the evaluator needs the same proof, and an entry here.
     */
    private static final List<String> EL_EVALUATOR_USERS =
            List.of("chat/MailTemplates.java", "action/TodoCommands.java");

    @Test
    public void storedOrTypedTextNeverReachesTheElEvaluator() throws IOException {
        final List<String> offenders = new ArrayList<>();
        for (final Path source : mainSources()) {
            final String path = source.toString().replace('\\', '/');
            if (EL_EVALUATOR_USERS.stream().noneMatch(path::endsWith)) {
                offenders.addAll(linesMatching(source, "ELUtil"));
                offenders.addAll(linesMatching(source, "createValueExpression"));
            }
        }
        Assert.assertEquals(offenders, List.of(),
                "personalize with {{token}} substitution (TokenText) instead of evaluating text as EL");
    }

    /**
     * {@code DAO.scanTable} is a strongly consistent scan of a WHOLE table, there for the one-time ledger
     * migration alone. Local mode answers it small, so a page or report that called it would pass every suite
     * and then scan the ledger on every production render; only its declaration and {@code migrate/} may name
     * it.
     */
    @Test
    public void theWholeTableScanStaysInTheMigration() throws IOException {
        final List<String> offenders = new ArrayList<>();
        for (final Path source : mainSources()) {
            final String path = source.toString().replace('\\', '/');
            if (!path.contains("/migrate/") && !path.endsWith("dynamo/DAO.java")) {
                offenders.addAll(linesMatching(source, "scanTable("));
            }
        }
        Assert.assertEquals(offenders, List.of(), "DAO.scanTable is for migrate/ only: never on a request path");
        Assert.assertEquals(linesMatching(MAIN.resolve("org/paulsens/trip/dynamo/DAO.java"), "scanTable(").size(), 1,
                "its declaration, and no caller inside the DAO facade either");
    }

    /**
     * "No trip" has ONE definition ({@code TransactionsCommands.isNoTrip}). A second, private one on the trip
     * pickers once disagreed with it about {@code "None"} and {@code " none"}, so the transaction editor's Trip
     * menu and its save rule answered differently for the same posted value.
     */
    @Test
    public void noTripHasOneDefinition() throws IOException {
        final List<String> definitions = new ArrayList<>();
        for (final Path source : mainSources()) {
            definitions.addAll(linesMatching(source, "boolean isNoTrip("));
            definitions.addAll(linesMatching(source, "String NO_TRIP ="));
        }
        Assert.assertEquals(definitions.size(), 2, "one isNoTrip and one NO_TRIP, both on TransactionsCommands: "
                + definitions);
        Assert.assertTrue(definitions.stream().allMatch(line -> line.contains("TransactionsCommands.java")),
                definitions.toString());
    }

    /** The one file that may spell "trim, then null when blank": the rule's own home. */
    private static final String TRIM_TO_NULL_HOME = "util/Util.java";

    /** A receiver with nothing done to it, up to its dot: {@code x.}, {@code node.asText().}, {@code a.b().}. */
    private static final String RECEIVER = "(?:\\w+(?:\\(\\))?\\.)+";

    /** A null-safe blank test called as a HELPER on one argument: {@code isBlank(x)}, {@code Util.isEmpty(x)}. */
    private static final String BLANK_HELPER = "is(?:Blank|Empty)\\(([\\w.]+(?:\\(\\))?)\\)";

    /**
     * The spellings {@code Util.trimToNull} replaces, over code with its whitespace collapsed (so a wrapped
     * ternary is still one match). Each answers the receiver's own {@code trim()} and nothing more: a parse of
     * the trimmed text ({@code Person.Id.from(x.trim())}), a further call on it ({@code x.trim().toLowerCase()})
     * and a non-null fallback are other rules and stay legal.
     */
    private static final List<Pattern> INLINE_TRIM_TO_NULL = List.of(
            // (x == null || x.isBlank()) ? null : x.trim()
            Pattern.compile("is(?:Blank|Empty)\\(\\)\\)? \\? null : " + RECEIVER + "trim\\(\\)(?! ?\\.)"),
            // !x.isBlank() ? x.trim() : null
            Pattern.compile("! ?" + RECEIVER + "is(?:Blank|Empty)\\(\\)\\)? \\? " + RECEIVER
                    + "trim\\(\\) : null"),
            // if (x == null || x.isBlank()) { return null; } return x.trim();
            Pattern.compile("is(?:Blank|Empty)\\(\\)\\) \\{ return null; \\} return " + RECEIVER
                    + "trim\\(\\);"),
            // t = x.trim(); return t.isEmpty() ? null : t;
            Pattern.compile("(\\w+) = [^;]*\\.trim\\(\\); return \\1\\.is(?:Blank|Empty)\\(\\) \\? null : \\1;"),
            // t = x.trim(); if (t.isEmpty()) { return null; } return t;
            Pattern.compile("(\\w+) = [^;]*\\.trim\\(\\); if \\(\\1\\.is(?:Blank|Empty)\\(\\)\\) \\{ return null; \\} "
                    + "return \\1;"),
            // The same three through a private or shared helper, which the receiver forms above cannot see
            // (FamilyCommands kept one through four reviews): isBlank(x) ? null : x.trim()
            Pattern.compile(BLANK_HELPER + " \\? null : \\1\\.trim\\(\\)(?! ?\\.)"),
            // !isBlank(x) ? x.trim() : null
            Pattern.compile("! ?" + BLANK_HELPER + "\\)? \\? \\1\\.trim\\(\\) : null"),
            // if (isBlank(x)) { return null; } return x.trim();
            Pattern.compile(BLANK_HELPER + "\\) \\{ return null; \\} return \\1\\.trim\\(\\);"));

    /**
     * "Trimmed, or null when nothing is left" has ONE definition ({@code Util.trimToNull}). Inline copies test
     * blank BEFORE trimming, which answers {@code ""} for text of only control characters, and three reviews in
     * a row found more of them: a sweep by helper name misses a rule spelled as an expression. One such copy
     * normalized {@code MediaItem}'s org id while the library lookup it is compared with had moved to the helper.
     */
    @Test
    public void trimToNullHasOneDefinition() throws IOException {
        final List<String> offenders = new ArrayList<>();
        for (final Path source : mainSources()) {
            if (!source.toString().replace('\\', '/').endsWith(TRIM_TO_NULL_HOME)) {
                offenders.addAll(inlineTrimToNulls(source));
            }
        }
        // The offenders are in the message: TestNG reports two lists of different size by their sizes alone.
        Assert.assertEquals(offenders, List.of(),
                "call Util.trimToNull instead of spelling its rule inline: " + offenders);
    }

    /**
     * The ratchet's own patterns, on samples: the helper forms have no site left in the sources to prove them
     * on, and a pattern that matches nothing passes {@link #trimToNullHasOneDefinition} forever.
     */
    @Test
    public void theTrimToNullPatternsSeeTheHelperFormsAndLeaveOtherRulesAlone() {
        for (final String copy : List.of(
                "final String a = isBlank(email) ? null : email.trim();",
                "return Util.isEmpty(node.asText()) ? null : node.asText().trim();",
                "final String a = !isBlank(email) ? email.trim() : null;",
                "return (!isBlank(email)) ? email.trim() : null;",
                "if (isBlank(email)) { return null; } return email.trim();")) {
            Assert.assertEquals(trimToNullSpellings(copy).size(), 1, copy);
        }
        for (final String legal : List.of(
                "final String a = isBlank(email) ? null : email.trim().toLowerCase();",
                "return isBlank(id) ? null : Person.Id.from(id.trim());",
                "final Person.Sex parsed = isBlank(sex) ? null : Person.Sex.valueOf(sex);",
                "final String a = isBlank(email) ? null : other.trim();",
                "final String a = !isBlank(email) ? email.trim() : fallback;",
                "if (isBlank(email)) { return null; } return email.trim().toLowerCase();")) {
            Assert.assertEquals(trimToNullSpellings(legal), List.of(), legal);
        }
    }

    private static List<String> inlineTrimToNulls(final Path source) throws IOException {
        return trimToNullSpellings(codeOf(source)).stream().map(hit -> source + " " + hit).toList();
    }

    private static List<String> trimToNullSpellings(final String code) {
        final List<String> hits = new ArrayList<>();
        for (final Pattern spelling : INLINE_TRIM_TO_NULL) {
            final Matcher matcher = spelling.matcher(code);
            while (matcher.find()) {
                hits.add(matcher.group());
            }
        }
        return hits;
    }

    /** A file's code as ONE line, comment lines dropped and whitespace runs collapsed. */
    private static String codeOf(final Path source) throws IOException {
        final StringBuilder code = new StringBuilder();
        for (final String raw : Files.readAllLines(source)) {
            final String line = raw.strip();
            if (!line.startsWith("*") && !line.startsWith("//") && !line.startsWith("/*")) {
                code.append(line.replaceAll("\\s+", " ")).append(' ');
            }
        }
        return code.toString();
    }

    /** Matches CODE lines only: comments may (and do) name the banned things while explaining their absence. */
    private static List<String> linesMatching(final Path source, final String needle) throws IOException {
        final List<String> hits = new ArrayList<>();
        final List<String> lines = Files.readAllLines(source);
        for (int i = 0; i < lines.size(); i++) {
            final String line = lines.get(i).strip();
            if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) {
                continue;
            }
            if (line.contains(needle)) {
                hits.add(source + ":" + (i + 1) + " " + line);
            }
        }
        return hits;
    }
}
