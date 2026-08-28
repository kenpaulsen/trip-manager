package org.paulsens.trip.pay;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.model.PaymentProcessorConfig;

/**
 * The "Test connection" button behind a processor config: proves the stored credentials actually
 * authenticate, without creating an order. PAYPAL does a real OAuth client-credentials exchange; FAKE always
 * succeeds (local mode); STRIPE/ZEFFY answer an honest "not supported yet" rather than a fake pass.
 */
@Slf4j
public class ProcessorPing {
    static final String PAYPAL_LIVE = "https://api-m.paypal.com/v1/oauth2/token";
    static final String PAYPAL_SANDBOX = "https://api-m.sandbox.paypal.com/v1/oauth2/token";

    private final HttpClient http;

    public ProcessorPing() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    /** Test seam. */
    ProcessorPing(final HttpClient http) {
        this.http = http;
    }

    /**
     * Null on success, otherwise a short human-readable failure (rendered in a growl SUMMARY).
     *
     * @param sandbox test the sandbox credential set rather than the config's own mode.
     */
    public String ping(final PaymentProcessorConfig config, final boolean sandbox, final String clientSecret) {
        if (config == null || config.getType() == null) {
            return "Unknown processor configuration.";
        }
        return switch (config.getType()) {
            case FAKE -> null;
            case PAYPAL -> pingPayPal(config, sandbox, clientSecret);
            case STRIPE, ZEFFY -> config.getType().name() + " is not supported yet.";
        };
    }

    private String pingPayPal(final PaymentProcessorConfig config, final boolean sandbox,
            final String clientSecret) {
        // sandbox=true tests the SANDBOX credential set; otherwise the config's own set, whose endpoint
        // follows its mode (a SANDBOX-mode config's primary credentials ARE sandbox credentials).
        final boolean useSandbox =
                sandbox || config.getMode() == PaymentProcessorConfig.ProcessorMode.SANDBOX;
        final String clientId = sandbox
                ? config.getSandboxPublicConfig().get("clientId")
                : config.getPublicConfig().get("clientId");
        if (clientId == null || clientId.isBlank()) {
            return "No client id configured" + (sandbox ? " (sandbox)." : ".");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            return "No client secret stored" + (sandbox ? " (sandbox)." : ".");
        }
        final String basic = Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(useSandbox ? PAYPAL_SANDBOX : PAYPAL_LIVE))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                .build();
        try {
            final HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return null;
            }
            log.warn("PayPal ping for config {} answered {}: {}", config.getId(), response.statusCode(),
                    response.body());
            return "PayPal rejected the credentials (HTTP " + response.statusCode() + ").";
        } catch (final java.io.IOException ex) {
            return "Could not reach PayPal: " + ex.getMessage();
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "Interrupted while contacting PayPal.";
        }
    }
}
