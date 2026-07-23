package org.paulsens.trip.security;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public class PepperTest {
    private static final String GOOD_KEY = Base64.getEncoder()
            .encodeToString("a-thirty-two-byte-pepper-key-1234".getBytes(StandardCharsets.UTF_8));

    @Test
    public void parseSecretAcceptsBareBase64AsVersionOne() {
        final Pepper pepper = Pepper.parseSecret(GOOD_KEY);
        assertEquals(pepper.currentVersion(), 1);
        assertTrue(pepper.supportsVersion(1));
    }

    @Test
    public void parseSecretAcceptsJsonWithVersionAndKey() {
        final Pepper pepper = Pepper.parseSecret("{\"version\":1,\"key\":\"" + GOOD_KEY + "\"}");
        assertEquals(pepper.currentVersion(), 1);
    }

    @Test
    public void parseSecretGivesAClearMessageWhenTheJsonKeyIsNotBase64() {
        // The exact failure the deployment hit: the shell never expanded $KEY, so the literal placeholder was stored.
        try {
            Pepper.parseSecret("{\"version\":1,\"key\":\"$KEY\"}");
            fail("a non-base64 key must be rejected");
        } catch (final IllegalStateException ex) {
            assertTrue(ex.getMessage().contains("not valid base64"), "message should name the base64 problem");
            assertTrue(ex.getMessage().contains("$KEY"), "message should point at the unexpanded placeholder cause");
        }
    }

    @Test
    public void parseSecretGivesAClearMessageWhenABareValueIsNotBase64() {
        try {
            Pepper.parseSecret("$KEY");
            fail("a non-base64 bare value must be rejected");
        } catch (final IllegalStateException ex) {
            assertTrue(ex.getMessage().contains("not valid base64"), ex.getMessage());
        }
    }

    @Test
    public void resolveWithABadDirectKeyThrowsAClearMessage() {
        System.setProperty(Pepper.KEY_PROP, "$KEY");
        try {
            Pepper.resolve();
            fail("a non-base64 direct key must be rejected");
        } catch (final IllegalStateException ex) {
            assertTrue(ex.getMessage().contains("not valid base64"), ex.getMessage());
        } finally {
            System.clearProperty(Pepper.KEY_PROP);
        }
    }
}
