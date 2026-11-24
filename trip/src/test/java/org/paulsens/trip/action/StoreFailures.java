package org.paulsens.trip.action;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.dynamo.DAO;
import org.testng.Assert;

/**
 * A save whose put FAILS, for the beans' "could not be saved" paths: the real store with one write taught to
 * throw. The fake store never fails, so nothing else reaches those catch blocks, and what they tell the page is
 * the point: a fixed sentence from the bundle, never the exception's own words (an SDK's request id, a row id).
 */
final class StoreFailures {
    /** What a failing store "says": no page may ever show it. */
    static final String STORE_WORDS = "RequestId 7f3a-internal: ProvisionedThroughputExceeded";

    private StoreFailures() {
    }

    /** Teaches the store one failing write: {@code dao -> Mockito.doThrow(...).when(dao).saveX(any())}. */
    @FunctionalInterface
    interface Lesson {
        void teach(DAO dao) throws IOException;
    }

    static IOException storeDown() {
        return new IOException(STORE_WORDS);
    }

    /**
     * Runs {@code save} against a store that fails as {@code lesson} says, and answers what the page's growl
     * showed. The save must answer false.
     */
    static List<String> growlsOfFailed(final Lesson lesson, final BooleanSupplier save) {
        final DAO failing = Mockito.spy(DAO.getInstance());
        try {
            lesson.teach(failing);
        } catch (final IOException ex) {
            throw new IllegalStateException(ex);
        }
        try (MockedStatic<DAO> statics = Mockito.mockStatic(DAO.class, Mockito.CALLS_REAL_METHODS)) {
            statics.when(DAO::getInstance).thenReturn(failing);
            return TestFaces.growlsOf(new HashMap<>(), () -> Assert.assertFalse(save.getAsBoolean()));
        }
    }

    /** The one thing every such path owes the page: its own sentence, and none of the store's words. */
    static void assertSays(final List<String> growls, final String sentence) {
        Assert.assertTrue(growls.contains(sentence), growls.toString());
        Assert.assertTrue(growls.stream().noneMatch(growl -> growl.contains("RequestId")), growls.toString());
    }
}
