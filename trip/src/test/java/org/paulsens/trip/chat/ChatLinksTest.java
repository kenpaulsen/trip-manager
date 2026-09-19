package org.paulsens.trip.chat;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The link rule shared with the browser pane and the iOS app: {@code https?://} to the next whitespace, sentence
 * punctuation left outside, everything else escaped. The escaping assertions matter most -- the output is wrapped
 * in {@link MailTemplates.Raw}, so this class is the only thing between a chat body and a mail reader's HTML.
 */
public class ChatLinksTest {

    private static final String ATTRS = "target=\"_blank\" rel=\"noopener noreferrer nofollow\"";

    @Test
    public void urlBecomesAnAnchorThatOpensInANewWindow() {
        Assert.assertEquals(ChatLinks.html("see https://example.com/a?b=1&c=2 now"),
                "see <a href=\"https://example.com/a?b=1&amp;c=2\" " + ATTRS + ">https://example.com/a?b=1&amp;c=2</a>"
                        + " now");
    }

    @Test
    public void sentencePunctuationStaysOutsideTheLink() {
        Assert.assertEquals(ChatLinks.html("(bus times: http://x.org/t)."),
                "(bus times: <a href=\"http://x.org/t\" " + ATTRS + ">http://x.org/t</a>).");
        Assert.assertEquals(ChatLinks.html("Go to https://x.org/t?q=1…"),
                "Go to <a href=\"https://x.org/t?q=1\" " + ATTRS + ">https://x.org/t?q=1</a>…");
    }

    @Test
    public void everythingOutsideAnAnchorIsEscaped() {
        final String out = ChatLinks.html("<b>hi</b> https://e.com/\"onmouseover=\"x <i>there</i>");
        Assert.assertFalse(out.contains("<b>"), out);
        Assert.assertFalse(out.contains("<i>"), out);
        Assert.assertFalse(out.contains("\"onmouseover"), "an attribute must never reach the anchor: " + out);
        Assert.assertTrue(out.contains("<a href=\"https://e.com/&quot;onmouseover=&quot;x\""), out);
    }

    @Test
    public void onlyHttpSchemesAreLinked() {
        Assert.assertEquals(ChatLinks.html("javascript:alert(1) ftp://x.org www.x.org"),
                "javascript:alert(1) ftp://x.org www.x.org");
        Assert.assertEquals(ChatLinks.html("see https://."), "see https://.");
    }

    @Test
    public void nullAndPlainTextPassThrough() {
        Assert.assertEquals(ChatLinks.html(null), "");
        Assert.assertEquals(ChatLinks.html(""), "");
        Assert.assertEquals(ChatLinks.html("no links & no tags"), "no links &amp; no tags");
    }

    @Test
    public void severalLinksInOneBody() {
        final String out = ChatLinks.html("https://a.org, https://b.org");
        Assert.assertEquals(out, "<a href=\"https://a.org\" " + ATTRS + ">https://a.org</a>, "
                + "<a href=\"https://b.org\" " + ATTRS + ">https://b.org</a>");
    }
}
