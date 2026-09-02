package org.paulsens.trip.pay;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.MailCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.chat.MailTemplates;
import org.paulsens.trip.model.FeesPaidBy;
import org.paulsens.trip.model.Payment;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripPaymentConfig;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class PaymentMailerTest {

    @Test
    public void sendsThroughTheEffectiveConfigWithTheOrgBcc() {
        final MailCommands mail = Mockito.mock(MailCommands.class);
        Mockito.when(mail.sendManagedTemplateForOrg(ArgumentMatchers.anyString(), ArgumentMatchers.any(),
                ArgumentMatchers.anyMap(), ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(true);
        final Person payer = person("Joe", "joe@example.com");
        final PaymentMailer mailer = new PaymentMailer(mail, id -> payer);

        final TripPaymentConfig effective = TripPaymentConfig.builder()
                .confirmationTemplateId("payment-confirmation")
                .mailFrom("CFPW <no-reply@x.org>").replyTo("info@x.org").bcc("books@x.org")
                .build();
        assertTrue(mailer.sendConfirmation(payment(payer), trip(), effective, "CFPW", "PayPal",
                "notify@x.org", AuditActor.system()));

        final ArgumentCaptor<String> bcc = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mail).sendManagedTemplateForOrg(ArgumentMatchers.eq("payment-confirmation"),
                ArgumentMatchers.any(), ArgumentMatchers.anyMap(), ArgumentMatchers.eq("joe@example.com"),
                ArgumentMatchers.eq("CFPW <no-reply@x.org>"), ArgumentMatchers.eq("info@x.org"),
                bcc.capture(), ArgumentMatchers.any());
        assertEquals(bcc.getValue(), "books@x.org,notify@x.org",
                "The org copy and the site notification ride as bcc");
    }

    /**
     * Whose confirmation copy goes out: the PAYMENT's own organization. A capture is confirmed wherever the
     * processor calls back -- often on a background thread with no host at all -- so the org must come from
     * the row, never from the request's site. A legacy org-less payment falls back to its trip's.
     */
    @Test
    public void theOrganizationThreadedIntoTheSendComesFromThePayment() {
        final MailCommands mail = Mockito.mock(MailCommands.class);
        Mockito.when(mail.sendManagedTemplateForOrg(ArgumentMatchers.anyString(), ArgumentMatchers.any(),
                ArgumentMatchers.anyMap(), ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(true);
        final Person payer = person("Joe", "joe@example.com");
        final Payment ownedPayment = payment(payer);
        ownedPayment.setOrgId("org-of-the-payment");
        final TripPaymentConfig effective = TripPaymentConfig.builder()
                .confirmationTemplateId("payment-confirmation").mailFrom("x <y@z.org>").build();

        assertTrue(new PaymentMailer(mail, id -> payer).sendConfirmation(ownedPayment, trip(), effective,
                "CFPW", "PayPal", null, AuditActor.system()));
        Mockito.verify(mail).sendManagedTemplateForOrg(ArgumentMatchers.anyString(),
                ArgumentMatchers.eq("org-of-the-payment"), ArgumentMatchers.anyMap(),
                ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any());

        final Trip owned = trip();
        owned.setOrgId("org-of-the-trip");
        assertEquals(PaymentMailer.orgOf(payment(payer), owned), "org-of-the-trip",
                "a payment written before payments carried an org falls back to its trip's");
        assertEquals(PaymentMailer.orgOf(ownedPayment, owned), "org-of-the-payment", "the payment wins");
        assertNull(PaymentMailer.orgOf(payment(payer), trip()), "org-less both ways: the shared copy");
        assertNull(PaymentMailer.orgOf(payment(payer), null));
    }

    @Test
    public void refusalsAreQuietAndNeverThrow() {
        final MailCommands mail = Mockito.mock(MailCommands.class);
        final Person noAddress = person("Nobody", null);
        final PaymentMailer mailer = new PaymentMailer(mail, id -> noAddress);
        final TripPaymentConfig effective = TripPaymentConfig.builder()
                .confirmationTemplateId("payment-confirmation").mailFrom("x <y@z.org>").build();

        assertFalse(mailer.sendConfirmation(payment(noAddress), trip(), effective, "CFPW", "PayPal",
                null, AuditActor.system()), "No payer address, no send");
        assertFalse(new PaymentMailer(mail, id -> null).sendConfirmation(payment(noAddress), trip(),
                effective, "CFPW", "PayPal", null, AuditActor.system()));
        assertFalse(mailer.sendConfirmation(payment(person("Joe", "j@x.org")), trip(),
                TripPaymentConfig.builder().build(), "CFPW", "PayPal", null, AuditActor.system()),
                "Unconfigured template/from refuses");
        Mockito.verifyNoInteractions(mail);
    }

    @Test
    public void tokenValuesMatchTheWorkedExample() {
        final Person joe = person("Joe", "joe@example.com");
        final PaymentMailer mailer = new PaymentMailer(Mockito.mock(MailCommands.class), id -> joe);
        final TripPaymentConfig effective = TripPaymentConfig.builder()
                .extraTokens(Map.of("officePhone", "555-1212")).build();

        final Map<String, Object> values =
                mailer.values(payment(joe), trip(), effective, joe, "CFPW", "PayPal");
        assertEquals(values.get("payerName"), "Joe Tester");
        assertEquals(values.get("tripTitle"), "Golden Trip");
        assertEquals(values.get("totalPaid"), "$2,500.00");
        assertEquals(values.get("donationAmount"), "$1,000.00");
        assertTrue(String.valueOf(values.get("donationNote")).contains("donation of $1,000.00"));
        assertTrue(String.valueOf(values.get("feeNote")).contains("$75.00 PayPal fee"));
        assertEquals(values.get("captureId"), "123447384");
        assertEquals(values.get("paymentDate"), "June 9, 2026");
        assertEquals(values.get("officePhone"), "555-1212", "Extra tokens merge in");
        final MailTemplates.Raw block = (MailTemplates.Raw) values.get("amountsBlock");
        assertTrue(block.html().contains("Joe Tester: $475.00"), block.html());

        // Org-pays variant flips the fee wording; no donation drops its note.
        final Payment orgPays = payment(joe);
        orgPays.setFeesPaidBy(FeesPaidBy.ORGANIZATION);
        orgPays.setDonationCents(0L);
        final Map<String, Object> orgValues =
                mailer.values(orgPays, trip(), effective, joe, "CFPW", "PayPal");
        assertTrue(String.valueOf(orgValues.get("feeNote")).contains("CFPW covered"));
        assertEquals(orgValues.get("donationNote"), "");
    }

    @Test
    public void bccCombinationsAndNameFallbacksHold() {
        final MailCommands mail = Mockito.mock(MailCommands.class);
        Mockito.when(mail.sendManagedTemplateForOrg(ArgumentMatchers.anyString(), ArgumentMatchers.any(),
                ArgumentMatchers.anyMap(), ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(true);
        final Person payer = person("Joe", "joe@example.com");
        final PaymentMailer mailer = new PaymentMailer(mail,
                id -> id.equals(payer.getId()) ? payer : null);
        final TripPaymentConfig noBcc = TripPaymentConfig.builder()
                .confirmationTemplateId("payment-confirmation").mailFrom("x <y@z.org>").build();

        final ArgumentCaptor<String> bcc = ArgumentCaptor.forClass(String.class);
        assertTrue(mailer.sendConfirmation(payment(payer), trip(), noBcc, "CFPW", "PayPal",
                "notify@x.org", AuditActor.system()));
        assertTrue(mailer.sendConfirmation(payment(payer), trip(), noBcc, "CFPW", "PayPal",
                null, AuditActor.system()));
        Mockito.verify(mail, Mockito.times(2)).sendManagedTemplateForOrg(ArgumentMatchers.anyString(),
                ArgumentMatchers.any(), ArgumentMatchers.anyMap(), ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(), ArgumentMatchers.any(), bcc.capture(),
                ArgumentMatchers.any());
        assertEquals(bcc.getAllValues().get(0), "notify@x.org", "Notify alone still rides as bcc");
        assertEquals(bcc.getAllValues().get(1), null, "No bcc at all is fine");

        // A name the resolver cannot answer falls back to the id rather than dying.
        final Payment withStranger = payment(payer);
        withStranger.getAllocations().add(new Payment.Allocation(Person.Id.from("ghost-1"), 100L));
        final MailTemplates.Raw block = (MailTemplates.Raw) mailer
                .values(withStranger, trip(), noBcc, payer, "CFPW", "PayPal").get("amountsBlock");
        assertTrue(block.html().contains("ghost-1"), block.html());
    }

    private static Person person(final String first, final String email) {
        return Person.builder().first(first).last("Tester").email(email).build();
    }

    private static Trip trip() {
        return Trip.builder().id("golden").title("Golden Trip").build();
    }

    private static Payment payment(final Person payer) {
        return Payment.builder()
                .payerId(payer.getId())
                .allocations(List.of(new Payment.Allocation(payer.getId(), 47500L)))
                .donationCents(100000L)
                .feesPaidBy(FeesPaidBy.PAYER)
                .creditFeeCents(7500L)
                .donationFeeCents(5000L)
                .totalChargedCents(250000L)
                .captureId("123447384")
                .capturedAt(LocalDateTime.of(2026, 6, 9, 10, 0))
                .build();
    }
}
