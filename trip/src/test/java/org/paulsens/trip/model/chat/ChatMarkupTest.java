package org.paulsens.trip.model.chat;

import java.util.List;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * The formatting grammar, as a conformance table. The SAME table is encoded by {@code ChatFormattingPwIT}
 * (the browser parser, through {@code TripChatFormat.debug}) and {@code ChatMarkupTests} (the app's Swift
 * parser), in the debug notation used here: paragraph lines joined by a newline inside {@code <p>}, styles as
 * {@code b i u s}, lists as {@code ul}/{@code ol start=N} with {@code li} items. Change a case in one place and
 * the other two must follow, or one surface reads a body differently from the rest.
 */
public class ChatMarkupTest {

    @DataProvider(name = "cases")
    public static Object[][] cases() {
        return new Object[][] {
            {"hello", "<p>hello</p>"},
            {"**bold**", "<p><b>bold</b></p>"},
            {"*it* _it_", "<p><i>it</i> <i>it</i></p>"},
            {"__u__ ~~s~~", "<p><u>u</u> <s>s</s></p>"},
            {"***both***", "<p><b><i>both</i></b></p>"},
            {"___both___", "<p><u><i>both</i></u></p>"},
            {"**bold _it_ text**", "<p><b>bold <i>it</i> text</b></p>"},
            {"*a **b** c*", "<p><i>a <b>b</b> c</i></p>"},
            {"**a*b**", "<p><b>a*b</b></p>"},
            {"snake_case_name 2*3*4 file_", "<p>snake_case_name 2*3*4 file_</p>"},
            {"**unclosed and *also", "<p>**unclosed and *also</p>"},
            {"** not bold **", "<p>** not bold **</p>"},
            {"**bold**text", "<p>**bold**text</p>"},
            {"(**bold**), \"**b**\".", "<p>(<b>bold</b>), \"<b>b</b>\".</p>"},
            {"**@{abc_1}**", "<p><b>@{abc_1}</b></p>"},
            {"**https://x.com/a_b**", "<p><b>https://x.com/a_b</b></p>"},
            {"see https://x.com/**not** ok", "<p>see https://x.com/**not** ok</p>"},
            {"**https://x.com.**", "<p><b>https://x.com.</b></p>"},
            {"- a\n- b", "<ul><li>a</li><li>b</li></ul>"},
            {"* a\n• b", "<ul><li>a</li><li>b</li></ul>"},
            {"3. c\n4) d", "<ol start=3><li>c</li><li>d</li></ol>"},
            {"intro\n- a\n\n1. x\noutro",
                "<p>intro</p><ul><li>a</li></ul><p></p><ol start=1><li>x</li></ol><p>outro</p>"},
            {"- **bold** item", "<ul><li><b>bold</b> item</li></ul>"},
            {"-not a list\n1.also not\n1234. nor", "<p>-not a list\n1.also not\n1234. nor</p>"},
            {"**a\nb**", "<p>**a\nb**</p>"},
            {"**** ~s~ ~~~x~~~", "<p>**** ~s~ ~~~x~~~</p>"},
            {"**😀 x**", "<p><b>😀 x</b></p>"},
            {"    - deep\n\t- tab", "<ul><li>deep</li><li>tab</li></ul>"},
            {"- ", "<ul><li></li></ul>"},
            {"1. a\n- b", "<ol start=1><li>a</li></ol><ul><li>b</li></ul>"},
            {"a **b**\n**c** d", "<p>a <b>b</b>\n<b>c</b> d</p>"},
            {"a\r\nb\rc", "<p>a\nb\nc</p>"},
            {"**b** and *i* and __u__ and ~~s~~", "<p><b>b</b> and <i>i</i> and <u>u</u> and <s>s</s></p>"},
            {"¿qué *tal*? **été**", "<p>¿qué <i>tal</i>? <b>été</b></p>"},
        };
    }

    @Test(dataProvider = "cases")
    public void parsesTheConformanceTable(final String body, final String expected) {
        Assert.assertEquals(debug(ChatMarkup.parse(body)), expected, "body: " + body.replace("\n", "\\n"));
    }

    @Test
    public void nothingParsesToNothing() {
        Assert.assertEquals(ChatMarkup.parse(null), List.of());
        Assert.assertEquals(ChatMarkup.parse(""), List.of());
        Assert.assertEquals(ChatMarkup.parseInline(null), List.of());
        Assert.assertEquals(ChatMarkup.plain(null), "");
        Assert.assertEquals(ChatMarkup.plain(""), "");
    }

    @Test
    public void plainDropsTheMarkersAndKeepsOneLinePerSourceLine() {
        Assert.assertEquals(ChatMarkup.plain("**bold** *it* __u__ ~~s~~ @{id} https://x.org/a_b"),
                "bold it u s @{id} https://x.org/a_b");
        Assert.assertEquals(ChatMarkup.plain("- a\n2. b\n3. c"), "• a\n2. b\n3. c");
        Assert.assertEquals(ChatMarkup.plain("1. x\n1. y"), "1. x\n2. y", "numbers count on from the start");
        Assert.assertEquals(ChatMarkup.plain("intro\n\n- ***deep*** item\nend"), "intro\n\n• deep item\nend");
        Assert.assertEquals(ChatMarkup.plain("plain **unclosed"), "plain **unclosed",
                "an orphan marker is text, so it stays");
    }

    @Test
    public void textOfInlinesIsTheirLiteralContent() {
        Assert.assertEquals(ChatMarkup.text(ChatMarkup.parseInline("a **b _c_** d")), "a b c d");
    }

    /** A mention token that is not really one (whitespace inside, no closing brace) is ordinary text. */
    @Test
    public void aMalformedMentionTokenIsNotOpaque() {
        Assert.assertEquals(debug(ChatMarkup.parse("@{a b} *x* @{open *y*")), "<p>@{a b} <i>x</i> @{open <i>y</i></p>");
    }

    /** The parse tree is what the model sweep expects: immutable and serializable. */
    @Test
    public void theTreeIsImmutable() {
        final List<ChatMarkup.Block> blocks = ChatMarkup.parse("- a");
        Assert.assertThrows(UnsupportedOperationException.class, blocks::clear);
        final ChatMarkup.ListBlock list = (ChatMarkup.ListBlock) blocks.get(0);
        Assert.assertThrows(UnsupportedOperationException.class, list.items()::clear);
    }

    static String debug(final List<ChatMarkup.Block> blocks) {
        final StringBuilder out = new StringBuilder();
        for (final ChatMarkup.Block block : blocks) {
            if (block instanceof ChatMarkup.Paragraph paragraph) {
                out.append("<p>");
                for (int i = 0; i < paragraph.lines().size(); i++) {
                    out.append(i > 0 ? "\n" : "").append(debugInline(paragraph.lines().get(i)));
                }
                out.append("</p>");
            } else if (block instanceof ChatMarkup.ListBlock list) {
                out.append(list.ordered() ? "<ol start=" + list.start() + ">" : "<ul>");
                for (final List<ChatMarkup.Inline> item : list.items()) {
                    out.append("<li>").append(debugInline(item)).append("</li>");
                }
                out.append(list.ordered() ? "</ol>" : "</ul>");
            }
        }
        return out.toString();
    }

    private static String debugInline(final List<ChatMarkup.Inline> inlines) {
        final StringBuilder out = new StringBuilder();
        for (final ChatMarkup.Inline inline : inlines) {
            if (inline instanceof ChatMarkup.Text text) {
                out.append(text.text());
            } else if (inline instanceof ChatMarkup.Styled styled) {
                final String tag = switch (styled.style()) {
                    case BOLD -> "b";
                    case ITALIC -> "i";
                    case UNDERLINE -> "u";
                    case STRIKETHROUGH -> "s";
                };
                out.append('<').append(tag).append('>').append(debugInline(styled.children()))
                        .append("</").append(tag).append('>');
            }
        }
        return out.toString();
    }
}
