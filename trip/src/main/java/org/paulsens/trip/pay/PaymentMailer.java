package org.paulsens.trip.pay;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.MailCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.chat.MailTemplates;
import org.paulsens.trip.model.Payment;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripPaymentConfig;

/**
 * The payment confirmation email: fills the trip's configured MAIL template ({@code payment-confirmation}
 * starter by default) from a RECORDED {@link Payment} and sends it to the payer, bcc'ing the org copy. Mail
 * must never decide a payment's fate -- a failed send logs and reports false; the caller audits it and moves
 * on (the ledger is already written).
 */
@Slf4j
public class PaymentMailer {
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
