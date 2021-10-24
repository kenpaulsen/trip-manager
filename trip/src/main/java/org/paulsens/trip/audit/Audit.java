package org.paulsens.trip.audit;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class Audit {
    public static final ZoneId ZONE_ID = ZoneId.of("UTC");
    private static final Audit INSTANCE = new Audit();

    private final PrintWriter printWriter;

    private Audit() {
        // Determine log file name
        final String logDir = "logs";
        final String auditLogFile = logDir + "/trip-audit.log";
System.out.println("Audit log file location: " + auditLogFile);
        PrintWriter pw = null;
        try {
            pw = new PrintWriter(new FileWriter(auditLogFile, true));
        } catch (IOException ex) {
            ex.printStackTrace();
        }
        this.printWriter = (pw == null) ? new PrintWriter(System.out) : pw;
    }

    /**
     * Logs a message in the audit log file. This is intended to record key events so a traceable history of what
     * happen exists in case there is any question about what happened.
     *
     * @param user  The UserId who initiated this action.
     * @param type  The type of action, for example: "LOGIN"
     * @param msg   The message to display.
     */
    public static void log(final String user, final String type, final String msg) {
        final String logDate = OffsetDateTime.now(ZONE_ID).format(DateTimeFormatter.ISO_INSTANT);
        INSTANCE.printWriter.printf("%s | %s | %s | %s\n", logDate, user, type, msg);
        INSTANCE.printWriter.flush();
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
