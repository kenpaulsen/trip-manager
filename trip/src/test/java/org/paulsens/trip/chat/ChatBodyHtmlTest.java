package org.paulsens.trip.chat;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * {@link ChatBodyHtml}: the mail rendering of a formatted body. The allowlist assertions are the point -- the
 * output is wrapped in {@link MailTemplates.Raw}, so nothing a body contains may ever reach the mail as markup
 * except the handful of tags the renderer itself writes.
 */
public class ChatBodyHtmlTest {

    private static final String ATTRS = "target=\"_blank\" rel=\"noopener noreferrer nofollow\"";

    @Test
    public void stylesBecomeTheirTags() {
        Assert.assertEquals(ChatBodyHtml.html("**b** *i* __u__ ~~s~~ ***bi***"),
                "<b>b</b> <i>i</i> <u>u</u> <s>s</s> <b><i>bi</i></b>");
    }

    @Test
    public void linesAndListsKeepTheirShape() {
        Assert.assertEquals(ChatBodyHtml.html("one\ntwo\n- a\n- b\n3. c\n4. d\nend"),
                "one<br />two<ul><li>a</li><li>b</li></ul><ol start=\"3\"><li>c</li><li>d</li></ol>end");
        Assert.assertEquals(ChatBodyHtml.html("1. x"), "<ol><li>x</li></ol>", "a list from 1 needs no start");
    }

    @Test
    public void everyTextRunIsEscapedAndAutolinked() {
        Assert.assertEquals(ChatBodyHtml.html("**<img src=x onerror=alert(1)>** & _<b>x</b>_"),
                "<b>&lt;img src=x onerror=alert(1)&gt;</b> &amp; <i>&lt;b&gt;x&lt;/b&gt;</i>");
        Assert.assertEquals(ChatBodyHtml.html("- see **https://x.org/a?b=1&c=2**."),
                "<ul><li>see <b><a href=\"https://x.org/a?b=1&amp;c=2\" " + ATTRS + ">https://x.org/a?b=1&amp;c=2</a>"
                        + "</b>.</li></ul>");
    }

    @Test
    public void nullAndPlainBodiesPassThrough() {
        Assert.assertEquals(ChatBodyHtml.html(null), "");
        Assert.assertEquals(ChatBodyHtml.html(""), "");
        Assert.assertEquals(ChatBodyHtml.html("just text"), "just text");
    }
}
