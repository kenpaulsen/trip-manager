package org.paulsens.trip.action;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.api.Beans;
import org.paulsens.trip.content.StarterTemplates;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.MediaItem;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * A File item through its template's layout body (docs/content-templates.md, "Live sections with a layout"): the
 * document's values, escaped, its description block, and the built-in look for a blank body.
 */
public class FileLayoutCommandsTest {

    private final Map<String, Object> requestMap = new HashMap<>();
    private final AtomicInteger reads = new AtomicInteger();
    private String body;
    private FileLayoutCommands layouts;

    @BeforeMethod
    public void setUp() {
        requestMap.clear();
        reads.set(0);
        body = null;
        layouts = new FileLayoutCommands(this::bodyOf, item -> "https://files.test/" + item.getS3Key(),
                () -> requestMap);
    }

    private String bodyOf(final ContentInstance instance) {
        reads.incrementAndGet();
        return body;
    }

    private static ContentInstance file(final String id) {
        return new ContentInstance(id, "docs", "", StarterTemplates.FILE_ID, 1,
                new HashMap<>(Map.of("mediaId", "m1")), null, 0, 1, null, null);
    }

    private static MediaItem doc(final String description) {
        return new MediaItem("m1", "downloads/Guide.pdf", "Guide <2026> & more", description, "application/pdf",
                2_400_000L, "home-docs", 0, LocalDateTime.of(2026, 11, 1, 9, 0), "test");
    }

    @Test
    public void aBlankBodyLeavesTheBuiltInLook() {
        Assert.assertFalse(layouts.of(file("f1"), doc(null)).set());
        body = "  ";
        Assert.assertFalse(layouts.of(file("f2"), doc(null)).set());
        Assert.assertFalse(layouts.of(null, doc(null)).set(), "no item");
        Assert.assertFalse(layouts.of(file("f3"), null).set(), "a hidden or deleted document");
    }

    @Test
    public void aBodyIsFilledWithTheDocumentsValuesEscaped() {
        body = "<a href=\"{{file:url}}\">{{file:title}}</a> ({{file:size}})"
                + "{{#file:description}}<p>{{file:description}}</p>{{/file:description}}";
        final FileLayoutCommands.Layout plain = layouts.of(file("f1"), doc(""));
        Assert.assertTrue(plain.set());
        Assert.assertEquals(plain.html(), "<a href=\"https://files.test/downloads/Guide.pdf\">Guide &lt;2026&gt; &amp;"
                + " more</a> (" + doc("").getDisplaySize() + ")", "a blank description drops its block");
        Assert.assertEquals(layouts.of(file("f2"), doc("For <everyone>")).html(),
                "<a href=\"https://files.test/downloads/Guide.pdf\">Guide &lt;2026&gt; &amp; more</a> ("
                        + doc("").getDisplaySize() + ")<p>For &lt;everyone&gt;</p>");
    }

    @Test
    public void anItemIsBuiltOncePerRequest() {
        body = "{{file:title}}";
        layouts.of(file("f1"), doc(null));
        layouts.of(file("f1"), doc(null));
        Assert.assertEquals(reads.get(), 1, "the second ask is the memo's");
        requestMap.clear();
        layouts.of(file("f1"), doc(null));
        Assert.assertEquals(reads.get(), 2, "a new request builds again");
    }

    @Test
    public void theDefaultWiringReadsTheStoredLayoutAndTheLibrarysUrl() {
        final ContentCommands content = Mockito.mock(ContentCommands.class);
        Mockito.when(content.layoutBody(ArgumentMatchers.any())).thenReturn("<a href=\"{{file:url}}\">x</a>");
        final MediaCommands media = Mockito.mock(MediaCommands.class);
        Mockito.when(media.getUrl(ArgumentMatchers.any())).thenReturn("https://files.test/g.pdf");
        try (MockedStatic<Beans> beans = Mockito.mockStatic(Beans.class)) {
            beans.when(() -> Beans.get(ContentCommands.class)).thenReturn(content);
            beans.when(() -> Beans.get(MediaCommands.class)).thenReturn(media);
            Assert.assertEquals(new FileLayoutCommands().of(file("f1"), doc(null)).html(),
                    "<a href=\"https://files.test/g.pdf\">x</a>");
        }
    }
}
