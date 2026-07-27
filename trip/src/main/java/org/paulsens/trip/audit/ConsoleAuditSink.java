package org.paulsens.trip.audit;

import org.paulsens.trip.model.AuditEvent;

/**
 * Writes audit records to stdout. Used for local runs and tests, and as the fallback whenever another sink
 * cannot be built or cannot deliver -- in a container stdout is captured by the awslogs driver, so records
 * still reach CloudWatch (in the application log group rather than the audit one). Never the silent option.
 */
public final class ConsoleAuditSink implements AuditSink {

    @Override
    public void write(final AuditEvent event) {
        writeLine(AuditSink.format(event));
    }

    /**
     * Prints an already-rendered line. Exposed for the other sinks' failure paths, which have a line in hand
     * (and a prefix to add) rather than an event.
     */
    void writeLine(final String line) {
        // println is atomic enough for this purpose: PrintStream synchronizes internally, so concurrent audit
        // writes cannot interleave mid-line.
        System.out.println(line);
    }
}
