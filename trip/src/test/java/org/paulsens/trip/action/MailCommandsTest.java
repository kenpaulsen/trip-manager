package org.paulsens.trip.action;

import java.io.FileNotFoundException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.SendEmailResponse;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

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
    void sendTemplateFile_existingTemplate_isFoundAndLoaded() {
        // Passing an empty "to" list keeps the SES client from being invoked, so this exercises
        // only the template lookup path. Success here means the .tpl file was located on the classpath.
        final List<SendEmailResponse> result = new MailCommands().sendTemplateFile(
                "from@example.com", List.of(), "", "reply@example.com", "Subject", "test").join();
        assertNotNull(result);
        assertEquals(result, List.of());
    }

    @Test
    void sendTemplateFile_missingTemplate_failsWithFileNotFound() {
        try {
            new MailCommands().sendTemplateFile(
                    "from@example.com", List.of(), "", "reply@example.com", "Subject",
                    "this-template-does-not-exist").join();
            fail("Expected the future to complete exceptionally with FileNotFoundException");
        } catch (final CompletionException ex) {
            assertTrue(ex.getCause() instanceof FileNotFoundException,
                    "Expected FileNotFoundException, got: " + ex.getCause());
        }
    }
}
