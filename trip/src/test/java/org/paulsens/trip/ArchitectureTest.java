package org.paulsens.trip;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
