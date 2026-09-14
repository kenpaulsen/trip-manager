package org.paulsens.trip.push;

import java.security.interfaces.ECPrivateKey;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

/** {@link LoggingPushGateway} (the recorder) and {@link PushRuntime} (which gateway serves which kind). */
public class GatewayRuntimeTest {

    private static final PushDevice PHONE = PushDevice.ios("ab".repeat(16), "production", "s", "Phone", "1", 1L);
    private static final PushDevice BROWSER = PushDevice.web("https://e/1", "p", "a", "Safari", "o", 1L);

    @AfterMethod(alwaysRun = true)
    public void restore() {
        PushRuntime.setGateway(PushDevice.Kind.IOS, null);
        PushRuntime.setGateway(PushDevice.Kind.WEB, null);
        PushRuntime.setSecrets(null);
    }

    @Test
    public void theRecorderKeepsABoundedHistoryAndAnswersItsOutcome() {
        final LoggingPushGateway gateway = new LoggingPushGateway();
        Assert.assertEquals(gateway.send(PHONE, PushPayload.silent()), PushOutcome.DELIVERED);
        Assert.assertEquals(gateway.sent().size(), 1);
        Assert.assertEquals(gateway.sent().get(0).device(), PHONE);
        Assert.assertEquals(gateway.send(null, PushPayload.silent()), PushOutcome.FAILED);
        Assert.assertEquals(gateway.send(PHONE, null), PushOutcome.FAILED);
        for (int i = 0; i < LoggingPushGateway.MAX_RECORDED + 5; i++) {
            gateway.send(BROWSER, PushPayload.silent());
        }
        Assert.assertEquals(gateway.sent().size(), LoggingPushGateway.MAX_RECORDED);
        Assert.assertEquals(gateway.sent().get(0).device(), BROWSER, "the oldest entries were dropped");
        gateway.clear();
        Assert.assertTrue(gateway.sent().isEmpty());

        final LoggingPushGateway failing = new LoggingPushGateway(PushOutcome.FAILED, "no secret");
        Assert.assertEquals(failing.send(PHONE, PushPayload.silent()), PushOutcome.FAILED);
        Assert.assertEquals(failing.send(PHONE, PushPayload.silent()), PushOutcome.FAILED);
    }

    @Test
    public void localModeHandsOutTheRecorderForEveryKind() {
        Assert.assertSame(PushRuntime.gatewayFor(PushDevice.Kind.IOS), PushRuntime.recorder(),
                "the suite runs local with the ephemeral VAPID pair: no real client, ever");
        Assert.assertSame(PushRuntime.gatewayFor(PushDevice.Kind.WEB), PushRuntime.recorder());
    }

    @Test
    public void anOverrideWinsAndIsClearedWithNull() {
        final LoggingPushGateway pinned = new LoggingPushGateway();
        PushRuntime.setGateway(PushDevice.Kind.IOS, pinned);
        Assert.assertSame(PushRuntime.gatewayFor(PushDevice.Kind.IOS), pinned);
        Assert.assertNotSame(PushRuntime.gatewayFor(PushDevice.Kind.WEB), pinned);
        PushRuntime.setGateway(PushDevice.Kind.IOS, null);
        Assert.assertSame(PushRuntime.gatewayFor(PushDevice.Kind.IOS), PushRuntime.recorder());
    }

    @Test
    public void realKeysBuildRealClientsCachedPerKey() {
        final PushSecrets configured = new PushSecrets(() -> PushSecretsTest.json(true, true));
        PushRuntime.setSecrets(() -> configured);
        final PushGateway apns = PushRuntime.gatewayFor(PushDevice.Kind.IOS);
        Assert.assertTrue(apns instanceof ApnsClient);
        Assert.assertSame(PushRuntime.gatewayFor(PushDevice.Kind.IOS), apns, "one client per key id");
        Assert.assertTrue(PushRuntime.gatewayFor(PushDevice.Kind.WEB) instanceof WebPushClient);

        final PushSecrets apnsOnly = new PushSecrets(() -> PushSecretsTest.json(true, false));
        PushRuntime.setSecrets(() -> apnsOnly);
        Assert.assertSame(PushRuntime.gatewayFor(PushDevice.Kind.WEB), PushRuntime.recorder(),
                "no VAPID pair: local mode falls back to the recorder");
        Assert.assertNotNull(new PushSecrets.ApnsKey("k", "t", (ECPrivateKey) EcKeys.generate().getPrivate()));
    }
}
