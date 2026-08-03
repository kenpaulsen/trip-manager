package org.paulsens.trip.action;

import org.paulsens.trip.dynamo.FakeData;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.paulsens.trip.audit.AuditActor;

/**
 * Local mode must not send real email.
 *
 * <p>It did. A webtest that registers a fake pilgrim delivered a genuine "just registered for the 'Spring Demo
 * Trip' trip" notification to a real inbox, sent through SES with the laptop's own AWS credentials. Local mode
 * fakes the DAO and the cache; SES had no stand-in at all, so it was the one dependency that stayed live while
 * everything around it went fake — and the test data being fake says nothing about the addresses in it.
 *
 * <p>The guard sits at the single point every send funnels through, so this covers mail merges, chat mentions,
 * the daily digest and registration notifications alike. The unit suite runs in local mode, which is what makes
 * this assertable at all: without the guard the call below would reach for AWS.
 */
public class MailLocalModeTest {

    private final MailCommands mail = new MailCommands();

    @Test
    public void aSendInLocalModeReachesNobody() {
        Assert.assertTrue(FakeData.isLocal(), "the unit suite must run in local mode for this to mean anything");

        // A real-looking address on purpose: the bug was that fake test DATA carried real addresses.
        final var response = mail.send("Test <no-reply@visitqueenofpeace.com>", "someone@example.com", null,
                "no-reply@visitqueenofpeace.com", "Local mode must not send this", "<p>body</p>",
                AuditActor.system()).join();

        // The empty response is the local-mode stand-in; a real send comes back with an SES message id.
        Assert.assertNull(response.messageId(),
                "local mode must return the stand-in response, not the result of a real SES call");
    }
}
