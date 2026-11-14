package org.paulsens.trip.pay;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.MailCommands;
import org.paulsens.trip.action.OrgCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.chat.MailTemplates;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.FeesPaidBy;
import org.paulsens.trip.model.Organization;
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
                .mailFrom("CFPW <no-reply@unitetrip.com>").replyTo("info@x.org").bcc("books@x.org")
                .build();
        assertTrue(mailer.sendConfirmation(payment(payer), trip(), effective, "CFPW", "PayPal",
                "notify@x.org", AuditActor.system()));

        final ArgumentCaptor<String> bcc = ArgumentCaptor.forClass(String.class);
        Mockito.verify(mail).sendManagedTemplateForOrg(ArgumentMatchers.eq("payment-confirmation"),
                ArgumentMatchers.any(), ArgumentMatchers.anyMap(), ArgumentMatchers.eq("joe@example.com"),
                ArgumentMatchers.eq("CFPW <no-reply@unitetrip.com>"), ArgumentMatchers.eq("info@x.org"),
                bcc.capture(), ArgumentMatchers.any());
        assertEquals(bcc.getValue(), "books@x.org,notify@x.org",
                "The org copy and the site notification ride as bcc");
    }

    @Test
    public void theFailureNoticeUsesTheFixedStarterAndCarriesTheReason() {
        final MailCommands mail = Mockito.mock(MailCommands.class);
        Mockito.when(mail.sendManagedTemplateForOrg(ArgumentMatchers.anyString(), ArgumentMatchers.any(),
                ArgumentMatchers.anyMap(), ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(true);
        final Person payer = person("Joe", "joe@example.com");
        final PaymentMailer mailer = new PaymentMailer(mail, id -> payer);
        final TripPaymentConfig effective = TripPaymentConfig.builder()
                .mailFrom("CFPW <no-reply@unitetrip.com>").bcc("books@x.org").build();

        assertTrue(mailer.sendPaymentFailed(payment(payer), trip(), effective, "CFPW", "Stripe",
                "the bank returned the debit", AuditActor.system()));
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<java.util.Map<String, Object>> values = ArgumentCaptor.forClass(java.util.Map.class);
        Mockito.verify(mail).sendManagedTemplateForOrg(ArgumentMatchers.eq("payment-failed"),
                ArgumentMatchers.any(), values.capture(), ArgumentMatchers.eq("joe@example.com"),
                ArgumentMatchers.eq("CFPW <no-reply@unitetrip.com>"), ArgumentMatchers.any(),
                ArgumentMatchers.eq("books@x.org"), ArgumentMatchers.any());
        assertEquals(values.getValue().get("failureReason"), "the bank returned the debit");
        assertEquals(values.getValue().get("processorName"), "Stripe");

        assertFalse(mailer.sendPaymentFailed(payment(payer), trip(), TripPaymentConfig.builder().build(),
                "CFPW", "Stripe", "x", AuditActor.system()), "no From = unsendable, quietly");
        assertFalse(new PaymentMailer(mail, id -> null).sendPaymentFailed(payment(payer), trip(), effective,
                "CFPW", "Stripe", "x", AuditActor.system()), "no payer = unsendable, quietly");
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
                .confirmationTemplateId("payment-confirmation").mailFrom("x <y@unitetrip.com>").build();

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

    /**
     * A From the trip's organization may not send from (stored before a move to another organization was
     * checked, or on a domain that stopped being the org's) is never used: the send falls back to the site's
     * payment From, which is on a shared domain.
     */
    @Test
    public void aFromTheTripsOrganizationMayNotUseFallsBackToTheSites() {
        DAO.getInstance();
        final MailCommands mail = Mockito.mock(MailCommands.class);
        Mockito.when(mail.sendManagedTemplateForOrg(ArgumentMatchers.anyString(), ArgumentMatchers.any(),
                ArgumentMatchers.anyMap(), ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(true);
        final Person payer = person("Joe", "joe@example.com");
        final PaymentMailer mailer = new PaymentMailer(mail, id -> payer);
        final Trip moved = trip();
        moved.setOrgId(FakeData.BETA_ORG_ID);
        final String tenants = "CFPW <no-reply@centerforpeacewest.com>";
        final TripPaymentConfig carried = TripPaymentConfig.builder()
                .confirmationTemplateId("payment-confirmation").mailFrom(tenants).build();

        final String fallback = mailer.fromFor(moved, carried);
        assertFalse(fallback.contains("centerforpeacewest.com"), "never another tenant's domain: " + fallback);
        assertTrue(new OrgCommands().isOrgFromSendable(null, fallback), "the site's From, on a shared domain");
        assertTrue(mailer.sendConfirmation(payment(payer), moved, carried, "Beta", "PayPal", null,
                AuditActor.system()));
        Mockito.verify(mail).sendManagedTemplateForOrg(ArgumentMatchers.anyString(), ArgumentMatchers.any(),
                ArgumentMatchers.anyMap(), ArgumentMatchers.anyString(), ArgumentMatchers.eq(fallback),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());

        final TripPaymentConfig shared = TripPaymentConfig.builder().mailFrom("Beta <no-reply@unitetrip.com>").build();
        assertEquals(mailer.fromFor(moved, shared), "Beta <no-reply@unitetrip.com>", "a sendable From is kept");
        assertEquals(mailer.fromFor(null, shared), "Beta <no-reply@unitetrip.com>", "no trip: the site's domains");
        assertNull(mailer.fromFor(moved, TripPaymentConfig.builder().build()), "no From stays no From");
    }

    /**
     * The fallback is the ladder minus the trip's own override: the org's default From, while the org may send
     * from it, comes before the site's, so a registrant never gets the org's receipt under the shared brand.
     * An inherited site From the org's allow-list excludes is sent as it is: there is nothing to fall back to.
     */
    @Test
    public void anUnsendableTripFromFallsBackToTheOrgDefaultFirst() throws IOException {
        final Organization acme = Organization.builder().name("Acme " + java.util.UUID.randomUUID()).build();
        acme.getPaymentDefaults().setMailFrom("Acme <pay@unitetrip.com>");
        assertTrue(DAO.getInstance().saveOrganization(acme));
        final Trip trip = trip();
        trip.setOrgId(acme.getId().getValue());
        final PaymentMailer mailer = new PaymentMailer(Mockito.mock(MailCommands.class), id -> null);
        final TripPaymentConfig stale = TripPaymentConfig.builder()
                .mailFrom("CFPW <no-reply@centerforpeacewest.com>").build();
        assertEquals(mailer.fromFor(trip, stale), "Acme <pay@unitetrip.com>", "the org's default, not the site's");

        acme.getPaymentDefaults().setMailFrom(null);
        acme.setMailDomains(List.of("unitetrip.com"));
        assertTrue(DAO.getInstance().saveOrganization(acme));
        final String site = new OrgCommands().inheritedPaymentFrom(null);
        assertFalse(new OrgCommands().isOrgFromSendable(acme.getId().getValue(), site), "the premise");
        assertEquals(mailer.fromFor(trip, TripPaymentConfig.builder().mailFrom(site).build()), site,
                "the inherited From is what is sent");
    }

    @Test
    public void refusalsAreQuietAndNeverThrow() {
        final MailCommands mail = Mockito.mock(MailCommands.class);
        final Person noAddress = person("Nobody", null);
        final PaymentMailer mailer = new PaymentMailer(mail, id -> noAddress);
        final TripPaymentConfig effective = TripPaymentConfig.builder()
                .confirmationTemplateId("payment-confirmation").mailFrom("x <y@unitetrip.com>").build();

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
                .confirmationTemplateId("payment-confirmation").mailFrom("x <y@unitetrip.com>").build();

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

    /** A checkout's confirmation lists each registrant with type, price, status and (when confirmed) ticket. */
    @Test
    public void aCheckoutConfirmationListsEveryRegistrant() {
        final MailCommands mail = Mockito.mock(MailCommands.class);
        Mockito.when(mail.sendManagedTemplateForOrg(ArgumentMatchers.anyString(), ArgumentMatchers.any(),
                ArgumentMatchers.anyMap(), ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(true);
        final Person payer = person("Ann", "ann@example.com");
        final Person kid = person("Kid", "kid@example.com");
        final java.util.Map<org.paulsens.trip.model.Person.Id, Person> people =
                java.util.Map.of(payer.getId(), payer, kid.getId(), kid);
        final PaymentMailer mailer = new PaymentMailer(mail, people::get);
        final Payment payment = payment(payer);
        payment.setCheckout(new org.paulsens.trip.model.PendingParty("party", payer.getId(), List.of(
                row(payer, org.paulsens.trip.model.Registration.Status.CONFIRMED, "Full weekend", 15000L, "ABCDEFGH"),
                row(kid, org.paulsens.trip.model.Registration.Status.PENDING, null, 5000L, null)), null, null));
        final Trip trip = trip();
        trip.setStartDate(LocalDateTime.of(2027, 3, 5, 8, 0));
        trip.setEndDate(LocalDateTime.of(2027, 3, 7, 17, 0));
        final TripPaymentConfig effective = TripPaymentConfig.builder().mailFrom("CFPW <no-reply@unitetrip.com>")
                .build();

        assertTrue(mailer.sendCheckoutConfirmation(payment, trip, effective, "CFPW", "PayPal", "office@x.org",
                AuditActor.system()));
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<java.util.Map<String, Object>> values = ArgumentCaptor.forClass(java.util.Map.class);
        Mockito.verify(mail).sendManagedTemplateForOrg(ArgumentMatchers.eq("checkout-confirmation"),
                ArgumentMatchers.any(), values.capture(), ArgumentMatchers.eq("ann@example.com"),
                ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.eq("office@x.org"),
                ArgumentMatchers.any());
        final String block = ((MailTemplates.Raw) values.getValue().get("registrantsBlock")).html();
        assertTrue(block.contains("Ann Tester</b>: Full weekend, $150.00 (Confirmed)"), block);
        assertTrue(block.contains("ticket <a href=\"https://") && block.contains(
                "/trip/ticket.jsf?trip=golden&amp;t=ABCDEFGH\"><b>ABCD-EFGH</b></a>"),
                "the code links to the ticket page, the QR check-in scans: " + block);
        assertEquals(PaymentMailer.ticketLink(null, "ABCDEFGH"), "ticket <b>ABCD-EFGH</b>", "no trip, no link");
        assertTrue(block.contains("Kid Tester</b>: Registered, $50.00 (Pending)"), block);
        assertTrue(values.getValue().get("statusNote").toString().contains("awaiting confirmation"),
                "one registrant still waits for approval");
        assertEquals(values.getValue().get("tripDates"), "March 5, 2027 to March 7, 2027");
        assertEquals(values.getValue().get("paymentMethod"), "PayPal");

        // A deposit (or a partial offline payment) credited less than the prices: the status note says what is
        // left, with the policy's due date, so an installed template needs no new token.
        payment.setAllocations(List.of(new Payment.Allocation(payer.getId(), 5000L)));
        trip.getCheckout().setDepositCents(5000L);
        trip.getCheckout().setBalanceDueOn(java.time.LocalDate.of(2027, 3, 1));
        assertEquals(PaymentMailer.balanceNote(payment, trip, payment.getCheckout().getRows()),
                " A balance of $150.00 remains on your trip account, due by March 1, 2027; pay it from the trip's"
                        + " Pay page.");
        assertEquals(PaymentMailer.balanceNote(payment, null, payment.getCheckout().getRows()),
                " A balance of $150.00 remains on your trip account; pay it from the trip's Pay page.");
        assertTrue(mailer.checkoutValues(payment, trip, "PayPal").get("statusNote").toString()
                .endsWith("due by March 1, 2027; pay it from the trip's Pay page."));
        payment.setAllocations(List.of(new Payment.Allocation(payer.getId(), 20000L)));
        assertEquals(PaymentMailer.balanceNote(payment, trip, payment.getCheckout().getRows()), "", "paid in full");

        payment.setTotalChargedCents(0L);
        assertEquals(mailer.checkoutValues(payment, trip, "Offline payment").get("paymentMethod"), "no charge");
        assertEquals(mailer.checkoutValues(payment, null, "x").get("tripDates"), "");
        assertFalse(mailer.sendCheckoutConfirmation(payment, trip, TripPaymentConfig.builder().build(), "CFPW",
                "PayPal", null, AuditActor.system()), "no From = unsendable, quietly");
        assertFalse(new PaymentMailer(mail, id -> null).sendCheckoutConfirmation(payment, trip, effective, "CFPW",
                "PayPal", null, AuditActor.system()), "no payer = unsendable, quietly");
    }

    private static org.paulsens.trip.model.Registration row(final Person person,
            final org.paulsens.trip.model.Registration.Status status, final String type, final long cents,
            final String ticket) {
        final org.paulsens.trip.model.PriceBreakdown breakdown = new org.paulsens.trip.model.PriceBreakdown(null,
                null, cents, type, null);
        return new org.paulsens.trip.model.Registration("golden", person.getId()).withStatus(status)
                .withRegistrationType(type).withPriceBreakdown(breakdown).withTicketCode(ticket);
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
