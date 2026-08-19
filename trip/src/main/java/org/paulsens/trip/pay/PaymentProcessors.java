package org.paulsens.trip.pay;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.paulsens.trip.model.PaymentProcessorConfig;
import org.paulsens.trip.security.ProcessorSecrets;

/**
 * Resolves the {@link PaymentProcessor} for a {@code PaymentProcessorConfig} -- the multi-tenant
 * replacement for the env-var singleton: credentials come from the CONFIG (public ids) and the payment
 * secret (API secrets), so which account gets paid is a property of the trip's chosen config, and sandbox vs
 * live is a property of the config's mode (plus the paymentsAdmin sandbox toggle, which selects the config's
 * SANDBOX credential set).
 *
 * <p>Instances are memoized per (config id, version, sandbox): a config edit bumps the version, so
 * credential rotation takes effect without a restart while steady-state requests reuse the built client.
 */
public final class PaymentProcessors {
    private static final Map<String, PaymentProcessor> CACHE = new ConcurrentHashMap<>();

    private PaymentProcessors() {
    }

    /**
     * @param sandbox use the config's SANDBOX credential set (the paymentsAdmin toggle); independent of the
     *                config's own mode, which decides the endpoints for its PRIMARY credentials.
     * @throws PaymentProcessor.ProcessorException when the type is unbuilt or credentials are missing.
     */
    public static PaymentProcessor forConfig(final PaymentProcessorConfig config, final boolean sandbox) {
        if (config == null || config.getType() == null) {
            throw new PaymentProcessor.ProcessorException("No payment processor is configured.");
        }
        return switch (config.getType()) {
            case FAKE -> new FakeProcessor();
            case PAYPAL -> CACHE.computeIfAbsent(
                    config.getId().getValue() + ":" + config.getVersion() + ":" + sandbox,
                    key -> buildPayPal(config, sandbox));
            case STRIPE, ZEFFY -> throw new PaymentProcessor.ProcessorException(
                    config.getType().name() + " is not supported yet.");
        };
    }

    private static PaymentProcessor buildPayPal(final PaymentProcessorConfig config, final boolean sandbox) {
        final Map<String, String> publicSet =
                sandbox ? config.getSandboxPublicConfig() : config.getPublicConfig();
        final String secret = ProcessorSecrets.getInstance().get(config.getId().getValue())
                .get(sandbox ? "clientSecretSandbox" : "clientSecret");
        final boolean sandboxEndpoints =
                sandbox || config.getMode() == PaymentProcessorConfig.ProcessorMode.SANDBOX;
        return new PayPalProcessor(publicSet.get("clientId"), secret, sandboxEndpoints);
    }
}
