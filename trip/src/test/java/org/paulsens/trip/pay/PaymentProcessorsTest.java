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
        assertEquals(live.cancel("ORDER-1"), PaymentProcessor.OrderState.UNKNOWN,
                "these credentials cannot ask PayPal anything, and that is never read as 'released'");
    }

    @Test
    public void stripeBuildsFromThePublishableKeyAndSecretKeyAndChecksTheirMode() {
        final PaymentProcessorConfig config = config(ProcessorType.STRIPE);
        config.getPublicConfig().put("clientId", "pk_live_1");
        config.getSandboxPublicConfig().put("clientId", "pk_test_1");
        assertTrue(ProcessorSecrets.getInstance().put(config.getId().getValue(),
                Map.of("clientSecret", "sk_live_1", "clientSecretSandbox", "sk_test_1")));

        final PaymentProcessor live = PaymentProcessors.forConfig(config, false);
        assertEquals(live.type(), ProcessorType.STRIPE);
        assertTrue(live instanceof StripeProcessor);
        assertTrue(live == PaymentProcessors.forConfig(config, false), "memoized per config/version/sandbox");
        assertTrue(live != PaymentProcessors.forConfig(config, true), "the sandbox toggle builds on the test keys");

        final PaymentProcessorConfig mixed = config(ProcessorType.STRIPE);
        mixed.getSandboxPublicConfig().put("clientId", "pk_test_2");
        assertTrue(ProcessorSecrets.getInstance().put(mixed.getId().getValue(),
                Map.of("clientSecretSandbox", "sk_live_2")));
        assertThrows(PaymentProcessor.ProcessorException.class,
                () -> PaymentProcessors.forConfig(mixed, true));

        final PaymentProcessorConfig sandboxMode = config(ProcessorType.STRIPE);
        sandboxMode.setMode(PaymentProcessorConfig.ProcessorMode.SANDBOX);
        sandboxMode.getPublicConfig().put("clientId", "pk_test_3");
        assertTrue(ProcessorSecrets.getInstance().put(sandboxMode.getId().getValue(),
                Map.of("clientSecret", "sk_test_3")));
        assertEquals(PaymentProcessors.forConfig(sandboxMode, false).type(), ProcessorType.STRIPE,
                "a SANDBOX-mode config's primary credentials are test keys");
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
                .publicConfig(new java.util.HashMap<>(Map.of("clientId", "live-id")))
                .sandboxPublicConfig(new java.util.HashMap<>(Map.of("clientId", "sb-id")))
                .version(1L)
                .build();
    }

    /** OFFLINE is a payment recorded by hand: it has no processor to build, ping or configure. */
    @org.testng.annotations.Test
    public void anOfflinePaymentHasNoProcessor() {
        final org.paulsens.trip.model.PaymentProcessorConfig offline = org.paulsens.trip.model.PaymentProcessorConfig
                .builder().type(org.paulsens.trip.model.ProcessorType.OFFLINE).build();
        org.testng.Assert.assertThrows(PaymentProcessor.ProcessorException.class,
                () -> PaymentProcessors.forConfig(offline, false));
        org.testng.Assert.assertEquals(new ProcessorPing().ping(offline, false, null),
                "An offline payment has no processor to test.");
        org.testng.Assert.assertFalse(org.paulsens.trip.model.ProcessorType.configurableTypes()
                .contains(org.paulsens.trip.model.ProcessorType.OFFLINE));
        org.testng.Assert.assertFalse(org.paulsens.trip.model.ProcessorType.OFFLINE.isConfigurable());
        org.testng.Assert.assertTrue(org.paulsens.trip.model.ProcessorType.STRIPE.isConfigurable());
        org.testng.Assert.assertFalse(new org.paulsens.trip.action.OrgCommands().getProcessorTypes()
                .contains("OFFLINE"), "an org can never set one up");
    }
}
