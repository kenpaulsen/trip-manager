package org.paulsens.trip.chat;

import java.util.HashMap;
import java.util.Map;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The escaping contract for mail sent off a request thread.
 *
 * <p>This is the last line before a chat body -- arbitrary text typed by one pilgrim -- lands as HTML in another's
 * inbox, somewhere no administrator can reach to correct it.
 */
public class MailTemplatesTest {

    @Test
    public void stringValuesAreEscapedAutomatically() {
        final String out = MailTemplates.render("chat-mention", Map.of(
                "lead", "<script>alert(1)</script> in Trip & Co",
                "chatUrl", "https://example.com/trip/chat.jsf?trip=t1",
                "snippetBlock", new MailTemplates.Raw(""),
                "linkLabel", "Open", "footerNote", "why"));

        Assert.assertNotNull(out, "the template must render");
        Assert.assertFalse(out.contains("<script>"), "a script tag must never survive into a mail body");
        Assert.assertTrue(out.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        Assert.assertTrue(out.contains("Trip &amp; Co"));
    }

    @Test
    public void rawValuesPassThroughUntouched() {
        // The opt-out exists so a caller can assemble markup it has escaped field by field -- and it has to be
        // spelled out at the call site, which is the point of the wrapper type.
        final String out = MailTemplates.render("chat-mention", Map.of(
                "lead", "Ken in Fest",
                "chatUrl", "https://example.com",
                "snippetBlock", new MailTemplates.Raw("<blockquote>hi</blockquote>"),
                "linkLabel", "Open", "footerNote", "why"));

        Assert.assertTrue(out.contains("<blockquote>hi</blockquote>"));
    }

    @Test
    public void domainObjectsAreRejected() {
        // Binding a Person and writing #{person.first} would resolve straight past the escape with no way to notice.
        // Failing loudly here means that mistake is a red test rather than unescaped user text in an inbox.
        final IllegalArgumentException ex = Assert.expectThrows(IllegalArgumentException.class,
                () -> MailTemplates.render("chat-mention", Map.of(
                        "lead", "Ken in Fest",
                        "chatUrl", "https://example.com",
                        "snippetBlock", Person.Id.from("p1"),
                        "linkLabel", "Open", "footerNote", "why")));
        Assert.assertTrue(ex.getMessage().contains("escaped scalars only"), ex.getMessage());
    }

    @Test
    public void numbersAndBooleansAreAllowed() {
        final String out = MailTemplates.render("chat-digest", Map.of(
                "lead", "Fest",
                "chatUrl", "https://example.com",
                "countLine", 7,
                "messageBlock", new MailTemplates.Raw("<p>a</p>"),
                "linkLabel", true, "footerNote", "why"));
        Assert.assertTrue(out.contains("<p style=\"color:#666;\">7</p>"), out);
        Assert.assertTrue(out.contains(">true</a>"), out);
    }

    @Test
    public void aMissingTemplateReturnsNullSoNothingIsSent() {
        // Null is the "do not send" signal. Sending a half-rendered mail would be worse than sending none.
        Assert.assertNull(MailTemplates.render("no-such-template", Map.of()));
    }

    @Test
    public void anUnrenderableTemplateReturnsNullRatherThanThrowing() {
        // chat-mention references #{lead}; omitting it makes EL throw. One broken template must not be able
        // to abort a digest run part-way through.
        final Map<String, Object> tooFew = new HashMap<>();
        tooFew.put("footerNote", "why");
        Assert.assertNull(MailTemplates.render("chat-mention", tooFew));
    }

    @Test
    public void escapeHandlesTheFullSet() {
        Assert.assertEquals(MailTemplates.escape("<a href=\"x\" id='y'>&</a>"),
                "&lt;a href=&quot;x&quot; id=&#39;y&#39;&gt;&amp;&lt;/a&gt;");
        Assert.assertEquals(MailTemplates.escape(null), "");
    }

    @Test
    public void nullValuesRenderEmptyRatherThanTheWordNull() {
        final Map<String, Object> values = new HashMap<>();
        values.put("lead", null);
        values.put("chatUrl", "https://example.com");
        values.put("snippetBlock", new MailTemplates.Raw(""));
        values.put("linkLabel", "Open");
        values.put("footerNote", "why");
        final String out = MailTemplates.render("chat-mention", values);
        Assert.assertNotNull(out);
        Assert.assertFalse(out.contains("null"), "a null value must not print as the word 'null': " + out);
    }
}
