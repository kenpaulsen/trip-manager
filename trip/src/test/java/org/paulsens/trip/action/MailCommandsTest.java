package org.paulsens.trip.action;

import java.io.FileNotFoundException;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.List;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.SendEmailResponse;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;
import org.paulsens.trip.audit.AuditActor;

public class MailCommandsTest {

    @Test
    void emptyBCC() {
        final Collection<String> bcc = new MailCommands().splitEmail("");
        final Destination dest = Destination.builder().toAddresses("abc@123.com").bccAddresses(bcc).build();
        assertEquals(dest.bccAddresses(), List.of());
    }

    @Test
    void nullBCC() {
        final Collection<String> bcc = new MailCommands().splitEmail(null);
        final Destination dest = Destination.builder().toAddresses("abc@123.com").bccAddresses(bcc).build();
        assertEquals(dest.bccAddresses(), List.of());
    }

    @Test
    void unusableRecipientsAreDroppedFromTheList() {
        // Real data: some people have no email address and the field holds a bare name. SES rejects the WHOLE
        // request when any destination is malformed, so without this filter one such person in a mail merge
        // costs every recipient after them their mail.
        final Collection<String> to = new MailCommands()
                .splitEmail("good@example.com, joe.smith, also.good@example.org, bad@nodot");
        assertEquals(to, List.of("good@example.com", "also.good@example.org"));
    }

    @Test
    void theDisplayNameFormSurvivesTheFilter() {
        // formatEmail() produces "Pref Last <addr>", which contains spaces and angle brackets -- validating the
        // raw string rather than the bare address would silently drop every normal recipient.
        final Collection<String> to = new MailCommands().splitEmail("Joe Smith <joe@example.com>");
        assertEquals(to, List.of("Joe Smith <joe@example.com>"));
    }

    @Test
    void allRecipientsUnusableMeansNoSendRatherThanAnError() {
        // A missing address is a data problem, not a transient one, so this is a logged no-op: throwing would
        // fail a sendTemplate fan-out and lose the recipients that DID work.
        final SendEmailResponse response = new MailCommands()
                .send("from@example.com", "joe.smith", null, "reply@example.com", "subj", "body",
                        AuditActor.system());
        assertNotNull(response, "An unsendable request must complete, not fail");
    }

    @Test
    void sendTemplateFile_existingTemplate_isFoundAndLoaded() {
        // Passing an empty "to" list keeps the SES client from being invoked, so this exercises
        // only the template lookup path. Success here means the .tpl file was located on the classpath.
        final List<SendEmailResponse> result = new MailCommands().sendTemplateFile(
                "from@example.com", List.of(), "", "reply@example.com", "Subject", "test");
        assertNotNull(result);
        assertEquals(result, List.of());
    }

    @Test
    void sendTemplateFile_missingTemplate_failsWithFileNotFound() {
        try {
            new MailCommands().sendTemplateFile(
                    "from@example.com", List.of(), "", "reply@example.com", "Subject",
                    "this-template-does-not-exist");
            fail("Expected UncheckedIOException carrying FileNotFoundException");
        } catch (final UncheckedIOException ex) {
            assertTrue(ex.getCause() instanceof FileNotFoundException,
                    "Expected FileNotFoundException, got: " + ex.getCause());
        }
    }
}
