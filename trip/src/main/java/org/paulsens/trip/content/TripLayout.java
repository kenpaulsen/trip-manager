package org.paulsens.trip.content;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.util.Html;

/**
 * The layout body of a trip page's live section (docs/content-templates.md, "Live sections with a layout"): the HTML a
 * template author writes for Trip Details or Trip Detail Pictures, with the trip's own values as tokens. Pure text in,
 * text out: the bean ({@code TripLayoutCommands}) gathers the trip's values, and this fills them in.
 *
 * <ul>
 * <li>{@code {{trip:name}}} is a value, HTML-escaped ({@link Data#text}), or markup this server built and escaped
 *     itself ({@link Data#html}: the cover picture). A name with no value renders nothing.</li>
 * <li>{@code {{#trip:name}} ... {{/trip:name}}} keeps what it holds only when {@code name} is one of
 *     {@link Data#blocks} (a fact the trip has and the section shows, the trip having pictures, ...), so a missing
 *     fact leaves no empty label behind.</li>
 * <li>{@value #GALLERY_SLOT} is not text: it marks where the picture gallery (a PrimeFaces component) goes. The body
 *     is cut there into a {@link Rendered#before} and an {@link Rendered#after}, and the page draws the gallery
 *     between them, the way a group's row is cut at its {@code {{child}}}.</li>
 * <li>{@code {{name}}} (no colon) is one of the section's own properties ({@link Data#properties}: its Tone),
 *     escaped like a hand-written template's value.</li>
 * </ul>
 * The colon form never collides with a hand-written template's {@code {{name}}} tokens (ContentRenderer's token
 * pattern has no colon), as {@code {{container:title}}} does not.
 */
public final class TripLayout {

    /** Where the gallery component goes; the body is cut there. */
    public static final String GALLERY_SLOT = "{{trip:gallery}}";
    /** The slot's own name, which is also a block ({@code {{#trip:gallery}}}): two or more pictures, not cover only. */
    public static final String GALLERY = "gallery";

    private static final Pattern VALUE = Pattern.compile("\\{\\{trip:([A-Za-z]+)}}");
    private static final Pattern BLOCK = Pattern.compile("\\{\\{#trip:([A-Za-z]+)}}(.*?)\\{\\{/trip:\\1}}",
            Pattern.DOTALL);
    private static final Pattern MARK = Pattern.compile("\\{\\{([#/])trip:([A-Za-z]*)}}");
    private static final Pattern PROPERTY = Pattern.compile("\\{\\{([A-Za-z0-9_]+)}}");
    /** The section property naming its background: the bands' Tone, one of {@link StarterTemplates#TONES}. */
    public static final String PROP_TONE = "tone";

    /** Enough rounds for blocks inside blocks of other names; a body needing more is refused at save. */
    private static final int MAX_DEPTH = 8;

    private TripLayout() {
    }

    /**
     * What a body may use, for its check at save and for the template editor's reference: the value tokens, the
     * block names and the section's property names.
     */
    public record Vocabulary(List<String> values, List<String> blocks, List<String> properties) {
    }

    /**
     * One trip's values for one section: {@code text} escaped as it is filled in, {@code html} trusted markup this
     * server built, {@code blocks} the names whose blocks are kept, {@code properties} the section's own values.
     */
    public record Data(Map<String, String> text, Map<String, String> html, Set<String> blocks,
            Map<String, String> properties) {
    }

    /** A filled body, cut at the gallery slot ({@code slot} false: no slot survived, all of it is in before). */
    public record Rendered(String before, String after, boolean slot) {
    }

    /**
     * The section's Tone as the CSS class its wrapper wears ({@code tone-tint}), or "" for none: blank, or anything
     * that is not one of the tones, draws the section with no background of its own, exactly as before tones.
     */
    public static String toneClass(final String tone) {
        return tone != null && StarterTemplates.TONES.contains(tone) ? "tone-" + tone : "";
    }

    /** The Tone menu's options, the bands' own (each value is its own label, as the bands' menu shows it). */
    public static List<ProgrammaticContentTemplate.Choice> toneChoices() {
        return StarterTemplates.TONES.stream().map(tone -> new ProgrammaticContentTemplate.Choice(tone, tone))
                .toList();
    }

    /**
     * Every word of {@code words} as the template editor's reference card lists them, space-separated: the values
     * ({@code {{trip:dates}}}), the blocks by their opening mark ({@code {{#trip:dates}}}), the gallery slot when the
     * section has a gallery, and the properties ({@code {{tone}}}).
     */
    public static String reference(final Vocabulary words) {
        final List<String> all = new ArrayList<>();
        words.values().forEach(name -> all.add("{{trip:" + name + "}}"));
        words.blocks().forEach(name -> all.add("{{#trip:" + name + "}}"));
        if (words.blocks().contains(GALLERY)) {
            all.add(GALLERY_SLOT);
        }
        words.properties().forEach(name -> all.add("{{" + name + "}}"));
        return String.join(" ", all);
    }

    /** Fills {@code body} with {@code data}; a blank body renders nothing. */
    public static Rendered render(final String body, final Data data) {
        if (body == null || body.isBlank()) {
            return new Rendered("", "", false);
        }
        final String kept = keepBlocks(body, data.blocks());
        final int at = kept.indexOf(GALLERY_SLOT);
        if (at < 0) {
            return new Rendered(fill(kept, data), "", false);
        }
        return new Rendered(fill(kept.substring(0, at), data), fill(kept.substring(at + GALLERY_SLOT.length()), data),
                true);
    }

    /**
     * What is wrong with a layout body, in words the template editor shows, or null: a token, block or property
     * this section does not know, a block opened and not closed (or closed and not opened), blocks nested too deep,
     * or more than one gallery slot. Blank is fine: it means the built-in layout.
     */
    public static String problem(final String body, final Vocabulary words) {
        if (body == null || body.isBlank()) {
            return null;
        }
        final Matcher value = VALUE.matcher(body);
        while (value.find()) {
            if (!knownValue(value.group(1), words)) {
                return Text.get("feedback.tripLayout.unknownToken", value.group());
            }
        }
        final String marks = marksProblem(body, words);
        if (marks != null) {
            return marks;
        }
        final Matcher property = PROPERTY.matcher(body);
        while (property.find()) {
            if (!words.properties().contains(property.group(1))) {
                return Text.get("feedback.tripLayout.unknownToken", property.group());
            }
        }
        if (body.indexOf(GALLERY_SLOT) != body.lastIndexOf(GALLERY_SLOT)) {
            return Text.get("feedback.tripLayout.oneGallery", GALLERY_SLOT);
        }
        return null;
    }

    /** A value the section fills, or the gallery slot of a section that has a gallery. */
    private static boolean knownValue(final String name, final Vocabulary words) {
        return words.values().contains(name) || (GALLERY.equals(name) && words.blocks().contains(GALLERY));
    }

    private static String marksProblem(final String body, final Vocabulary words) {
        final Map<String, Integer> open = new HashMap<>();
        final Matcher mark = MARK.matcher(body);
        while (mark.find()) {
            final String name = mark.group(2);
            if (!words.blocks().contains(name)) {
                return Text.get("feedback.tripLayout.unknownBlock", mark.group());
            }
            open.merge(name, "#".equals(mark.group(1)) ? 1 : -1, Integer::sum);
            if (open.get(name) < 0) {
                return Text.get("feedback.tripLayout.unbalanced", name);
            }
        }
        for (final Map.Entry<String, Integer> each : open.entrySet()) {
            if (each.getValue() != 0) {
                return Text.get("feedback.tripLayout.unbalanced", each.getKey());
            }
        }
        if (MARK.matcher(keepAll(body)).find()) {
            return Text.get("feedback.tripLayout.tooDeep");
        }
        return null;
    }

    /** The body with every block kept: what is left of a mark afterwards was nested too deep (or crossed). */
    private static String keepAll(final String body) {
        String out = body;
        for (int round = 0; round < MAX_DEPTH; round++) {
            out = BLOCK.matcher(out).replaceAll(match -> Matcher.quoteReplacement(match.group(2)));
        }
        return out;
    }

    private static String keepBlocks(final String body, final Set<String> blocks) {
        String out = body;
        for (int round = 0; round < MAX_DEPTH && BLOCK.matcher(out).find(); round++) {
            out = BLOCK.matcher(out).replaceAll(match -> Matcher.quoteReplacement(blockText(match, blocks)));
        }
        return MARK.matcher(out).replaceAll("");
    }

    private static String blockText(final MatchResult match, final Set<String> blocks) {
        return blocks.contains(match.group(1)) ? match.group(2) : "";
    }

    /**
     * The section's properties first, then the trip's values: a trip's typed value (its destination, say) is never
     * read again as a token, so one holding "{{tone}}" stays that text.
     */
    private static String fill(final String part, final Data data) {
        final String props = PROPERTY.matcher(part)
                .replaceAll(match -> Matcher.quoteReplacement(property(match, data)));
        return VALUE.matcher(props).replaceAll(match -> Matcher.quoteReplacement(value(match, data)));
    }

    private static String value(final MatchResult match, final Data data) {
        final String name = match.group(1);
        if (data.html().containsKey(name)) {
            return data.html().get(name);
        }
        final String text = data.text().get(name);
        return text == null ? "" : Html.escape(text);
    }

    private static String property(final MatchResult match, final Data data) {
        final String value = data.properties().get(match.group(1));
        return value == null ? "" : Html.escape(value);
    }
}
