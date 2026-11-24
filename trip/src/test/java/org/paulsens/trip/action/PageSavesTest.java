package org.paulsens.trip.action;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.testng.Assert;
import org.testng.annotations.Test;

public class PageSavesTest {

    @Test
    public void aCompletedSaveAnswersItsOwnResultAndAsksForNoMessage() {
        final List<String> asked = new ArrayList<>();
        Assert.assertTrue(PageSaves.saveOrGrowl(() -> true, () -> note(asked, "error"), () -> note(asked, "io"),
                "row"));
        Assert.assertFalse(PageSaves.saveOrGrowl(() -> false, () -> note(asked, "error"), () -> note(asked, "io"),
                "row"), "a refused write is the DAO's false, not an exception");
        Assert.assertTrue(asked.isEmpty(), "messages are built only on a failure: " + asked);
    }

    @Test
    public void aThrownSaveIsFalseWithTheMessageForWhatFailed() {
        final List<String> asked = new ArrayList<>();
        Assert.assertFalse(PageSaves.saveOrGrowl(PageSavesTest::storeFails, () -> note(asked, "error"),
                () -> note(asked, "io"), "row"));
        Assert.assertEquals(asked, List.of("error"));

        asked.clear();
        Assert.assertFalse(PageSaves.saveOrGrowl(PageSavesTest::cannotSerialize, () -> note(asked, "error"),
                () -> note(asked, "io"), "row"));
        Assert.assertEquals(asked, List.of("io"));
    }

    private static String note(final List<String> asked, final String which) {
        asked.add(which);
        return which;
    }

    private static boolean storeFails() {
        throw new IllegalStateException("the store refused");
    }

    private static boolean cannotSerialize() throws IOException {
        throw new IOException("not serializable");
    }
}
