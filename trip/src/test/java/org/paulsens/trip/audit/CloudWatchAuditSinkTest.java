package org.paulsens.trip.audit;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditEvent;
import org.paulsens.trip.model.AuditOutcome;
import org.testng.Assert;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogStreamRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogStreamResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.ResourceAlreadyExistsException;

/**
 * {@link CloudWatchAuditSink} with its log client injected.
 *
 * <p>Everything worth testing here is about what happens when CloudWatch is NOT working. The audit trail is
 * the thing you go to when something has already gone wrong, so a sink that loses records during an outage --
 * or worse, blocks a user's login waiting for one -- defeats the purpose. Hence: a bounded queue that spills to
 * stdout rather than the heap, a flusher that cannot die, and delivery failures that print the records instead
 * of dropping them.
 */
public class CloudWatchAuditSinkTest {

    private static AuditEvent event(final String message) {
        return new AuditEvent(Instant.now(), AuditAction.CONFIG, AuditOutcome.SUCCESS, "actor@test", null,
                null, null, null, message, null);
    }

    private static CloudWatchLogsClient clientThatWorks() {
        final CloudWatchLogsClient client = Mockito.mock(CloudWatchLogsClient.class);
        Mockito.when(client.createLogStream(ArgumentMatchers.any(CreateLogStreamRequest.class)))
                .thenReturn(CreateLogStreamResponse.builder().build());
        Mockito.when(client.putLogEvents(ArgumentMatchers.any(PutLogEventsRequest.class)))
                .thenReturn(PutLogEventsResponse.builder().build());
        return client;
    }

    private static PutLogEventsResponse slowOk() throws InterruptedException {
        Thread.sleep(TimeUnit.SECONDS.toMillis(2));
        return PutLogEventsResponse.builder().build();
    }

    /** Captures stdout, which is where every fallback path writes. */
    private static String captureStdout(final Runnable action) {
        final PrintStream original = System.out;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void recordsAreBatchedAndDeliveredInChronologicalOrder() throws Exception {
        final CloudWatchLogsClient client = clientThatWorks();
        final CountDownLatch delivered = new CountDownLatch(1);
        final AtomicReference<PutLogEventsRequest> sent = new AtomicReference<>();
        Mockito.when(client.putLogEvents(ArgumentMatchers.any(PutLogEventsRequest.class)))
                .thenAnswer(call -> {
                    sent.set(call.getArgument(0));
                    delivered.countDown();
                    return PutLogEventsResponse.builder().build();
                });

        final CloudWatchAuditSink sink = new CloudWatchAuditSink("/trip/audit", client);
        try {
            sink.write(event("second"));
            sink.write(event("first"));
            Assert.assertTrue(delivered.await(10, TimeUnit.SECONDS), "the flusher must deliver");
        } finally {
            sink.close();
        }

        final List<InputLogEvent> batch = sent.get().logEvents();
        for (int i = 1; i < batch.size(); i++) {
            Assert.assertTrue(batch.get(i).timestamp() >= batch.get(i - 1).timestamp(),
                    "CloudWatch rejects a batch that is not in chronological order");
        }
    }

    /** A delivery failure must print the records, not drop them. */
    @Test
    public void aDeliveryFailurePrintsTheRecordsToStdout() throws Exception {
        final CloudWatchLogsClient client = clientThatWorks();
        Mockito.when(client.putLogEvents(ArgumentMatchers.any(PutLogEventsRequest.class)))
                .thenThrow(new IllegalStateException("cloudwatch is down"));

        final String out = captureStdout(() -> {
            final CloudWatchAuditSink sink = new CloudWatchAuditSink("/trip/audit", client);
            sink.write(event("must-not-be-lost"));
            sink.close();
        });

        Assert.assertTrue(out.contains("AUDIT-DELIVERY-FAILED"), out);
        Assert.assertTrue(out.contains("must-not-be-lost"),
                "The record itself has to reach stdout, or the outage loses it entirely");
    }

    /**
     * A full queue spills to stdout rather than blocking.
     *
     * <p>{@code offer}, never {@code put}: a full queue means CloudWatch is unreachable or far behind, and
     * blocking would stall a user's login on a logging backend.
     */
    @Test
    public void afullQueueSpillsToStdoutInsteadOfBlocking() throws Exception {
        final CloudWatchLogsClient client = clientThatWorks();
        // SLOW, not stalled: the flusher blocks in the send long enough for the queue to fill, but the send
        // does eventually complete. (In production the client's apiCallTimeout bounds a send that never would.)
        Mockito.when(client.putLogEvents(ArgumentMatchers.any(PutLogEventsRequest.class)))
                .thenAnswer(call -> slowOk());

        final String out = captureStdout(() -> {
            final CloudWatchAuditSink sink = new CloudWatchAuditSink("/trip/audit", client);
            // Comfortably past the 10,000 capacity; each call must return promptly.
            final long start = System.nanoTime();
            for (int i = 0; i < 12_000; i++) {
                sink.write(event("flood-" + i));
            }
            Assert.assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(20),
                    "writes must never block on the logging backend");
            sink.close();
        });

        Assert.assertTrue(out.contains("AUDIT-QUEUE-FULL"),
                "Once the bounded queue is full the overflow has to go to stdout");
    }

    /** An existing stream is the normal case on a restart, and must not be treated as a failure. */
    @Test
    public void anAlreadyExistingStreamIsNotAFailure() throws Exception {
        final CloudWatchLogsClient client = clientThatWorks();
        Mockito.when(client.createLogStream(ArgumentMatchers.any(CreateLogStreamRequest.class)))
                .thenThrow(ResourceAlreadyExistsException.builder().message("exists").build());

        final CloudWatchAuditSink sink = new CloudWatchAuditSink("/trip/audit", client);
        sink.write(event("still works"));
        sink.close();
    }

    /**
     * Any other stream-creation failure throws, and closes the client on the way out.
     *
     * <p>Deliberate: a misconfigured audit trail must surface at startup, not on the day someone asks what
     * happened. {@code Audit} catches this and falls back to stdout.
     */
    @Test
    public void aStreamThatCannotBeCreatedFailsLoudlyAtStartup() {
        final CloudWatchLogsClient client = clientThatWorks();
        Mockito.when(client.createLogStream(ArgumentMatchers.any(CreateLogStreamRequest.class)))
                .thenThrow(new IllegalStateException("access denied"));

        Assert.assertThrows(IllegalStateException.class,
                () -> new CloudWatchAuditSink("/trip/audit", client));
        Mockito.verify(client).close();
    }

    @Test
    public void closeDrainsWhatIsStillQueued() throws Exception {
        final CloudWatchLogsClient client = clientThatWorks();
        final AtomicReference<PutLogEventsRequest> sent = new AtomicReference<>();
        Mockito.when(client.putLogEvents(ArgumentMatchers.any(PutLogEventsRequest.class)))
                .thenAnswer(call -> {
                    sent.compareAndSet(null, call.getArgument(0));
                    return PutLogEventsResponse.builder().build();
                });

        final CloudWatchAuditSink sink = new CloudWatchAuditSink("/trip/audit", client);
        sink.write(event("queued-at-shutdown"));
        sink.close();

        Assert.assertNotNull(sent.get(), "close() must flush what the flusher had not sent yet");
        Mockito.verify(client).close();
    }
}
