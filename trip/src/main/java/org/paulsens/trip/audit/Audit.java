package org.paulsens.trip.audit;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditEvent;
import org.paulsens.trip.model.AuditOutcome;

/**
 * Records key events so a traceable history exists in case there is ever a question about what happened.
 *
 * <p>Records go to a dedicated CloudWatch log group when {@code TRIP_AUDIT_LOG_GROUP} names one, and to stdout
 * otherwise (local runs, tests, and as the fallback if CloudWatch cannot be reached).
 *
 * <p>This used to append to {@code logs/trip-audit.log}. That quietly stopped being an audit trail when the app
 * moved into a container: the file lives on the task's ephemeral disk, so every deploy, crash or scale event
 * destroyed the history -- production was found holding a single day of records. Nothing that claims to be an
 * audit log may depend on container-local storage.
 */
public final class Audit {
    public static final ZoneId ZONE_ID = ZoneId.of("UTC");
    /** Names the CloudWatch log group to ship to; unset means stdout (local/tests). Set by the task definition. */
    static final String LOG_GROUP_VAR = "TRIP_AUDIT_LOG_GROUP";

    private static final Audit INSTANCE = new Audit();

    private final AuditSink sink;

    private Audit() {
        this.sink = buildSink();
        Runtime.getRuntime().addShutdownHook(new Thread(sink::close, "trip-audit-shutdown"));
    }

    /**
     * Builds the sink set.
     *
     * <p>Deployed, records go to BOTH CloudWatch and DynamoDB, and the two are not redundant copies of each
     * other -- they have different properties on purpose. CloudWatch is the ledger: its grant is append-only
     * because the API has no verb that rewrites a delivered event, so it is the copy that survives a bug (or a
     * person) that would rewrite the table. DynamoDB is the index: it is what the admin page can actually query.
     *
     * <p>Locally, and whenever the table is switched off, stdout alone -- which the awslogs driver still
     * captures in a container, so nothing is ever merely discarded.
     */
    private static AuditSink buildSink() {
        final List<AuditSink> sinks = new ArrayList<>();

        final String logGroup = logGroupName();
        if (logGroup == null) {
            System.out.println("Audit: not writing to CloudWatch (" + LOG_GROUP_VAR + " is not set)");
        } else {
            try {
                sinks.add(new CloudWatchAuditSink(logGroup));
                System.out.println("Audit: writing to CloudWatch log group '" + logGroup + "'");
            } catch (final RuntimeException ex) {
                // Loud, not silent: auditing still works via stdout, but somebody needs to know the dedicated
                // trail is not being written.
                System.out.println("Audit: FAILED to open CloudWatch log group '" + logGroup
                        + "', falling back to stdout. Cause: " + ex);
                ex.printStackTrace();
            }
        }

        if (dynamoIndexEnabled()) {
            sinks.add(new DynamoAuditSink());
            System.out.println("Audit: indexing to the DynamoDB audit table");
        }

        if (sinks.isEmpty()) {
            System.out.println("Audit: writing to stdout only");
            return new ConsoleAuditSink();
        }
        // Always keep stdout alongside the real sinks. It costs nothing, and it is what makes a delivery
        // failure visible instead of a record quietly vanishing.
        sinks.add(new ConsoleAuditSink());
        return new CompositeAuditSink(sinks);
    }

    /**
     * Whether to index into the audit table. On unless explicitly switched off.
     *
     * <p>There is deliberately no environment sniffing here any more. WHERE the records land is already
     * decided, once, by which {@link org.paulsens.trip.dynamo.Persistence} the {@code DAO} chose: DynamoDB when
     * deployed, an in-memory table under local mode and tests. Asking a second time, in a different way, only
     * created opportunities to disagree with that answer.
     *
     * <p>Both earlier attempts got it wrong. {@code FakeData.isLocal()} reports local whenever there is no
     * {@code FacesContext}, and this runs in a class initializer on whichever thread audits first -- so a
     * background thread getting there first would disable indexing for the life of the JVM. Keying off the
     * CloudWatch log group fixed that but left local mode unable to read back what it had written, which is
     * why the admin page could not be tested end to end.
     *
     * <p>Always-on means a laptop and a webtest exercise the same code path production does, differing only in
     * where the bytes go. {@value #DYNAMO_INDEX_VAR} still forces it off for anything that needs that.
     */
    private static boolean dynamoIndexEnabled() {
        final String explicit = System.getProperty(DYNAMO_INDEX_PROP, System.getenv(DYNAMO_INDEX_VAR));
        return (explicit == null || explicit.isBlank()) || Boolean.parseBoolean(explicit.trim());
    }

    static final String DYNAMO_INDEX_VAR = "TRIP_AUDIT_DYNAMO";
    static final String DYNAMO_INDEX_PROP = "trip.audit.dynamo";

    private static String logGroupName() {
        String group = System.getProperty("trip.audit.log.group");
        if (group == null || group.isBlank()) {
            group = System.getenv(LOG_GROUP_VAR);
        }
        return (group == null || group.isBlank()) ? null : group.trim();
    }

    /**
     * Records a fully-formed event. The typed entry point -- prefer this everywhere.
     *
     * <p>Never throws and never blocks on the audit backend: callers are request threads in the middle of a
     * login or a save, and failing a user's request because a logging backend hiccupped is worse than the
     * record being late.
     *
     * @param event the event to record; ignored if null rather than throwing.
     */
    public static void log(final AuditEvent event) {
        if (event == null) {
            return;
        }
        INSTANCE.sink.write(event);
    }

    /**
     * Records an event from its parts, for Java callers that have an action constant in hand.
     *
     * @param action  what happened.
     * @param outcome whether it worked; use {@link AuditOutcome#of(boolean)} at branch points.
     * @param actor   who did it (email).
     * @param actorId who did it (person id); may be null when the caller genuinely does not know.
     * @param msg     human-readable detail.
     */
    public static void log(final AuditAction action, final AuditOutcome outcome, final String actor,
            final String actorId, final String msg) {
        log(builder(action, outcome).actor(actor, actorId).message(msg).build());
    }

    /** Starts a builder; the readable way to attach a target. */
    public static AuditEventBuilder builder(final AuditAction action, final AuditOutcome outcome) {
        return new AuditEventBuilder(action, outcome);
    }

    /**
     * The historical string-typed entry point.
     *
     * <p><b>Deprecated:</b> the untyped {@code type} argument is what let five years of history accumulate
     * {@code LOGIN}, {@code saveTx}, {@code PWReset} and a stray {@code Register} -- inconsistent enough that
     * filtering on it needs special cases. It survives because roughly half the call sites are JSFT
     * expressions in XHTML, which cannot name a Java enum constant; {@code AuditCommands} is the bridge, and
     * this overload keeps unconverted callers working meanwhile. It never drops a record: an unrecognised type
     * resolves to {@link AuditAction#UNKNOWN} with the original text preserved.
     *
     * @param user The user (email) who initiated this action.
     * @param type The type of action, for example: "LOGIN"
     * @param msg  The message to record.
     */
    @Deprecated
    public static void log(final String user, final String type, final String msg) {
        final AuditAction action = AuditAction.from(type);
        // Keep the original spelling when it could not be mapped, so nothing is silently discarded.
        final String message = (action == AuditAction.UNKNOWN && type != null && !type.isBlank())
                ? "[" + type.trim() + "] " + msg
                : msg;
        log(builder(action, inferOutcome(message)).actor(user, null).message(message).build());
    }

    /**
     * Best-effort outcome for callers that do not state one -- almost entirely the legacy string API, where
     * failure was recorded as prose ("Login Failed!", "Failed to create credentials!").
     *
     * <p>Returns {@link AuditOutcome#UNKNOWN} rather than assuming success, because recording that something
     * succeeded when the log never said so would be inventing history.
     */
    static AuditOutcome inferOutcome(final String msg) {
        if (msg == null) {
            return AuditOutcome.UNKNOWN;
        }
        final String lower = msg.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("failed") || lower.contains("failure") || lower.contains("unable to")) {
            return AuditOutcome.FAILURE;
        }
        return AuditOutcome.UNKNOWN;
    }

    public static String formatEpochSeconds(final Long epochSeconds) {
        final String result;
        if (epochSeconds == null) {
            result = "";
        } else {
            final Instant instant = Instant.ofEpochSecond(epochSeconds);
            result = OffsetDateTime.ofInstant(instant, ZONE_ID).format(DateTimeFormatter.ISO_INSTANT);
        }
        return result;
    }
}
