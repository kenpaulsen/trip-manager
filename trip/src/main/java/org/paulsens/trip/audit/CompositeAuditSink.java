package org.paulsens.trip.audit;

import java.util.List;
import org.paulsens.trip.model.AuditEvent;

/**
 * Writes every record to several sinks.
 *
 * <p>This exists so the trail can be in two places with different properties at once: CloudWatch is the
 * immutable ledger (its grant is append-only -- there is no verb that overwrites a delivered log event), and
 * DynamoDB is the queryable index that the admin page pages through. If the two ever disagree, CloudWatch is
 * the one to believe.
 *
 * <p>One failing sink must not stop the others: a record reaching the ledger but not the index is a degraded
 * search experience, while a record reaching neither because the first sink threw is a lost audit record.
 */
public final class CompositeAuditSink implements AuditSink {

    private final List<AuditSink> sinks;
    private final ConsoleAuditSink fallback = new ConsoleAuditSink();

    public CompositeAuditSink(final List<AuditSink> sinks) {
        this.sinks = List.copyOf(sinks);
    }

    @Override
    public void write(final AuditEvent event) {
        for (final AuditSink sink : sinks) {
            try {
                sink.write(event);
            } catch (RuntimeException ex) {
                // Sinks are contractually not supposed to throw; if one does anyway, say so loudly and keep the
                // record by printing it, then carry on to the remaining sinks.
                fallback.writeLine("AUDIT-SINK-FAILED (" + sink.getClass().getSimpleName() + ": " + ex + ") "
                        + AuditSink.format(event));
            }
        }
    }

    @Override
    public void close() {
        for (final AuditSink sink : sinks) {
            try {
                sink.close();
            } catch (RuntimeException ex) {
                fallback.writeLine("AUDIT-SINK-CLOSE-FAILED (" + sink.getClass().getSimpleName() + "): " + ex);
            }
        }
    }

    /** The sinks being written to, for startup logging and tests. */
    public List<AuditSink> getSinks() {
        return sinks;
    }
}
