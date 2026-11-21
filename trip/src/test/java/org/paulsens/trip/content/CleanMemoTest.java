package org.paulsens.trip.content;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.Assert;
import org.testng.annotations.Test;

/** The memo behind {@link PageHtml#clean}: one clean per body, and never more held than its three bounds allow. */
public class CleanMemoTest {

    private final AtomicInteger cleans = new AtomicInteger();

    private PageHtml.Cleaned countedClean(final String html) {
        cleans.incrementAndGet();
        return new PageHtml.Cleaned(html, List.of());
    }

    @Test
    public void aCleanIsRememberedButNeverABodyTooLongToHold() {
        final CleanMemo memo = new CleanMemo();
        final int before = cleans.get();
        Assert.assertSame(memo.get("<p>a</p>", this::countedClean), memo.get("<p>a</p>", this::countedClean));
        Assert.assertEquals(cleans.get() - before, 1);
        final String huge = "a".repeat(CleanMemo.MAX_LENGTH + 1);
        memo.get(huge, this::countedClean);
        memo.get(huge, this::countedClean);
        Assert.assertEquals(cleans.get() - before, 3, "too long to hold: cleaned each time");
        for (int i = 0; i < CleanMemo.MAX_ENTRIES + 50; i++) {
            memo.get("<p>" + i + "</p>", this::countedClean);
        }
        Assert.assertEquals(memo.size(), CleanMemo.MAX_ENTRIES, "the least recently used go first");
    }

    /** A few hundred long bodies must not add up to more than the memo's share of the heap. */
    @Test
    public void theBodiesHeldNeverAddUpToMoreThanTheTotal() {
        final CleanMemo memo = new CleanMemo();
        final String filler = "x".repeat(CleanMemo.MAX_LENGTH - 10);
        for (int i = 0; i < 300; i++) {
            memo.get(filler + i, this::countedClean);
        }
        Assert.assertTrue(memo.total() <= CleanMemo.MAX_TOTAL, "holds " + memo.total());
        Assert.assertTrue(memo.size() < 300 && memo.size() > 0, "holds " + memo.size() + " entries");
    }

    /** The page path remembers; the save and preview path, whose bodies are drafts, does not fill the memo. */
    @Test
    public void onlyThePagePathIsRemembered() {
        final String body = "<p>memo " + System.nanoTime() + "</p>";
        Assert.assertSame(PageHtml.clean(body), PageHtml.clean(body), "one clean, one answer, handed out twice");
        Assert.assertNotSame(PageHtml.cleanNoting(body), PageHtml.cleanNoting(body));
    }
}
