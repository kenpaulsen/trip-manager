package org.paulsens.trip.pay;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.model.PaymentProcessorConfig;
import org.paulsens.trip.model.ProcessorType;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class ProcessorPingTest {

    @Test
    public void fakeAlwaysPassesAndUnbuiltTypesSaySoHonestly() {
        final ProcessorPing ping = new ProcessorPing();
        assertNull(ping.ping(config(ProcessorType.FAKE), false, null));
        assertEquals(ping.ping(config(ProcessorType.STRIPE), false, "sk"), "STRIPE is not supported yet.");
        assertEquals(ping.ping(config(ProcessorType.ZEFFY), false, null), "ZEFFY is not supported yet.");
        assertTrue(ping.ping(null, false, null).contains("Unknown"));
        assertTrue(ping.ping(new PaymentProcessorConfig(), false, null).contains("Unknown"));
    }

    @Test
    public void payPalRefusesWithoutCredentialsBeforeTouchingTheNetwork() {
        final ProcessorPing ping = new ProcessorPing(Mockito.mock(HttpClient.class));
        final PaymentProcessorConfig noId = config(ProcessorType.PAYPAL);
        noId.getPublicConfig().clear();
        assertTrue(ping.ping(noId, false, "secret").contains("No client id"));

        assertTrue(ping.ping(config(ProcessorType.PAYPAL), false, "  ").contains("No client secret"));
        final PaymentProcessorConfig noSandboxSet = config(ProcessorType.PAYPAL);
        noSandboxSet.getSandboxPublicConfig().clear();
        assertTrue(ping.ping(noSandboxSet, true, "secret").contains("No client id"),
                "The sandbox check reads the SANDBOX credential set");
    }

    @Test
    public void payPalOutcomesMapToMessages() throws Exception {
        assertNull(pingWithStatus(200, false), "200 means the credentials authenticate");
        assertTrue(pingWithStatus(401, false).contains("HTTP 401"));

        final HttpClient broken = Mockito.mock(HttpClient.class);
        Mockito.when(broken.send(ArgumentMatchers.any(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new IOException("no route"));
        assertTrue(new ProcessorPing(broken).ping(config(ProcessorType.PAYPAL), false, "secret")
                .contains("Could not reach PayPal"));
    }

    @Test
    public void sandboxFlagAndModePickTheSandboxEndpoint() throws Exception {
        final HttpClient http = Mockito.mock(HttpClient.class);
        final HttpResponse<String> ok = response(200);
        final ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        Mockito.when(http.send(sent.capture(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(ok);
        final ProcessorPing ping = new ProcessorPing(http);

        final PaymentProcessorConfig live = config(ProcessorType.PAYPAL);
        assertNull(ping.ping(live, false, "secret"));
        assertEquals(sent.getValue().uri().toString(), ProcessorPing.PAYPAL_LIVE);

        final PaymentProcessorConfig sandboxMode = config(ProcessorType.PAYPAL);
        sandboxMode.setMode(PaymentProcessorConfig.ProcessorMode.SANDBOX);
        assertNull(ping.ping(sandboxMode, false, "secret"));
        assertEquals(sent.getValue().uri().toString(), ProcessorPing.PAYPAL_SANDBOX,
                "A SANDBOX-mode config's own credentials test against the sandbox endpoint");

        assertNull(ping.ping(live, true, "secret"));
        assertEquals(sent.getValue().uri().toString(), ProcessorPing.PAYPAL_SANDBOX,
                "The explicit sandbox check uses the sandbox endpoint too");
    }

    private String pingWithStatus(final int status, final boolean sandbox) throws Exception {
        final HttpResponse<String> response = response(status);
        final HttpClient http = Mockito.mock(HttpClient.class);
        Mockito.when(http.send(ArgumentMatchers.any(),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        return new ProcessorPing(http).ping(config(ProcessorType.PAYPAL), sandbox, "secret");
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(final int status) {
        final HttpResponse<String> response = Mockito.mock(HttpResponse.class);
        Mockito.when(response.statusCode()).thenReturn(status);
        Mockito.when(response.body()).thenReturn("{}");
        return response;
    }

    private static PaymentProcessorConfig config(final ProcessorType type) {
        return PaymentProcessorConfig.builder()
                .label("Test").type(type)
                .mode(PaymentProcessorConfig.ProcessorMode.LIVE)
                .publicConfig(Map.of("clientId", "live-id"))
                .sandboxPublicConfig(Map.of("clientId", "sandbox-id"))
                .build();
    }
}
