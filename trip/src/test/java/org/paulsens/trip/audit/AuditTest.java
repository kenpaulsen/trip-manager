package org.paulsens.trip.audit;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditEvent;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.site.SiteContext;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Audit has no CloudWatch group configured under test (TRIP_AUDIT_LOG_GROUP is unset), so it uses the console
 * sink -- which is exactly the fallback path that must keep working when the real sink is unavailable.
 */
public class AuditTest {

    /**
     * Touch Audit once before anything captures stdout. Its class initializer announces which sink it chose,
     * and that one-off banner would otherwise be counted as a record by the line-counting tests below.
     */
    @BeforeClass
    public void warmUpAudit() {
        Audit.builder(AuditAction.UNKNOWN, AuditOutcome.UNKNOWN).message("warm-up").build();
    }

    @Test
    public void logWritesFormattedRecordToStdout() {
        final String user = RandomData.genAlpha(8) + "@example.com";
        final String type = "LOGIN";
        final String msg = "User " + user + " logged in";

        final String out = captureStdout(() -> Audit.log(user, type, msg));

        // Assert on THIS record's line, not on everything captured: stdout is shared, and the audit sink's
        // writer thread can emit while a test holds the capture. Asserting on the whole buffer made this
        // fail whenever another test happened to audit at the wrong moment.
        final String line = java.util.Arrays.stream(out.split("\\R"))
                .filter(l -> l.contains(user))
                .findFirst()
                .orElse("");
        Assert.assertFalse(line.isEmpty(), "Audit record should name the user: " + out);
        Assert.assertTrue(line.contains(type), "Audit record should name the action type: " + line);
        Assert.assertTrue(line.contains(msg), "Audit record should carry the message: " + line);
        // "<iso-8601> | user | type | msg"
        Assert.assertEquals(line.split(" \\| ").length, 4, "Expected 4 pipe-delimited fields: " + line);
        Assert.assertTrue(line.startsWith("20"), "Record should start with an ISO timestamp: " + line);
    }

    private static final SiteContext ACME_SITE =
            SiteContext.org(Organization.Id.from("a17c3b52-2e84-4d0b-9c66-08d94f2e6b73"), "acme", "acme.localhost");

    private static AuditEvent builtOn(final SiteContext site, final String explicitOrg) throws Exception {
        return ScopedValue.where(RequestContext.SCOPE, RequestContext.of(null, null, site))
                .call(() -> Audit.builder(AuditAction.LOGIN, AuditOutcome.SUCCESS)
                        .actor("a@example.com", "id-1").org(explicitOrg).message("m").build());
    }

    @Test
    public void anEventOnAnOrgSiteBelongsToThatOrgUnlessTheWriterSaysOtherwise() throws Exception {
        Assert.assertEquals(builtOn(ACME_SITE, null).getOrgId(), ACME_SITE.orgId().getValue(),
                "everything done on an org's site lands in its trail");
        Assert.assertEquals(builtOn(ACME_SITE, "other-org").getOrgId(), "other-org",
                "the entity's org outranks the site's: an admin recording another tenant's row");
        Assert.assertNull(builtOn(SiteContext.shared("localhost"), null).getOrgId(),
                "a shared host stamps nothing: site-level records belong to nobody");
        Assert.assertNull(Audit.builder(AuditAction.LOGIN, AuditOutcome.SUCCESS).message("m").build().getOrgId(),
                "and neither does the unbound system context");
        Assert.assertEquals(builtOn(SiteContext.shared("localhost"), " ").getOrgId(), null,
                "a blank explicit org leaves the fallback in charge");
    }

    @Test
    public void theConsoleLineNamesTheOrgWhenThereIsOne() throws Exception {
        Assert.assertTrue(AuditSink.format(builtOn(ACME_SITE, null)).endsWith(
                " | org=" + ACME_SITE.orgId().getValue()));
        Assert.assertFalse(AuditSink.format(builtOn(SiteContext.shared("localhost"), null)).contains("org="),
                "site-level records keep the historical 4-field line");
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
                        sink.write(Audit.builder(AuditAction.LOGIN, AuditOutcome.SUCCESS)
                                .actor("line-" + id + "-" + i + "@example.com", null)
                                .message("concurrent")
                                .build());
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

        // Count only THIS test's records. Other threads share stdout -- the audit sink's writer and anything
        // logging through logback -- so counting every captured line made this flaky the moment the DynamoDB
        // sink became always-on. The property under test is that none of these 400 records is lost or
        // corrupted by interleaving, which is unaffected by unrelated output.
        final List<String> lines = new ArrayList<>(List.of(out.split("\\R")));
        lines.removeIf(line -> !line.contains("| line-"));
        Assert.assertEquals(lines.size(), threads * perThread, "Every record should produce exactly one line");
        Collections.sort(lines);
        Assert.assertEquals(lines.stream().distinct().count(), (long) threads * perThread, "No record lost");
        lines.forEach(l -> Assert.assertTrue(l.contains("| line-") && l.contains("| LOGIN |"),
                "Line was corrupted by interleaving: " + l));
    }

    @Test
    public void typedRecordCarriesActorTargetAndOutcome() {
        final String actor = RandomData.genAlpha(8) + "@example.com";
        final String target = RandomData.genAlpha(8) + "@example.com";

        final String out = captureStdout(() -> Audit.builder(AuditAction.PERSON, AuditOutcome.SUCCESS)
                .actor(actor, "actor-id")
                .targetPerson(target, "target-id")
                .message("Edited the record")
                .log());

        Assert.assertTrue(out.contains(actor), "Should name the actor: " + out);
        Assert.assertTrue(out.contains("PERSON"), "Should name the action: " + out);
        Assert.assertTrue(out.contains("outcome=SUCCESS"), "Should record the outcome: " + out);
        Assert.assertTrue(out.contains("target=person:" + target), "Should record the target: " + out);
    }

    @Test
    public void legacyTypeStringsResolveToActions() {
        // Five years of history uses these spellings; they must not all collapse into UNKNOWN.
        Assert.assertEquals(AuditAction.from("LOGIN"), AuditAction.LOGIN);
        Assert.assertEquals(AuditAction.from("saveTx"), AuditAction.TRANSACTION);
        Assert.assertEquals(AuditAction.from("PWReset"), AuditAction.PASSWORD_RESET);
        Assert.assertEquals(AuditAction.from("REG"), AuditAction.REGISTRATION);
        Assert.assertEquals(AuditAction.from("Register"), AuditAction.REGISTRATION, "the one-off legacy spelling");
        Assert.assertEquals(AuditAction.from("login"), AuditAction.LOGIN, "resolution is case-insensitive");
    }

    @Test
    public void unrecognisedTypeIsRecordedRatherThanDropped() {
        // Losing an audit record is worse than storing one with a vague label, so the original text survives.
        final String out = captureStdout(() -> Audit.log("someone@example.com", "NoSuchType", "did a thing"));

        Assert.assertTrue(out.contains("UNKNOWN"), "Unmapped type should record as UNKNOWN: " + out);
        Assert.assertTrue(out.contains("NoSuchType"), "Original type text must be preserved: " + out);
        Assert.assertTrue(out.contains("did a thing"), "Message must survive: " + out);
    }

    @Test
    public void failureIsInferredFromLegacyProseButSuccessIsNeverAssumed() {
        // Legacy records encode failure in words. Inferring FAILURE is safe; inferring SUCCESS would be
        // inventing history, so anything ambiguous stays UNKNOWN.
        Assert.assertEquals(Audit.inferOutcome("Login Failed!"), AuditOutcome.FAILURE);
        Assert.assertEquals(Audit.inferOutcome("Unable to send email: boom"), AuditOutcome.FAILURE);
        Assert.assertEquals(Audit.inferOutcome("User bob@example.com logged in"), AuditOutcome.UNKNOWN);
        Assert.assertEquals(Audit.inferOutcome(null), AuditOutcome.UNKNOWN);
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
