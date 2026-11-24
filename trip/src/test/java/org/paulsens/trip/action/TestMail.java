package org.paulsens.trip.action;

import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/** Mail stand-ins for tests of the code that decides WHAT to send; nothing here reaches SES. */
public final class TestMail {

    private TestMail() {
    }

    /**
     * A mocked {@link MailCommands} that reports an org's managed template as sent whenever it is handed a
     * template id, a recipient and a From. A send missing any of the three still answers false, as an
     * unstubbed mock does, so "no From, no mail" stays a failing send. A fresh mock per call.
     */
    public static MailCommands accepting() {
        final MailCommands mail = Mockito.mock(MailCommands.class);
        Mockito.when(mail.sendManagedTemplateForOrg(ArgumentMatchers.anyString(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.anyMap(), ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any())).thenReturn(true);
        return mail;
    }
}
