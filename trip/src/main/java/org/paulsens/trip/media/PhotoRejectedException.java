package org.paulsens.trip.media;

/**
 * An upload the pipeline refuses to process. The message is written for the person who chose the file — it
 * goes straight back to the upload dialog — so it says what to do ("convert it to JPEG"), not what failed
 * internally.
 */
public class PhotoRejectedException extends RuntimeException {

    public PhotoRejectedException(final String userMessage) {
        super(userMessage);
    }

    public PhotoRejectedException(final String userMessage, final Throwable cause) {
        super(userMessage, cause);
    }
}
