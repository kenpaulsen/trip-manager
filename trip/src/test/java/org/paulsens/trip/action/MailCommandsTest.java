package org.paulsens.trip.action;

import java.util.Collection;
import java.util.List;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.ses.model.Destination;

import static org.testng.Assert.assertEquals;

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
}