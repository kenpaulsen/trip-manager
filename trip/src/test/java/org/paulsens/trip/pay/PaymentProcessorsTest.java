package org.paulsens.trip.pay;

import java.util.Map;
import org.paulsens.trip.model.PaymentProcessorConfig;
import org.paulsens.trip.model.ProcessorType;
import org.paulsens.trip.security.ProcessorSecrets;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

public class PaymentProcessorsTest {

    @Test
    public void fakeAndUnbuiltTypesResolveHonestly() {
        assertTrue(PaymentProcessors.forConfig(config(ProcessorType.FAKE), false) instanceof FakeProcessor);
        assertThrows(PaymentProcessor.ProcessorException.class,
                () -> PaymentProcessors.forConfig(config(ProcessorType.STRIPE), false));
        assertThrows(PaymentProcessor.ProcessorException.class,
                () -> PaymentProcessors.forConfig(config(ProcessorType.ZEFFY), false));
        assertThrows(PaymentProcessor.ProcessorException.class,
                () -> PaymentProcessors.forConfig(null, false));
        assertThrows(PaymentProcessor.ProcessorException.class,
                () -> PaymentProcessors.forConfig(new PaymentProcessorConfig(), false));
    }

    @Test
    public void payPalBuildsFromConfigAndSecretAndIsMemoized() {
        final PaymentProcessorConfig config = config(ProcessorType.PAYPAL);
        // Local mode: the secret store is the in-memory map, so a test can stage credentials.
        assertTrue(ProcessorSecrets.getInstance().put(config.getId().getValue(),
                Map.of("clientSecret", "live-secret", "clientSecretSandbox", "sb-secret")));

        final PaymentProcessor live = PaymentProcessors.forConfig(config, false);
        assertEquals(live.type(), ProcessorType.PAYPAL);
        assertTrue(live == PaymentProcessors.forConfig(config, false),
                "Same (config, version, sandbox) reuses the built client");
        assertTrue(live != PaymentProcessors.forConfig(config, true),
                "The sandbox toggle builds against the sandbox credential set");

        config.setVersion(config.getVersion() + 1);
        assertTrue(live != PaymentProcessors.forConfig(config, false),
                "A config edit (version bump) rebuilds -- credential rotation without restart");
    }

    @Test
    public void missingCredentialsRefuseLoudly() {
        final PaymentProcessorConfig config = config(ProcessorType.PAYPAL);
        config.getPublicConfig().clear();    // no client id, and no stored secret for this fresh id
        assertThrows(PaymentProcessor.ProcessorException.class,
                () -> PaymentProcessors.forConfig(config, false));
    }

    private static PaymentProcessorConfig config(final ProcessorType type) {
        return PaymentProcessorConfig.builder()
                .orgId(org.paulsens.trip.model.Organization.Id.newInstance())
                .label("Test").type(type)
                .mode(PaymentProcessorConfig.ProcessorMode.LIVE)
                .publicConfig(Map.of("clientId", "live-id"))
                .sandboxPublicConfig(Map.of("clientId", "sb-id"))
                .version(1L)
                .build();
    }
}
