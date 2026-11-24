package org.paulsens.trip.action;

import java.io.IOException;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * A bean's save as the page reads it: true when the row was written, and a growl plus a log line when the write
 * threw. Six beans carried this try/catch pair around one DAO call each.
 */
@Slf4j
final class PageSaves {
    private PageSaves() {
    }

    /** A DAO write: answers whether the row was written, and may fail to serialize it. */
    @FunctionalInterface
    interface IoSave {
        boolean save() throws IOException;
    }

    /**
     * Runs the save. A thrown exception becomes {@code false}, the matching growl (never the exception's own text:
     * {@link PageFeedback#failure}) and an error log naming {@code what}.
     *
     * @param onError     the growl when the store threw (a refused or failed write).
     * @param onIoFailure the growl when the row could not be serialized.
     * @param what        the noun for the log line: "trip", "registration".
     */
    static boolean saveOrGrowl(final IoSave save, final Supplier<String> onError, final Supplier<String> onIoFailure,
            final String what) {
        try {
            return save.save();
        } catch (final RuntimeException ex) {
            PageFeedback.failure(onError.get());
            log.error("Error while saving {}: ", what, ex);
        } catch (final IOException ex) {
            PageFeedback.failure(onIoFailure.get());
            log.error("Error while saving {}: ", what, ex);
        }
        return false;
    }
}
