package org.paulsens.trip.pay;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.Caller;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.action.MailCommands;
import org.paulsens.trip.action.OrgCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.chat.MailTemplates;
import org.paulsens.trip.content.StarterTemplates;
import org.paulsens.trip.i18n.Formats;
import org.paulsens.trip.i18n.ReaderLanguage;
import org.paulsens.trip.i18n.Text;
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
    private final MailCommands mail;
    private final Function<Person.Id, Person> people;
    private final OrgCommands orgs;

    public PaymentMailer(final MailCommands mail, final Function<Person.Id, Person> people) {
        this.mail = mail;
        this.people = people;
        this.orgs = new OrgCommands(Caller::current, () -> mail);
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
        // ...and the PAYER's language picks that copy's version (docs/content-templates.md).
        return mail.sendManagedTemplateForOrg(effective.getConfirmationTemplateId(), orgOf(payment, trip),
                ReaderLanguage.of(payer, trip), values(payment, trip, effective, payer, orgName, processorName),
                payer.getEmail(), fromFor(trip, effective), effective.getReplyTo(), bcc, actor);
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
        return mail.sendManagedTemplateForOrg(StarterTemplates.PAYMENT_FAILED_ID, orgOf(payment, trip),
                ReaderLanguage.of(payer, trip), values, payer.getEmail(), fromFor(trip, effective),
                effective.getReplyTo(), effective.getBcc(), actor);
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
        values.putAll(checkoutValues(payment, trip, processorName, ReaderLanguage.of(payer, trip)));
        return mail.sendManagedTemplateForOrg(StarterTemplates.CHECKOUT_CONFIRMATION_ID, orgOf(payment, trip),
                ReaderLanguage.of(payer, trip), values, payer.getEmail(), fromFor(trip, effective),
                effective.getReplyTo(), joinAddresses(effective.getBcc(), notifyEmail), actor);
    }

    /**
     * The From to send with ({@link OrgCommands#sendablePaymentFrom}: the effective one while the trip's organization
     * may send from it, else the From the trip inherits), logging a substitution. The trips API re-checks a From
     * when a trip moves to another organization, but a row stored before that check, or a domain that has since
     * stopped being the organization's (a custom domain moved or dropped), must not mail its registrants as
     * another tenant.
     */
    String fromFor(final Trip trip, final TripPaymentConfig effective) {
        final String from = effective.getMailFrom();
        final String sendable = orgs.sendablePaymentFrom(trip, effective);
        if (from != null && !from.equals(sendable)) {
            // Only a real substitution is news: an org whose allow-list leaves out the shared domains still
            // inherits the site's From, and logging that on every payment mail would bury the real cases.
            log.warn("Trip {}: payment From {} is not on its organization's sending domains; sending as {}",
                    trip == null ? null : trip.getId(), from, sendable);
        }
        return sendable;
    }

    /**
     * The checkout tokens: the registrants block, the status note (which also names the balance a deposit leaves,
     * so an installed template needs no new token to say it), the trip's dates and how it was paid.
     */
    Map<String, Object> checkoutValues(final Payment payment, final Trip trip, final String processorName,
            final Locale locale) {
        final Map<String, Object> values = new HashMap<>();
        final List<Registration> rows = payment.getCheckout().getRows();
        values.put("registrantsBlock", registrantsBlock(trip, rows, locale));
        final boolean allConfirmed = rows.stream().allMatch(row -> row.getStatus() == Registration.Status.CONFIRMED);
        values.put("statusNote", Text.forLocale(locale, allConfirmed ? "mail.payment.statusConfirmed"
                : "mail.payment.statusReceived") + balanceNote(payment, trip, rows, locale));
        values.put("tripDates", trip == null ? ""
                : Objects.toString(Formats.longDateSpan(trip.getStartDate(), trip.getEndDate(), locale), ""));
        values.put("paymentMethod", payment.getTotalChargedCents() == 0
                ? Text.forLocale(locale, "mail.payment.noCharge") : processorName);
        return values;
    }

    /**
     * " A balance of $300.00 remains on your trip account, due by March 1, 2027; pay it from the trip's Pay page."
     * when the payment credited less than the party's prices (a deposit policy, or a partial offline payment); "".
     * The leading space joins it to the status sentence before it; a bundle value cannot keep one.
     */
    static String balanceNote(final Payment payment, final Trip trip, final List<Registration> rows,
            final Locale locale) {
        final long prices = rows.stream()
                .mapToLong(row -> row.getPriceBreakdown() == null ? 0L : row.getPriceBreakdown().getTotalCents())
                .sum();
        final long credited = payment.getAllocations().stream().mapToLong(Payment.Allocation::getAmountCents).sum();
        if (credited >= prices) {
            return "";
        }
        final java.time.LocalDate due = trip == null ? null : trip.getCheckout().getBalanceDueOn();
        final String balance = MoneyMath.formatCents(prices - credited);
        return " " + (due == null ? Text.forLocale(locale, "mail.payment.balance", balance)
                : Text.forLocale(locale, "mail.payment.balanceDue", balance, Formats.longDate(due, locale)));
    }

    private MailTemplates.Raw registrantsBlock(final Trip trip, final List<Registration> rows, final Locale locale) {
        final String items = rows.stream().map(row -> registrantItem(trip, row, locale))
                .collect(Collectors.joining());
        return new MailTemplates.Raw(items.isEmpty() ? "" : "<ul>" + items + "</ul>");
    }

    private String registrantItem(final Trip trip, final Registration row, final Locale locale) {
        final String type = row.getRegistrationType() == null ? Text.forLocale(locale, "common.registered")
                : row.getRegistrationType();
        final long price = row.getPriceBreakdown() == null ? 0L : row.getPriceBreakdown().getTotalCents();
        final String ticket = row.getStatus() == Registration.Status.CONFIRMED && row.getTicketCode() != null
                ? " &middot; " + ticketLink(trip, row.getTicketCode(), locale) : "";
        return "<li><b>" + MailTemplates.escape(nameOf(row.getUserId())) + "</b>: "
                + MailTemplates.escape(type) + ", " + MoneyMath.formatCents(price) + " ("
                + MailTemplates.escape(row.getStatus().getLabel(locale)) + ")" + ticket + "</li>";
    }

    /**
     * "ticket CODE", the code linking to the ticket page (its QR, for check-in) when the trip is known, with the
     * QR itself as an image under the link ({@code SiteUrls.ticketQrUrl}): the check-in table scans the email
     * directly, no page load on a venue's Wi-Fi.
     */
    static String ticketLink(final Trip trip, final String code, final Locale locale) {
        final String grouped = TicketCodes.grouped(code);
        final ConfigCommands config = new ConfigCommands();
        final String url = SiteUrls.ticketUrl(trip, code, config);
        if (url.isEmpty()) {
            return MailTemplates.html(locale, "mail.payment.ticket", grouped).html();
        }
        return MailTemplates.html(locale, "mail.payment.ticketLinked", url, grouped).html() + "<br/>"
                + qrImage(SiteUrls.ticketQrUrl(trip, code, config), locale);
    }

    /**
     * The {@code <img>} of a ticket's QR PNG, sized so a mail client reserves the box before the image loads. Shared
     * with the approval email ({@code RegistrationCommands.approvedMailValues}) so both emails carry one shape.
     */
    public static String qrImage(final String qrUrl, final Locale locale) {
        return "<img src=\"" + MailTemplates.escape(qrUrl) + "\" width=\"160\" height=\"160\" alt=\""
                + MailTemplates.escape(Text.forLocale(locale, "mail.ticket.qrAlt")) + "\">";
    }

    /** Whose confirmation copy this is: the payment's organization, else its trip's, else nobody's. */
    static String orgOf(final Payment payment, final Trip trip) {
        if (payment.getOrgId() != null && !payment.getOrgId().isBlank()) {
            return payment.getOrgId();
        }
        return trip == null ? null : trip.getOrgId();
    }

    /**
     * The template's token values -- scalars escaped by the mail layer, HTML pre-escaped here as Raw -- in the
     * payer's language, who is the one the mail is sent to.
     */
    public Map<String, Object> values(final Payment payment, final Trip trip,
            final TripPaymentConfig effective, final Person payer, final String orgName,
            final String processorName) {
        final Locale locale = ReaderLanguage.of(payer, trip);
        final Map<String, Object> values = new HashMap<>();
        effective.getExtraTokens().forEach(values::put);
        values.put("payerName", payer.getPreferredName() + " " + payer.getLast());
        values.put("tripTitle", (trip == null || trip.getTitle() == null) ? "" : trip.getTitle());
        values.put("totalPaid", MoneyMath.formatCents(payment.getTotalChargedCents()));
        values.put("feeNote", feeNote(payment, orgName, processorName, locale));
        values.put("donationAmount", MoneyMath.formatCents(payment.getDonationCents()));
        values.put("donationNote", payment.getDonationCents() > 0 ? Text.forLocale(locale,
                "mail.payment.donationThanks", MoneyMath.formatCents(payment.getDonationCents()), orgName) : "");
        values.put("captureId", payment.getCaptureId() == null ? "" : payment.getCaptureId());
        values.put("processorName", processorName);
        values.put("paymentDate", payment.getCapturedAt() == null ? ""
                : Formats.longDate(payment.getCapturedAt(), locale));
        values.put("orgName", orgName);
        values.put("amountsBlock", amountsBlock(payment));
        return values;
    }

    private static String feeNote(final Payment payment, final String orgName, final String processorName,
            final Locale locale) {
        if (payment.isPayerPaysFee() && payment.getCreditFeeCents() > 0) {
            return Text.forLocale(locale, "mail.payment.feeIncluded",
                    MoneyMath.formatCents(payment.getCreditFeeCents()), processorName);
        }
        return (payment.getCreditFeeCents() > 0)
                ? Text.forLocale(locale, "mail.payment.feeCovered", orgName, processorName)
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
