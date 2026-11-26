package org.paulsens.trip.content;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.paulsens.trip.model.Placeholder;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * A live section's layout body (docs/content-templates.md, "Live sections with a layout"): its tokens, blocks,
 * gallery slot and escaping, and what a save refuses.
 */
public class TripLayoutTest {

    private static final TripLayout.Vocabulary FACTS = new TripFactsType().layoutVocabulary();
    private static final TripLayout.Vocabulary PICTURES = new TripPicturesType().layoutVocabulary();

    private static TripLayout.Data data(final Map<String, String> text, final Set<String> blocks) {
        return new TripLayout.Data(text, Map.of(), blocks, Map.of(TripLayout.PROP_TONE, "tint"));
    }

    @Test
    public void aValueIsEscapedAndAMissingOneDrawsNothing() {
        final TripLayout.Rendered out = TripLayout.render("<p>{{trip:destination}}|{{trip:guide}}</p>",
                data(Map.of("destination", "<b>Rome & \"Assisi\"</b>"), Set.of()));
        Assert.assertEquals(out.before(), "<p>&lt;b&gt;Rome &amp; &quot;Assisi&quot;&lt;/b&gt;|</p>");
        Assert.assertEquals(out.after(), "");
        Assert.assertFalse(out.slot());
    }

    @Test
    public void markupTheServerBuiltIsWrittenAsBuilt() {
        final TripLayout.Data built = new TripLayout.Data(Map.of("cover", "<not used>"),
                Map.of("cover", "<img src=\"/c.jpg\" alt=\"A &amp; B\" />"), Set.of(), Map.of());
        Assert.assertEquals(TripLayout.render("<span>{{trip:cover}}</span>", built).before(),
                "<span><img src=\"/c.jpg\" alt=\"A &amp; B\" /></span>", "the html map wins over the text map");
    }

    @Test
    public void aBlockIsKeptOnlyWhenItsNameIsListed() {
        final String body = "{{#trip:price}}P{{/trip:price}}{{#trip:guide}}G{{/trip:guide}}";
        Assert.assertEquals(TripLayout.render(body, data(Map.of(), Set.of("price"))).before(), "P");
        Assert.assertEquals(TripLayout.render(body, data(Map.of(), Set.of())).before(), "");
        final String nested = "{{#trip:price}}a{{#trip:guide}}b{{/trip:guide}}c{{/trip:price}}";
        Assert.assertEquals(TripLayout.render(nested, data(Map.of(), Set.of("price"))).before(), "ac");
        Assert.assertEquals(TripLayout.render(nested, data(Map.of(), Set.of("price", "guide"))).before(), "abc");
        Assert.assertEquals(TripLayout.render(nested, data(Map.of(), Set.of("guide"))).before(), "",
                "an inner block goes with the block around it");
    }

    @Test
    public void aTypedValueIsNeverReadAgainAsAToken() {
        final TripLayout.Rendered out = TripLayout.render("<i>{{trip:destination}}</i><b>{{tone}}</b>",
                data(Map.of("destination", "{{tone}} {{trip:guide}} {{#trip:price}}", "guide", "G"), Set.of()));
        Assert.assertEquals(out.before(), "<i>{{tone}} {{trip:guide}} {{#trip:price}}</i><b>tint</b>");
    }

    @Test
    public void aPropertyIsFilledEscapedAndAMissingOneDrawsNothing() {
        final TripLayout.Data hostile = new TripLayout.Data(Map.of(), Map.of(), Set.of(),
                Map.of(TripLayout.PROP_TONE, "\"><script>"));
        Assert.assertEquals(TripLayout.render("<div class=\"x {{tone}}\">", hostile).before(),
                "<div class=\"x &quot;&gt;&lt;script&gt;\">");
        Assert.assertEquals(TripLayout.render("<div class=\"x {{tone}}\">",
                new TripLayout.Data(Map.of(), Map.of(), Set.of(), Map.of())).before(), "<div class=\"x \">");
    }

    @Test
    public void theBodyIsCutAtTheGallerySlot() {
        final String body = "<div class=\"hero\">{{#trip:gallery}}{{trip:gallery}}{{/trip:gallery}}</div>";
        final TripLayout.Rendered shown = TripLayout.render(body, data(Map.of(), Set.of(TripLayout.GALLERY)));
        Assert.assertTrue(shown.slot());
        Assert.assertEquals(shown.before(), "<div class=\"hero\">");
        Assert.assertEquals(shown.after(), "</div>");
        final TripLayout.Rendered hidden = TripLayout.render(body, data(Map.of(), Set.of()));
        Assert.assertFalse(hidden.slot(), "a slot inside a dropped block is gone with it");
        Assert.assertEquals(hidden.before(), "<div class=\"hero\"></div>");
    }

    @Test
    public void aBlankBodyDrawsNothing() {
        for (final String blank : new String[] {null, "", "  \n"}) {
            Assert.assertEquals(TripLayout.render(blank, data(Map.of(), Set.of())),
                    new TripLayout.Rendered("", "", false));
            Assert.assertNull(TripLayout.problem(blank, FACTS), "blank is the built-in layout");
        }
    }

    @Test
    public void theStarterLayoutsAreClean() {
        Assert.assertNull(TripLayout.problem(StarterTemplates.TRIP_DETAILS_LAYOUT, FACTS));
        Assert.assertNull(TripLayout.problem(StarterTemplates.TRIP_PICTURES_LAYOUT, PICTURES));
        Assert.assertNull(HtmlCheck.templateProblem(StarterTemplates.TRIP_DETAILS_LAYOUT));
        Assert.assertNull(HtmlCheck.templateProblem(StarterTemplates.TRIP_PICTURES_LAYOUT));
        Assert.assertNull(TripLayout.problem("<div class=\"{{tone}}\">{{#trip:dates}}{{trip:dates}}{{/trip:dates}}"
                + "</div>", FACTS));
    }

    @Test
    public void aSaveRefusesWhatTheSectionDoesNotKnow() {
        Assert.assertTrue(TripLayout.problem("<p>{{trip:nope}}</p>", FACTS).contains("{{trip:nope}}"));
        Assert.assertNotNull(TripLayout.problem("{{trip:gallery}}", FACTS), "the facts line has no gallery");
        Assert.assertNull(TripLayout.problem("<div>{{trip:gallery}}</div>", PICTURES));
        Assert.assertNotNull(TripLayout.problem("{{trip:dates}}", PICTURES), "a facts value is not a picture's");
        Assert.assertTrue(TripLayout.problem("{{#trip:nope}}x{{/trip:nope}}", FACTS).contains("nope"));
        Assert.assertTrue(TripLayout.problem("{{#trip:price}}x", FACTS).contains("price"), "opened, not closed");
        Assert.assertTrue(TripLayout.problem("{{/trip:price}}x{{#trip:price}}", FACTS).contains("price"),
                "closed before it is opened");
        Assert.assertNotNull(TripLayout.problem("<b>{{color}}</b>", FACTS), "not one of the section's properties");
        Assert.assertNotNull(TripLayout.problem("{{trip:gallery}}<p></p>{{trip:gallery}}", PICTURES),
                "one gallery");
    }

    @Test
    public void blocksNestedPastTheLimitAreRefused() {
        final List<String> names = List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j");
        final TripLayout.Vocabulary deep = new TripLayout.Vocabulary(List.of(), names, List.of());
        final StringBuilder body = new StringBuilder();
        names.forEach(name -> body.append("{{#trip:").append(name).append("}}"));
        names.reversed().forEach(name -> body.append("{{/trip:").append(name).append("}}"));
        Assert.assertNotNull(TripLayout.problem(body.toString(), deep), "ten deep");
        Assert.assertNull(TripLayout.problem("{{#trip:a}}{{#trip:b}}x{{/trip:b}}{{/trip:a}}", deep), "two deep");
    }

    @Test
    public void theReferenceListsEveryWord() {
        final String facts = TripLayout.reference(FACTS);
        Assert.assertTrue(facts.contains("{{trip:dates}}") && facts.contains("{{trip:flyerLabel}}"), facts);
        Assert.assertTrue(facts.contains("{{#trip:flyer}}") && facts.contains("{{tone}}"), facts);
        Assert.assertFalse(facts.contains("gallery"), facts);
        final String pictures = TripLayout.reference(PICTURES);
        Assert.assertTrue(pictures.contains(TripLayout.GALLERY_SLOT) && pictures.contains("{{#trip:single}}"),
                pictures);
    }

    @Test
    public void theToneIsOneOfTheBandsOrNone() {
        Assert.assertEquals(TripLayout.toneClass("tint"), "tone-tint");
        Assert.assertEquals(TripLayout.toneClass("transparent"), "tone-transparent");
        for (final String none : new String[] {null, "", "Dark", "tint\" onload=\"x"}) {
            Assert.assertEquals(TripLayout.toneClass(none), "", String.valueOf(none));
        }
        Assert.assertEquals(TripLayout.toneChoices().stream().map(ProgrammaticContentTemplate.Choice::value)
                .toList(), StarterTemplates.TONES);
    }

    @Test
    public void bothLiveSectionsOfferTheTone() {
        for (final ProgrammaticContentTemplate type : List.of(new TripFactsType(), new TripPicturesType())) {
            final Placeholder tone = type.getProperties().stream()
                    .filter(ph -> TripLayout.PROP_TONE.equals(ph.getName())).findFirst().orElseThrow();
            Assert.assertEquals(tone.getType(), Placeholder.Type.CHOICE);
            Assert.assertEquals(type.choicesFor(TripLayout.PROP_TONE), TripLayout.toneChoices());
            Assert.assertTrue(type.layoutVocabulary().properties().contains(TripLayout.PROP_TONE));
        }
        Assert.assertFalse(new TripFactsType().choicesFor("shown").isEmpty(), "its own menu is still there");
        Assert.assertFalse(new TripPicturesType().choicesFor("show").isEmpty());
        Assert.assertNull(ProgrammaticTypes.byId(StarterTemplates.TRIP_SCHEDULE_ID).orElseThrow()
                .layoutVocabulary(), "any other live type has no layout");
    }

    /** A File's words carry its own prefix (2026-11-26): filled the same way, and only its own pass a save. */
    @Test
    public void aFileLayoutUsesItsOwnWordsAndNoOthers() {
        final TripLayout.Vocabulary file = new FileType().layoutVocabulary();
        Assert.assertEquals(file.prefix(), TripLayout.FILE);
        final TripLayout.Data doc = new TripLayout.Data(Map.of("title", "Guide <2026>", "url", "/d/g.pdf"), Map.of(),
                Set.of(), Map.of());
        Assert.assertEquals(TripLayout.render(TripLayout.FILE,
                "<a href=\"{{file:url}}\">{{file:title}}</a>{{#file:description}}D{{/file:description}}", doc)
                        .before(), "<a href=\"/d/g.pdf\">Guide &lt;2026&gt;</a>", "no description, no block");
        Assert.assertEquals(TripLayout.render(TripLayout.FILE, "{{trip:gallery}}x", doc).before(), "{{trip:gallery}}x",
                "only a trip section is cut at a gallery slot");
        Assert.assertNull(TripLayout.problem("<a href=\"{{file:url}}\">{{file:title}}</a> {{file:size}}"
                + "{{#file:description}}<p>{{file:description}}</p>{{/file:description}}", file));
        Assert.assertNotNull(TripLayout.problem("{{trip:destination}}", file), "a trip word in a File layout");
        Assert.assertNotNull(TripLayout.problem("{{file:title}}", new TripFactsType().layoutVocabulary()),
                "a File word in a trip layout");
        Assert.assertNotNull(TripLayout.problem("{{container:title}}", file), "a group's word");
        Assert.assertNotNull(TripLayout.problem("{{file:pages}}", file), "a word the File type does not have");
        Assert.assertNotNull(TripLayout.problem("{{tone}}", file), "a File has no Tone");
        Assert.assertEquals(TripLayout.reference(file),
                "{{file:title}} {{file:url}} {{file:size}} {{file:description}} {{#file:description}}");
    }
}
