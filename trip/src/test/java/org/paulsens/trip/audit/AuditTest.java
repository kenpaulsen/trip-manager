package org.paulsens.trip.audit;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Audit has no CloudWatch group configured under test (TRIP_AUDIT_LOG_GROUP is unset), so it uses the console
 * sink -- which is exactly the fallback path that must keep working when the real sink is unavailable.
 */
public class AuditTest {

    @Test
    public void logWritesFormattedRecordToStdout() {
        final String user = RandomData.genAlpha(8) + "@example.com";
        final String type = "LOGIN";
        final String msg = "User " + user + " logged in";

        final String out = captureStdout(() -> Audit.log(user, type, msg));

        Assert.assertTrue(out.contains(user), "Audit record should name the user: " + out);
        Assert.assertTrue(out.contains(type), "Audit record should name the action type: " + out);
        Assert.assertTrue(out.contains(msg), "Audit record should carry the message: " + out);
        // "<iso-8601> | user | type | msg"
        Assert.assertEquals(out.trim().split(" \\| ").length, 4, "Expected 4 pipe-delimited fields: " + out);
        Assert.assertTrue(out.startsWith("20"), "Record should start with an ISO timestamp: " + out);
    }

    @Test
    public void logNeverThrowsOnNulls() {
        // Audit sits on the login path; a bad argument must never take down the request that is being audited.
        captureStdout(() -> Audit.log(null, null, null));
    }

    @Test
    public void formatEpochSecondsRendersUtcIso() {
        Assert.assertEquals(Audit.formatEpochSeconds(0L), "1970-01-01T00:00:00Z");
        Assert.assertEquals(Audit.formatEpochSeconds(1_600_000_000L), "2020-09-13T12:26:40Z");
    }

    @Test
    public void formatEpochSecondsHandlesNull() {
        Assert.assertEquals(Audit.formatEpochSeconds(null), "");
    }

    @Test
    public void consoleSinkKeepsConcurrentRecordsIntact() throws Exception {
        // Request threads audit concurrently; records must not interleave into corrupted lines.
        final AuditSink sink = new ConsoleAuditSink();
        final int threads = 8;
        final int perThread = 50;
        final String out = captureStdout(() -> {
            final List<Thread> workers = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int id = t;
                final Thread worker = new Thread(() -> {
                    for (int i = 0; i < perThread; i++) {
                        sink.write(System.currentTimeMillis(), "line-" + id + "-" + i);
                    }
                });
                workers.add(worker);
                worker.start();
            }
            workers.forEach(w -> {
                try {
                    w.join();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
        });

        final List<String> lines = new ArrayList<>(List.of(out.split("\\R")));
        lines.removeIf(String::isBlank);
        Assert.assertEquals(lines.size(), threads * perThread, "Every record should produce exactly one line");
        Collections.sort(lines);
        Assert.assertEquals(lines.stream().distinct().count(), (long) threads * perThread, "No record lost");
        lines.forEach(l -> Assert.assertTrue(l.startsWith("line-"), "Line was corrupted by interleaving: " + l));
    }

    private static String captureStdout(final Runnable action) {
        final PrintStream original = System.out;
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
