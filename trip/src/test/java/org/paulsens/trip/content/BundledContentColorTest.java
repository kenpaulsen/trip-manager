package org.paulsens.trip.content;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.TemplateKind;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The content this build SEEDS into an organization's site carries no literal color (issue #26). The starter
 * Site Pages, the product site's pages and the starter templates become database rows that render inside the
 * themed chrome, where a fixed color ignores the organization's palette and dark mode; once seeded, no file
 * check sees them again. So they are held clean here, at their source, the way the medjugorje
 * {@code ColorLiteralPolicyIT} holds the pages. A seed that needs a color writes a theme variable
 * ({@code var(--primary-color)}, {@code var(--status-danger)}; the list is {@code ThemeColors}).
 *
 * <p>MAIL-kind starter templates are the one exception, by kind rather than by count: an email body renders in
 * a mail client, which has no theme and no CSS variables, so its fixed grays are the right call there.
 */
public class BundledContentColorTest {

    private static final Path CONTENT = Path.of("src", "main", "resources", "content");
    private static final Path PAGE_SEEDS =
            Path.of("src", "main", "java", "org", "paulsens", "trip", "content", "OrgPageBootstrap.java");

    /**
     * A hex color (3, 6 or 8 digits, not part of a longer word: {@code #{el}} and {@code &#160;} are not colors)
     * or a functional color notation.
     */
    static final Pattern COLOR = Pattern.compile(
            "(?<![\\w&])#(?:[0-9A-Fa-f]{8}|[0-9A-Fa-f]{6}|[0-9A-Fa-f]{3})(?![\\w-])|\\b(?:rgba?|hsla?)\\(");

    @Test
    public void noSeedHoldsALiteralColor() throws IOException {
        final List<String> offenders = new ArrayList<>();
        for (final Path file : seeds()) {
            offenders.addAll(colorsIn(file.toString(), Files.readString(file, StandardCharsets.UTF_8)));
        }
        for (final ContentTemplate template : StarterTemplates.all()) {
            if (template.getKind() != TemplateKind.MAIL) {
                offenders.addAll(colorsIn("starter template " + template.getId(), template.getBody()));
            }
        }
        Assert.assertTrue(offenders.isEmpty(), "Seeded content renders inside the themed chrome: write a theme "
                + "variable (var(--text-color), var(--primary-color), var(--status-danger)...), not a color: "
                + offenders);
    }

    @Test
    public void thePatternTellsColorsFromLookalikes() {
        Assert.assertTrue(COLOR.matcher("color:#D03F68;").find());
        Assert.assertTrue(COLOR.matcher("background:#fff").find());
        Assert.assertTrue(COLOR.matcher("background:#FF884422").find());
        Assert.assertTrue(COLOR.matcher("color: rgb(1,2,3)").find());
        Assert.assertTrue(COLOR.matcher("hsla(0, 0%, 0%, .5)").find());
        Assert.assertFalse(COLOR.matcher("#{trip.title}").find(), "EL is not a color");
        Assert.assertFalse(COLOR.matcher("a&#160;b").find(), "an entity is not a color");
        Assert.assertFalse(COLOR.matcher("href=\"#schedule\"").find(), "an anchor is not a color");
        Assert.assertFalse(COLOR.matcher("var(--primary-color)").find());
    }

    /** The starter templates hold mail bodies too, so the test is not vacuous only if some are not mail. */
    @Test
    public void someStarterTemplatesAreSiteContent() {
        Assert.assertTrue(StarterTemplates.all().stream().anyMatch(t -> t.getKind() != TemplateKind.MAIL));
    }

    private static List<String> colorsIn(final String where, final String text) {
        final List<String> found = new ArrayList<>();
        final Matcher match = COLOR.matcher(text == null ? "" : text);
        while (match.find()) {
            found.add(where + ": " + match.group());
        }
        return found;
    }

    private static List<Path> seeds() throws IOException {
        final List<Path> files = new ArrayList<>(List.of(PAGE_SEEDS));
        try (Stream<Path> json = Files.list(CONTENT)) {
            files.addAll(json.filter(file -> file.toString().endsWith(".json")).sorted().toList());
        }
        Assert.assertTrue(files.size() >= 3, "the scan found only " + files + "; is the working directory right?");
        return files;
    }
}
