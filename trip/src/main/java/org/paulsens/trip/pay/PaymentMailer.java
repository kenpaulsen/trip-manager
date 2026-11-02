package org.paulsens.trip.pay;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.action.MailCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.chat.MailTemplates;
import org.paulsens.trip.content.StarterTemplates;
import org.paulsens.trip.model.Payment;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.security.TicketCodes;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.TripPaymentConfig;
import org.paulsens.trip.site.SiteUrls;

/**
 * The payment confirmation email: fills the trip's configured MAIL template ({@code payment-confirmation}
 * starter by default) from a RECORDED {@link Payment} and sends it to the payer, bcc'ing the org copy. Mail
 * must never decide a payment's fate -- a failed send logs and reports false; the caller audits it and moves
 * on (the ledger is already written).
 */
@Slf4j
public class PaymentMailer {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy");
    private final MailCommands mail;
    private final Function<Person.Id, Person> people;

    public PaymentMailer(final MailCommands mail, final Function<Person.Id, Person> people) {
        this.mail = mail;
        this.people = people;
    }

    /** Sends per the EFFECTIVE config; false when unconfigured/unsendable (logged, never thrown). */
    public boolean sendConfirmation(final Payment payment, final Trip trip, final TripPaymentConfig effective,
            final String orgName, final String processorName, final String notifyEmail,
            final AuditActor actor) {
        final Person payer = people.apply(payment.getPayerId());
        if (payer == null || payer.getEmail() == null || payer.getEmail().isBlank()) {
            log.warn("Payment {}: payer has no address; confirmation not sent", payment.getPaymentId());
            return false;
        }
        if (effective.getConfirmationTemplateId() == null || effective.getMailFrom() == null) {
            log.warn("Payment {}: confirmation mail unconfigured (template {}, from {})",
                    payment.getPaymentId(), effective.getConfirmationTemplateId(), effective.getMailFrom());
            return false;
        }
        final String bcc = joinAddresses(effective.getBcc(), notifyEmail);
        // The PAYMENT's own organization decides whose confirmation copy is sent -- never the request's
        // site: a capture is confirmed wherever the processor calls back, and a payment is org-stamped at
        // creation. The trip is the fallback for a legacy org-less row.
        return mail.sendManagedTemplateForOrg(effective.getConfirmationTemplateId(), orgOf(payment, trip),
                values(payment, trip, effective, payer, orgName, processorName),
                payer.getEmail(), effective.getMailFrom(), effective.getReplyTo(), bcc, actor);
    }

    /**
     * The "your bank payment did not go through" notice for a payment that FAILED after the payer saw
     * "processing" (a returned debit, days later): the fixed {@code payment-failed} MAIL starter, sent from
     * the same effective From/reply-to as the confirmation, bcc'ing the org copy. Same contract as
     * {@link #sendConfirmation}: false when unconfigured or unsendable, logged, never thrown.
     */
    public boolean sendPaymentFailed(final Payment payment, final Trip trip, final TripPaymentConfig effective,
            final String orgName, final String processorName, final String reason, final AuditActor actor) {
        final Person payer = people.apply(payment.getPayerId());
        if (payer == null || payer.getEmail() == null || payer.getEmail().isBlank()) {
            log.warn("Payment {}: payer has no address; failure notice not sent", payment.getPaymentId());
            return false;
        }
        if (effective.getMailFrom() == null) {
            log.warn("Payment {}: failure notice unconfigured (no From)", payment.getPaymentId());
            return false;
        }
        final Map<String, Object> values = values(payment, trip, effective, payer, orgName, processorName);
        values.put("failureReason", reason == null ? "" : reason);
        return mail.sendManagedTemplateForOrg(StarterTemplates.PAYMENT_FAILED_ID, orgOf(payment, trip), values,
                payer.getEmail(), effective.getMailFrom(), effective.getReplyTo(), effective.getBcc(), actor);
    }

    /**
     * A priced checkout's confirmation (docs/checkout.md): the {@code checkout-confirmation} MAIL starter (the
     * org's own copy when it has one), one line per registrant with what they registered as, its price, their
     * status and their ticket code. Same contract as {@link #sendConfirmation}: false when unconfigured or
     * unsendable, logged, never thrown.
     */
    public boolean sendCheckoutConfirmation(final Payment payment, final Trip trip, final TripPaymentConfig effective,
            final String orgName, final String processorName, final String notifyEmail, final AuditActor actor) {
        final Person payer = people.apply(payment.getPayerId());
        if (payer == null || payer.getEmail() == null || payer.getEmail().isBlank()) {
            log.warn("Payment {}: payer has no address; registration confirmation not sent", payment.getPaymentId());
            return false;
        }
        if (effective.getMailFrom() == null) {
            log.warn("Payment {}: registration confirmation unconfigured (no From)", payment.getPaymentId());
            return false;
        }
        final Map<String, Object> values = values(payment, trip, effective, payer, orgName, processorName);
        values.putAll(checkoutValues(payment, trip, processorName));
        return mail.sendManagedTemplateForOrg(StarterTemplates.CHECKOUT_CONFIRMATION_ID, orgOf(payment, trip), values,
                payer.getEmail(), effective.getMailFrom(), effective.getReplyTo(),
                joinAddresses(effective.getBcc(), notifyEmail), actor);
    }

    /**
     * The checkout tokens: the registrants block, the status note (which also names the balance a deposit leaves,
     * so an installed template needs no new token to say it), the trip's dates and how it was paid.
     */
    Map<String, Object> checkoutValues(final Payment payment, final Trip trip, final String processorName) {
        final Map<String, Object> values = new HashMap<>();
        final List<Registration> rows = payment.getCheckout().getRows();
        values.put("registrantsBlock", registrantsBlock(trip, rows));
        final boolean allConfirmed = rows.stream().allMatch(row -> row.getStatus() == Registration.Status.CONFIRMED);
        values.put("statusNote", (allConfirmed ? "Your registration is confirmed. Bring your ticket code to check-in."
                : "Your registration is received and awaiting confirmation; we will email you when it is approved.")
                + balanceNote(payment, trip, rows));
        values.put("tripDates", trip == null || trip.getStartDate() == null ? ""
                : DATE.format(trip.getStartDate()) + (trip.getEndDate() == null ? ""
                        : " to " + DATE.format(trip.getEndDate())));
        values.put("paymentMethod", payment.getTotalChargedCents() == 0 ? "no charge" : processorName);
        return values;
    }

    /**
     * " A balance of $300.00 remains on your trip account, due by March 1, 2027; pay it from the trip's Pay page."
     * when the payment credited less than the party's prices (a deposit policy, or a partial offline payment); "".
     */
    static String balanceNote(final Payment payment, final Trip trip, final List<Registration> rows) {
        final long prices = rows.stream()
                .mapToLong(row -> row.getPriceBreakdown() == null ? 0L : row.getPriceBreakdown().getTotalCents())
                .sum();
        final long credited = payment.getAllocations().stream().mapToLong(Payment.Allocation::getAmountCents).sum();
        if (credited >= prices) {
            return "";
        }
        final java.time.LocalDate due = trip == null ? null : trip.getCheckout().getBalanceDueOn();
        return " A balance of " + MoneyMath.formatCents(prices - credited) + " remains on your trip account"
                + (due == null ? "" : ", due by " + DATE.format(due)) + "; pay it from the trip's Pay page.";
    }

    private MailTemplates.Raw registrantsBlock(final Trip trip, final List<Registration> rows) {
        final String items = rows.stream().map(row -> registrantItem(trip, row)).collect(Collectors.joining());
        return new MailTemplates.Raw(items.isEmpty() ? "" : "<ul>" + items + "</ul>");
    }

    private String registrantItem(final Trip trip, final Registration row) {
        final String type = row.getRegistrationType() == null ? "Registered" : row.getRegistrationType();
        final long price = row.getPriceBreakdown() == null ? 0L : row.getPriceBreakdown().getTotalCents();
        final String ticket = row.getStatus() == Registration.Status.CONFIRMED && row.getTicketCode() != null
                ? " &middot; " + ticketLink(trip, row.getTicketCode()) : "";
        return "<li><b>" + MailTemplates.escape(nameOf(row.getUserId())) + "</b>: "
                + MailTemplates.escape(type) + ", " + MoneyMath.formatCents(price) + " ("
                + row.getStatus().getDescription() + ")" + ticket + "</li>";
    }

    /** "ticket CODE", the code linking to the ticket page (its QR, for check-in) when the trip is known. */
    static String ticketLink(final Trip trip, final String code) {
        final String grouped = "<b>" + TicketCodes.grouped(code) + "</b>";
        final String url = SiteUrls.ticketUrl(trip, code, new ConfigCommands());
        return url.isEmpty() ? "ticket " + grouped
                : "ticket <a href=\"" + MailTemplates.escape(url) + "\">" + grouped + "</a>";
    }

    /** Whose confirmation copy this is: the payment's organization, else its trip's, else nobody's. */
    static String orgOf(final Payment payment, final Trip trip) {
        if (payment.getOrgId() != null && !payment.getOrgId().isBlank()) {
            return payment.getOrgId();
        }
        return trip == null ? null : trip.getOrgId();
    }

    /** The template's token values -- scalars escaped by the mail layer, HTML pre-escaped here as Raw. */
    public Map<String, Object> values(final Payment payment, final Trip trip,
            final TripPaymentConfig effective, final Person payer, final String orgName,
            final String processorName) {
        final Map<String, Object> values = new HashMap<>();
        effective.getExtraTokens().forEach(values::put);
        values.put("payerName", payer.getPreferredName() + " " + payer.getLast());
        values.put("tripTitle", (trip == null || trip.getTitle() == null) ? "" : trip.getTitle());
        values.put("totalPaid", MoneyMath.formatCents(payment.getTotalChargedCents()));
        values.put("feeNote", feeNote(payment, orgName, processorName));
        values.put("donationAmount", MoneyMath.formatCents(payment.getDonationCents()));
        values.put("donationNote", payment.getDonationCents() > 0
                ? "Thank you for your donation of " + MoneyMath.formatCents(payment.getDonationCents())
                        + " to " + orgName + "!"
                : "");
        values.put("captureId", payment.getCaptureId() == null ? "" : payment.getCaptureId());
        values.put("processorName", processorName);
        values.put("paymentDate", payment.getCapturedAt() == null ? ""
                : payment.getCapturedAt().format(DateTimeFormatter.ofPattern("MMMM d, yyyy")));
        values.put("orgName", orgName);
        values.put("amountsBlock", amountsBlock(payment));
        return values;
    }

    private String feeNote(final Payment payment, final String orgName, final String processorName) {
        if (payment.isPayerPaysFee() && payment.getCreditFeeCents() > 0) {
            return "Includes a " + MoneyMath.formatCents(payment.getCreditFeeCents()) + " " + processorName
                    + " fee on the trip portion.";
        }
        return (payment.getCreditFeeCents() > 0)
                ? orgName + " covered the " + processorName + " processing fee."
                : "";
    }

    /** The per-person credited amounts as a pre-escaped HTML list (a Raw token). */
    private MailTemplates.Raw amountsBlock(final Payment payment) {
        final List<Payment.Allocation> selected = payment.getAllocations().stream()
                .filter(allocation -> allocation.getAmountCents() > 0)
                .toList();
        if (selected.isEmpty()) {
            return new MailTemplates.Raw("");
        }
        final String items = selected.stream()
                .map(allocation -> "<li>" + MailTemplates.escape(nameOf(allocation.getPersonId())) + ": "
                        + MoneyMath.formatCents(allocation.getAmountCents()) + "</li>")
                .collect(Collectors.joining());
        return new MailTemplates.Raw("<ul>" + items + "</ul>");
    }

    private String nameOf(final Person.Id personId) {
        final Person person = people.apply(personId);
        return (person == null) ? personId.getValue()
                : person.getPreferredName() + " " + person.getLast();
    }

    private static String joinAddresses(final String a, final String b) {
        final boolean hasA = a != null && !a.isBlank();
        final boolean hasB = b != null && !b.isBlank();
        if (hasA && hasB) {
            return a + "," + b;
        }
        return hasA ? a : (hasB ? b : null);
    }
}
