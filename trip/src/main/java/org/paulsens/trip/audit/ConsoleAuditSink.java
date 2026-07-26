package org.paulsens.trip.audit;

/**
 * Writes audit records to stdout. Used for local runs and tests, and as the fallback whenever the CloudWatch
 * sink cannot be built or cannot deliver -- in a container stdout is captured by the awslogs driver, so records
 * still reach CloudWatch (in the application log group rather than the audit one). Never the silent option.
 */
public final class ConsoleAuditSink implements AuditSink {

    @Override
    public void write(final long epochMillis, final String line) {
        // println is atomic enough for this purpose: PrintStream synchronizes internally, so concurrent audit
        // writes cannot interleave mid-line.
        System.out.println(line);
    }
}
