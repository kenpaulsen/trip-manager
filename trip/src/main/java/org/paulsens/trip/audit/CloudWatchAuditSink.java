package org.paulsens.trip.audit;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsAsyncClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogStreamRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.ResourceAlreadyExistsException;

/**
 * Ships audit records to a dedicated CloudWatch log group.
 *
 * <p>Why not just a file: on Fargate the container filesystem is ephemeral, so a file-based audit log is
 * destroyed on every deploy, crash and scale event -- which is the exact opposite of what an audit trail is
 * for. A dedicated log group also lets the audit trail keep a much longer retention than the noisy
 * application log, and keeps it out of the Tomcat chatter.
 *
 * <p>Delivery is asynchronous and best-effort-but-loud: callers hand a record to a bounded queue and return
 * immediately, and a single daemon thread batches and ships them. Nothing here propagates an exception to a
 * request thread, and anything that cannot be delivered is printed to stdout (captured by the awslogs driver)
 * rather than dropped silently.
 */
public final class CloudWatchAuditSink implements AuditSink {

    /** Bounded so a CloudWatch outage degrades to stdout instead of consuming the heap. */
    private static final int QUEUE_CAPACITY = 10_000;
    /** CloudWatch caps a batch at 10,000 events / 1 MB; stay well under both. */
    private static final int MAX_BATCH = 1_000;
    private static final long FLUSH_INTERVAL_MS = 2_000L;
    private static final DateTimeFormatter STREAM_DATE =
            DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);

    private final CloudWatchLogsAsyncClient client;
    private final String logGroup;
    private final String logStream;
    private final BlockingQueue<InputLogEvent> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AuditSink fallback = new ConsoleAuditSink();
    private final Thread flusher;
    private volatile boolean running = true;

    /**
     * @param logGroup the target log group; it must already exist (the CDK creates it with its retention).
     * @throws RuntimeException if the stream cannot be created -- {@link Audit} catches this and falls back to
     *         stdout. Failing here is deliberate: a misconfigured audit trail must surface at startup, not on
     *         the day someone asks what happened.
     */
    public CloudWatchAuditSink(final String logGroup) {
        this.logGroup = logGroup;
        // One stream per JVM. Two tasks writing the same stream is legal but pointlessly confusing, and the
        // container hostname alone repeats across restarts, so add a short random suffix.
        this.logStream = STREAM_DATE.format(Instant.now()) + "/" + hostName() + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        this.client = CloudWatchLogsAsyncClient.builder()
                .region(resolveRegion())
                // Default chain: the ECS task role in AWS, ~/.aws [default] on a laptop.
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .build();

        createStream();

        this.flusher = new Thread(this::flushLoop, "trip-audit-flusher");
        this.flusher.setDaemon(true);
        this.flusher.start();
    }

    @Override
    public void write(final long epochMillis, final String line) {
        final InputLogEvent event = InputLogEvent.builder().timestamp(epochMillis).message(line).build();
        // offer(), never put(): a full queue means CloudWatch is unreachable or far behind, and blocking here
        // would stall a user's login on a logging backend.
        if (!queue.offer(event)) {
            fallback.write(epochMillis, "AUDIT-QUEUE-FULL " + line);
        }
    }

    @Override
    public void close() {
        running = false;
        flusher.interrupt();
        try {
            flusher.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        drainAndSend();       // last gasp, in case the flusher did not get there
        client.close();
    }

    private void flushLoop() {
        while (running) {
            try {
                // Block for the first event so an idle app does no work, then sweep up whatever else is queued.
                final InputLogEvent first = queue.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
                if (first != null) {
                    final List<InputLogEvent> batch = new ArrayList<>(MAX_BATCH);
                    batch.add(first);
                    queue.drainTo(batch, MAX_BATCH - 1);
                    send(batch);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ex) {
                // Never let the flusher die -- that would silently end auditing for the life of the task.
                System.out.println("AUDIT-FLUSHER-ERROR " + ex);
            }
        }
    }

    private void drainAndSend() {
        final List<InputLogEvent> remaining = new ArrayList<>();
        queue.drainTo(remaining);
        if (!remaining.isEmpty()) {
            send(remaining);
        }
    }

    private void send(final List<InputLogEvent> batch) {
        // CloudWatch rejects a batch whose events are not in chronological order; concurrent writers can queue
        // slightly out of order, so sort rather than assume.
        batch.sort(Comparator.comparingLong(InputLogEvent::timestamp));
        try {
            client.putLogEvents(PutLogEventsRequest.builder()
                    .logGroupName(logGroup)
                    .logStreamName(logStream)
                    .logEvents(batch)
                    .build()).join();
        } catch (RuntimeException ex) {
            // Delivery failed: print the records so they still land in the application log group via stdout.
            System.out.println("AUDIT-DELIVERY-FAILED (" + ex + ") -- records follow:");
            batch.forEach(e -> fallback.write(e.timestamp(), e.message()));
        }
    }

    private void createStream() {
        try {
            client.createLogStream(CreateLogStreamRequest.builder()
                    .logGroupName(logGroup)
                    .logStreamName(logStream)
                    .build()).join();
        } catch (RuntimeException ex) {
            if (!(ex.getCause() instanceof ResourceAlreadyExistsException)) {
                client.close();
                throw new IllegalStateException(
                        "Unable to create audit log stream '" + logStream + "' in group '" + logGroup + "'", ex);
            }
        }
    }

    private static String hostName() {
        // In a container this is the container id, which is the useful thing to correlate with.
        final String host = System.getenv("HOSTNAME");
        return (host == null || host.isBlank()) ? "unknown" : host.trim();
    }

    /** Mirrors DynamoPersistence's precedence so every AWS client in the app lands in the same region. */
    private static Region resolveRegion() {
        String region = System.getProperty("trip.dynamo.region");
        if (region == null || region.isBlank()) {
            region = System.getenv("TRIP_DYNAMO_REGION");
        }
        return (region == null || region.isBlank()) ? Region.US_WEST_2 : Region.of(region.trim());
    }
}
