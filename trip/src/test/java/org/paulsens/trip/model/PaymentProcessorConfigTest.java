package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Map;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class PaymentProcessorConfigTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    public void builderWithNoValuesEqualsNewInstance() {
        final PaymentProcessorConfig built = PaymentProcessorConfig.builder().build();
        final PaymentProcessorConfig fresh = new PaymentProcessorConfig();
        built.setId(fresh.getId());
        assertEquals(built, fresh);
        assertEquals(fresh.getMode(), PaymentProcessorConfig.ProcessorMode.SANDBOX,
                "A config defaults to sandbox until an admin flips it deliberately");
    }

    @Test
    public void jacksonRoundTripPreservesEverything() throws Exception {
        final PaymentProcessorConfig config = PaymentProcessorConfig.builder()
                .id(PaymentProcessorConfig.Id.newInstance())
                .orgId(Organization.Id.newInstance())
                .label("Acme PayPal")
                .type(ProcessorType.PAYPAL)
                .mode(PaymentProcessorConfig.ProcessorMode.LIVE)
                .enabled(true)
                .publicConfig(Map.of("clientId", "live-id"))
                .sandboxPublicConfig(Map.of("clientId", "sandbox-id"))
                .feeBps(350)
                .feeFixedCents(50)
                .createdBy(Person.Id.newInstance())
                .created(LocalDateTime.of(2026, 8, 17, 10, 0))
                .version(2L)
                .build();
        assertEquals(MAPPER.readValue(MAPPER.writeValueAsString(config), PaymentProcessorConfig.class),
                config);
    }

    @Test
    public void effectiveFeesFallBackToTheProcessorDefaults() {
        final PaymentProcessorConfig unset = PaymentProcessorConfig.builder()
                .type(ProcessorType.PAYPAL).build();
        assertEquals(unset.getEffectiveFeeBps(), 349);
        assertEquals(unset.getEffectiveFeeFixedCents(), 49);

        final PaymentProcessorConfig overridden = PaymentProcessorConfig.builder()
                .type(ProcessorType.PAYPAL).feeBps(300).feeFixedCents(30).build();
        assertEquals(overridden.getEffectiveFeeBps(), 300);
        assertEquals(overridden.getEffectiveFeeFixedCents(), 30);

        assertEquals(PaymentProcessorConfig.builder().build().getEffectiveFeeBps(), 0,
                "No type, no default to fall back to");
    }

    @Test
    public void displayLabelNamesTypeAndMode() {
        final PaymentProcessorConfig config = PaymentProcessorConfig.builder()
                .label("Acme Stripe").type(ProcessorType.STRIPE)
                .mode(PaymentProcessorConfig.ProcessorMode.LIVE).build();
        assertEquals(config.getDisplayLabel(), "Acme Stripe (STRIPE, LIVE)");
        assertTrue(new PaymentProcessorConfig().getDisplayLabel().contains("?"));
    }

    @Test
    public void idsCompareByValue() {
        assertTrue(PaymentProcessorConfig.Id.from("a").compareTo(PaymentProcessorConfig.Id.from("b")) < 0);
        assertEquals(PaymentProcessorConfig.Id.from("a"), PaymentProcessorConfig.Id.from("a"));
    }

    @Test
    public void processorTypeDefaultsAreDeclared() {
        assertEquals(ProcessorType.STRIPE.getDefaultFeeBps(), 290);
        assertEquals(ProcessorType.STRIPE.getDefaultFeeFixedCents(), 30);
        assertEquals(ProcessorType.FAKE.getDefaultFeeBps(), 349, "FAKE mirrors PayPal so test math is real");
        assertEquals(ProcessorType.ZEFFY.getDefaultFeeBps(), 0);
    }
}
