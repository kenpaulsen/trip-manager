package org.paulsens.trip.audit;

import java.time.Duration;
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
import org.paulsens.trip.model.AuditEvent;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
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
    /** Upper bound on one delivery attempt; a slower CloudWatch is treated as a failed delivery, not waited on. */
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(30);
    private static final DateTimeFormatter STREAM_DATE =
            DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);

    private final CloudWatchLogsClient client;
    private final String logGroup;
    private final String logStream;
    private final BlockingQueue<InputLogEvent> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final ConsoleAuditSink fallback = new ConsoleAuditSink();
    private final Thread flusher;
    private volatile boolean running = true;
    /**
     * Whether {@code createLogStream} has succeeded. False after a TRANSIENT failure at construction, in which
     * case every delivery first retries the create; see {@link #ensureStream()}. Only the flusher (and
     * {@code close()}, after the flusher has stopped) reads or writes it, so volatile is enough.
     */
    private volatile boolean streamReady;

    /**
     * @param logGroup the target log group; it must already exist (the CDK creates it with its retention).
     * @throws RuntimeException if the stream cannot be created for a reason a retry will not cure (the group
     *         does not exist, the role may not write to it) -- {@link Audit} catches this and falls back to
     *         stdout. Failing here is deliberate: a misconfigured audit trail must surface at startup, not on
     *         the day someone asks what happened. A TRANSIENT failure (a timeout, a connection reset, a 5xx)
     *         does NOT throw: the sink comes up with the stream pending and creates it ahead of the first
     *         delivery that succeeds. One slow cold call on 2026-09-01 used to downgrade the whole task to
     *         stdout for its lifetime (trip-manager#39).
     */
    public CloudWatchAuditSink(final String logGroup) {
        this(logGroup, CloudWatchLogsClient.builder()
                .region(resolveRegion())
                // Default chain: the ECS task role in AWS, ~/.aws [default] on a laptop.
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                // The client-level bound replaces the CF-era per-call orTimeout: send() is reached from
                // close() as well as the flusher, and an unresponsive endpoint must be a failed delivery
                // (stdout fallback), never a wedged flusher or a blocked container shutdown.
                .overrideConfiguration(o -> o.apiCallTimeout(SEND_TIMEOUT))
                .build());
    }

    /**
     * @param client the log client to publish through. Package-private so a test can supply one: the batching,
     *               the bounded queue and the stdout fallback are the parts worth verifying, and none of them
     *               are reachable through a constructor that builds its own AWS client.
     */
    CloudWatchAuditSink(final String logGroup, final CloudWatchLogsClient client) {
        this.logGroup = logGroup;
        // One stream per JVM. Two tasks writing the same stream is legal but pointlessly confusing, and the
        // container hostname alone repeats across restarts, so add a short random suffix.
        this.logStream = STREAM_DATE.format(Instant.now()) + "/" + hostName() + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        this.client = client;

        streamReady = createStream(true);

        this.flusher = new Thread(this::flushLoop, "trip-audit-flusher");
        this.flusher.setDaemon(true);
        this.flusher.start();
    }

    @Override
    public void write(final AuditEvent event) {
        final long epochMillis = event.getTimestamp().toEpochMilli();
        final String line = AuditSink.format(event);
        final InputLogEvent logEvent = InputLogEvent.builder().timestamp(epochMillis).message(line).build();
        // offer(), never put(): a full queue means CloudWatch is unreachable or far behind, and blocking here
        // would stall a user's login on a logging backend.
        if (!queue.offer(logEvent)) {
            fallback.writeLine("AUDIT-QUEUE-FULL " + line);
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
        if (!ensureStream()) {
            System.out.println("AUDIT-DELIVERY-FAILED (stream still pending) -- records follow:");
            batch.forEach(e -> fallback.writeLine(e.message()));
            return;
        }
        try {
            // Blocking, and bounded by the client's apiCallTimeout (see the constructor): a delivery that
            // cannot finish inside the bound takes the same fallback as any other failure.
            client.putLogEvents(PutLogEventsRequest.builder()
                    .logGroupName(logGroup)
                    .logStreamName(logStream)
                    .logEvents(batch)
                    .build());
        } catch (RuntimeException ex) {
            // Delivery failed: print the records so they still land in the application log group via stdout.
            System.out.println("AUDIT-DELIVERY-FAILED (" + ex + ") -- records follow:");
            batch.forEach(e -> fallback.writeLine(e.message()));
        }
    }

    /**
     * Retries a deferred stream creation ahead of a delivery. Cheap once it has succeeded (one volatile read);
     * until then each batch costs one more create attempt, bounded by the client's apiCallTimeout like the
     * delivery itself, and a batch that still has no stream takes the stdout fallback rather than waiting.
     *
     * @return whether the stream exists, so the caller may deliver into it.
     */
    private boolean ensureStream() {
        if (!streamReady) {
            streamReady = createStream(false);
            if (streamReady) {
                System.out.println("AUDIT-STREAM-READY: audit log stream '" + logStream + "' created on retry");
            }
        }
        return streamReady;
    }

    /**
     * @param failFast whether a non-transient failure should close the client and throw (construction) or
     *                 merely report false (a retry from the flusher, where there is nobody to throw to).
     * @return whether the stream now exists.
     */
    private boolean createStream(final boolean failFast) {
        try {
            client.createLogStream(CreateLogStreamRequest.builder()
                    .logGroupName(logGroup)
                    .logStreamName(logStream)
                    .build());
            return true;
        } catch (ResourceAlreadyExistsException ex) {
            return true;
        } catch (RuntimeException ex) {
            if (isTransient(ex)) {
                // Greppable: a post-deploy check looks for this line and for AUDIT-STREAM-READY after it.
                System.out.println("AUDIT-STREAM-PENDING: could not create audit log stream '" + logStream
                        + "' in group '" + logGroup + "', will retry before each delivery. Cause: " + ex);
                return false;
            }
            if (!failFast) {
                System.out.println("AUDIT-STREAM-PENDING: create of '" + logStream + "' refused: " + ex);
                return false;
            }
            client.close();
            throw new IllegalStateException(
                    "Unable to create audit log stream '" + logStream + "' in group '" + logGroup + "'", ex);
        }
    }

    /**
     * Whether a failed AWS call is the kind another attempt may get past. Client-side failures (timeouts,
     * connection resets, the SDK never reaching the service) and throttled or 5xx service answers are;
     * everything else -- a missing group, a denied role, an unexpected exception type -- is a misconfiguration
     * that retrying would only hide.
     */
    static boolean isTransient(final RuntimeException ex) {
        if (ex instanceof SdkClientException) {
            return true;
        }
        if (ex instanceof AwsServiceException service) {
            return service.isThrottlingException() || service.statusCode() == 429 || service.statusCode() >= 500;
        }
        return false;
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
