package org.paulsens.trip;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Prose in {@code docs/} stays within 120 columns, the width the Java sources keep (checkstyle). Reviews raised an
 * over-long doc line again and again (trip-pictures.md three times), each time fixed by hand and brought back by
 * the next edit; a reviewer's eye was the only check. Table rows and code fences are not prose and are not
 * scanned: a table row cannot wrap, and a command in a fence is copied as written.
 */
public class DocsLineLengthTest {

    static final int WIDTH = 120;
    /** The repo's docs, from the module directory surefire runs in. */
    private static final Path DOCS = Path.of("..", "docs");

    @Test
    public void everyProseLineFitsTheWidth() throws IOException {
        final List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(DOCS)) {
            for (final Path doc : files.filter(path -> path.toString().endsWith(".md")).sorted().toList()) {
                final List<Integer> lines = overLong(Files.readAllLines(doc, StandardCharsets.UTF_8));
                lines.forEach(line -> offenders.add(DOCS.relativize(doc) + ":" + line));
            }
        }
        // assertTrue, not assertEquals: TestNG's size-mismatch message never prints the offenders.
        Assert.assertTrue(offenders.isEmpty(), "Doc prose over " + WIDTH + " columns (re-flow the paragraph): "
                + offenders);
    }

    @Test
    public void theScanSkipsTablesAndFencesButNotListsOrParagraphs() {
        final String wide = "x".repeat(WIDTH + 1);
        Assert.assertEquals(overLong(List.of(
                "| " + wide + " |",
                "```",
                wide,
                "```",
                "- " + wide,
                "  " + wide,
                "x".repeat(WIDTH))), List.of(5, 6));
    }

    /** The 1-based numbers of the prose lines over {@link #WIDTH}. */
    static List<Integer> overLong(final List<String> lines) {
        final List<Integer> found = new ArrayList<>();
        boolean fence = false;
        for (int i = 0; i < lines.size(); i++) {
            final String trimmed = lines.get(i).trim();
            if (trimmed.startsWith("```")) {
                fence = !fence;
            } else if (!fence && !trimmed.startsWith("|") && lines.get(i).length() > WIDTH) {
                found.add(i + 1);
            }
        }
        return found;
    }
}
