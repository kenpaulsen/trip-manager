package org.paulsens.trip.audit;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AuditEvent;

/**
 * Writes audit records to the {@code audit} DynamoDB table, which is what the admin page reads.
 *
 * <p>This is the INDEX, not the ledger. CloudWatch keeps the tamper-resistant copy: its grant is append-only
 * because the API has no verb that rewrites a delivered log event, whereas a table the app can write is a table
 * the app could in principle overwrite. If the two ever disagree, CloudWatch is the one to believe. That is why
 * a failure here is survivable -- {@link CompositeAuditSink} keeps going, and the record is still in the ledger.
 *
 * <p>Delivery is asynchronous for the same reason as the CloudWatch sink: callers are request threads in the
 * middle of a login or a save, and a DynamoDB round trip -- with a conditional-write retry behind it -- has no
 * business sitting in that path.
 */
public final class DynamoAuditSink implements AuditSink {

    /** Bounded, so a DynamoDB outage degrades to stdout instead of consuming the heap. */
    private static final int QUEUE_CAPACITY = 10_000;
    private static final long POLL_INTERVAL_MS = 500L;
    private static final long DRAIN_TIMEOUT_SECONDS = 5L;

    private final BlockingQueue<AuditEvent> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final ConsoleAuditSink fallback = new ConsoleAuditSink();
    private final Thread writer;
    private volatile boolean running = true;

    public DynamoAuditSink() {
        this.writer = new Thread(this::writeLoop, "trip-audit-dynamo");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    @Override
    public void write(final AuditEvent event) {
        // offer(), never put(): a full queue means the table is unreachable or far behind, and blocking here
        // would stall a user's request on a logging backend.
        if (!queue.offer(event)) {
            fallback.writeLine("AUDIT-DYNAMO-QUEUE-FULL " + AuditSink.format(event));
        }
    }

    @Override
    public void close() {
        running = false;
        writer.interrupt();
        try {
            writer.join(TimeUnit.SECONDS.toMillis(DRAIN_TIMEOUT_SECONDS));
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        drain();
    }

    private void writeLoop() {
        while (running) {
            try {
                final AuditEvent event = queue.poll(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
                if (event != null) {
                    store(event);
                }
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (final RuntimeException ex) {
                // Never let this thread die: that would silently end indexing for the life of the task.
                fallback.writeLine("AUDIT-DYNAMO-WRITER-ERROR " + ex);
            }
        }
    }

    private void drain() {
        AuditEvent event;
        while ((event = queue.poll()) != null) {
            try {
                store(event);
            } catch (final RuntimeException ex) {
                fallback.writeLine("AUDIT-DYNAMO-DRAIN-FAILED " + AuditSink.format(event));
            }
        }
    }

    private void store(final AuditEvent event) {
        final boolean stored = DAO.getInstance().saveAuditEvent(event);
        if (!stored) {
            // Print it so the record still reaches the application log group; the ledger already has it, but a
            // record missing from the index is a record the admin page will never show.
            fallback.writeLine("AUDIT-NOT-INDEXED " + AuditSink.format(event));
        }
    }
}
