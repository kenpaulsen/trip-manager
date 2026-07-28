package org.paulsens.trip.action;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * The email audit record must name who asked for the mail.
 *
 * <p>It did not, for every email the system sent between the Phase 10 rework and 2026-07-28 -- mail merges
 * included. Zero of the EMAIL records written in that window carried an actor.
 *
 * <p>The cause is invisible at the call site. {@code MailCommands.send()} returns the SES client's
 * {@link CompletableFuture} with the audit written in {@code thenApply}, so the record is built on the SDK's
 * completion thread. {@code AuditActor.current()} reads {@code FacesContext}, which is a ThreadLocal, so off
 * the request thread it truthfully reports that nobody is signed in. The line read exactly like the correct
 * line used everywhere else; only the thread it ran on was different.
 *
 * <p>So the property worth pinning is not "the audit names an actor" -- it is that the actor <b>survives a
 * thread hop</b>. These tests deliberately invoke the logging on another thread: capture on the request thread
 * or the record is anonymous, and no amount of reading the call site reveals which one is happening.
 */
public class MailAuditActorTest {

    private final MailCommands mail = new MailCommands();

    @BeforeClass
    public void warmUpAudit() {
        // Audit's class initializer announces its sink; keep that banner out of the captures below.
        org.paulsens.trip.audit.Audit.builder(AuditAction.UNKNOWN, AuditOutcome.UNKNOWN)
                .message("warm-up").build();
    }

    @Test
    public void aSuccessfulSendNamesTheActorEvenWhenLoggedOffTheRequestThread() throws Exception {
        final AuditActor actor = new AuditActor("admin@example.com", "actor-id");

        final String out = captureStdoutOnAnotherThread(
                () -> mail.logAndReturn(actor, "ok", "traveller@example.com", "Email 'Receipt' sent."));

        Assert.assertTrue(out.contains("admin@example.com"),
                "The EMAIL record must name who sent it, not just who received it: " + out);
        Assert.assertTrue(out.contains("target=person:traveller@example.com"),
                "The recipient is the TARGET, not the actor: " + out);
        Assert.assertTrue(out.contains("EMAIL"), out);
    }

    @Test
    public void aFailedSendAlsoNamesTheActor() throws Exception {
        // The failure path runs in exceptionally(), which is just as far from the request thread.
        final AuditActor actor = new AuditActor("admin@example.com", "actor-id");

        final String out = captureStdoutOnAnotherThread(
                () -> mail.logException(actor, "traveller@example.com", new RuntimeException("boom")));

        Assert.assertTrue(out.contains("admin@example.com"), "A failed send must still name the actor: " + out);
        Assert.assertTrue(out.contains("outcome=FAILURE"), out);
    }

    @Test
    public void anUnknownActorIsRecordedRatherThanFailing() throws Exception {
        // Genuinely unattributable mail exists -- a background job, a flow with no session. It must still
        // produce a record; losing the event entirely is worse than losing the name.
        final String out = captureStdoutOnAnotherThread(
                () -> mail.logAndReturn(new AuditActor(null, null), "ok", "t@example.com", "sent"));

        Assert.assertTrue(out.contains("EMAIL"), "An anonymous send must still be recorded: " + out);
    }

    /**
     * Runs the action on a DIFFERENT thread, which is the whole point: {@code FacesContext} is a ThreadLocal,
     * so anything resolving the actor inside the action rather than accepting it as an argument comes back
     * empty here.
     */
    private static String captureStdoutOnAnotherThread(final Runnable action) throws Exception {
        final PrintStream original = System.out;
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(action).get();
        } finally {
            worker.shutdownNow();
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
