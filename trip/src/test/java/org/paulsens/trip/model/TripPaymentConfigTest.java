package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class TripPaymentConfigTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    public void builderWithNoValuesEqualsNewInstance() {
        assertEquals(TripPaymentConfig.builder().build(), new TripPaymentConfig());
    }

    @Test
    public void jacksonRoundTripPreservesEverything() throws Exception {
        final TripPaymentConfig config = TripPaymentConfig.builder()
                .processorConfigId("cfg-1").feesPaidBy(FeesPaidBy.PAYER)
                .donationEnabled(true).donationLabel("Support the mission")
                .confirmationTemplateId("payment-confirmation")
                .mailFrom("Org <no-reply@org.example>").replyTo("info@org.example").bcc("books@org.example")
                .extraTokens(Map.of("officePhone", "555-1212"))
                .build();
        assertEquals(MAPPER.readValue(MAPPER.writeValueAsString(config), TripPaymentConfig.class), config);
    }

    @Test
    public void blanksNormalizeToNullSoInheritanceWorks() {
        final TripPaymentConfig config = TripPaymentConfig.builder()
                .processorConfigId("  ").mailFrom("").donationLabel(" x ").build();
        assertNull(config.getProcessorConfigId(), "Blank means inherit, stored as null");
        assertNull(config.getMailFrom());
        assertEquals(config.getDonationLabel(), "x");
        assertFalse(config.isPayable());
        assertFalse(config.isDonationOn());
        assertTrue(TripPaymentConfig.builder().processorConfigId("cfg").build().isPayable());
        assertTrue(TripPaymentConfig.builder().donationEnabled(true).build().isDonationOn());
    }

    @Test
    public void overlayLetsEveryNullFallThroughAndMergesTokens() {
        final TripPaymentConfig base = TripPaymentConfig.builder()
                .processorConfigId("org-cfg").feesPaidBy(FeesPaidBy.ORGANIZATION)
                .donationEnabled(false).confirmationTemplateId("payment-confirmation")
                .mailFrom("org-from").replyTo("org-reply")
                .extraTokens(Map.of("shared", "org", "orgOnly", "yes"))
                .build();
        final TripPaymentConfig trip = TripPaymentConfig.builder()
                .feesPaidBy(FeesPaidBy.PAYER).donationEnabled(true)
                .extraTokens(Map.of("shared", "trip", "tripOnly", "yes"))
                .build();

        final TripPaymentConfig merged = trip.overlayOn(base);
        assertEquals(merged.getProcessorConfigId(), "org-cfg", "Null falls through");
        assertEquals(merged.getFeesPaidBy(), FeesPaidBy.PAYER, "Set values win");
        assertTrue(merged.isDonationOn());
        assertEquals(merged.getMailFrom(), "org-from");
        assertEquals(merged.getExtraTokens(),
                Map.of("shared", "trip", "orgOnly", "yes", "tripOnly", "yes"),
                "Token maps merge; the closer rung's keys win");
        assertEquals(base.getExtraTokens().get("shared"), "org", "Inputs are left untouched");

        assertEquals(trip.overlayOn(null).getFeesPaidBy(), FeesPaidBy.PAYER,
                "Overlaying on nothing keeps the config");
    }
}
