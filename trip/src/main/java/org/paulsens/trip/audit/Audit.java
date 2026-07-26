package org.paulsens.trip.audit;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

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
public class Audit {
    public static final ZoneId ZONE_ID = ZoneId.of("UTC");
    /** Names the CloudWatch log group to ship to; unset means stdout (local/tests). Set by the task definition. */
    static final String LOG_GROUP_VAR = "TRIP_AUDIT_LOG_GROUP";

    private static final Audit INSTANCE = new Audit();

    private final AuditSink sink;

    private Audit() {
        this.sink = buildSink();
        Runtime.getRuntime().addShutdownHook(new Thread(sink::close, "trip-audit-shutdown"));
    }

    private static AuditSink buildSink() {
        final String logGroup = logGroupName();
        if (logGroup == null) {
            System.out.println("Audit: writing to stdout (" + LOG_GROUP_VAR + " is not set)");
            return new ConsoleAuditSink();
        }
        try {
            final AuditSink sink = new CloudWatchAuditSink(logGroup);
            System.out.println("Audit: writing to CloudWatch log group '" + logGroup + "'");
            return sink;
        } catch (RuntimeException ex) {
            // Loud, not silent: auditing still works via stdout, but somebody needs to know the dedicated
            // trail is not being written.
            System.out.println("Audit: FAILED to open CloudWatch log group '" + logGroup
                    + "', falling back to stdout. Cause: " + ex);
            ex.printStackTrace();
            return new ConsoleAuditSink();
        }
    }

    private static String logGroupName() {
        String group = System.getProperty("trip.audit.log.group");
        if (group == null || group.isBlank()) {
            group = System.getenv(LOG_GROUP_VAR);
        }
        return (group == null || group.isBlank()) ? null : group.trim();
    }

    /**
     * Logs a message in the audit log. This is intended to record key events so a traceable history of what
     * happened exists in case there is any question about what happened.
     *
     * <p>Never throws and never blocks on the audit backend -- callers are request threads.
     *
     * @param user  The UserId who initiated this action.
     * @param type  The type of action, for example: "LOGIN"
     * @param msg   The message to display.
     */
    public static void log(final String user, final String type, final String msg) {
        final Instant now = Instant.now();
        final String logDate = OffsetDateTime.ofInstant(now, ZONE_ID).format(DateTimeFormatter.ISO_INSTANT);
        INSTANCE.sink.write(now.toEpochMilli(), String.format("%s | %s | %s | %s", logDate, user, type, msg));
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
