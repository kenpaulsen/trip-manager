package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.site.ListingScope;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.PaymentDAO;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Payment;
import org.paulsens.trip.model.PaymentProcessorConfig;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.ProcessorType;
import org.paulsens.trip.model.FeesPaidBy;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripPaymentConfig;
import org.paulsens.trip.pay.MoneyMath;
import org.paulsens.trip.pay.PaymentMailer;
import org.paulsens.trip.pay.PaymentProcessor;
import org.paulsens.trip.pay.PaymentProcessors;
import org.paulsens.trip.pay.PaymentRecorder;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.dynamo.LocalMode;
import org.paulsens.trip.pay.FakeProcessor;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * The payment flow, ALL of it in Java (a user-locked rule: the mir2026 xhtml-driven callback handling made
 * mobile impossible): quote &rarr; start (order + redirect, or an inline client secret for the page's Payment
 * Element / the apps' PaymentSheet) &rarr; complete (capture + record + mail) &rarr; cancel, plus the
 * site-admin reconciliation actions and the trusted {@code *FromWebhook} entry points Stripe's webhook
 * resource calls after verifying the event. The page and the REST resource are both thin callers of these
 * same methods.
 *
 * <p>Ordering on completion is what makes money safe here: capture first, then the conditional
 * {@code CREATED->CAPTURED} transition (double-submits serialize at the row), then gross verification (a
 * mismatch parks the payment in CAPTURED for a human), then the idempotent ledger write, then
 * {@code CAPTURED->RECORDED}, then mail (which may fail without consequence to the money). A crash anywhere
 * leaves a row the return page or the reconciliation list can finish.
 *
 * <p>SANDBOX (the {@code paymentsAdmin} toggle) runs the same pipeline against the config's sandbox
 * credential set, but the recorder dry-runs: no ledger rows, no mail, audits prefixed {@code [SANDBOX]},
 * and the would-have-written rows come back for the page's info dialog.
 */
@Slf4j
@Named("payment")
@ApplicationScoped
public class PaymentCommands {
    /** Stripe's name for a US bank debit: the one method with its own (lower) price. */
    private static final String BANK_METHOD = "us_bank_account";
    /** How {@link #paidWith} prints the day a payment arrived: "10/30/2026", the receipt's own format. */
    private static final java.time.format.DateTimeFormatter RECEIVED_DATE =
            java.time.format.DateTimeFormatter.ofPattern("M/d/yyyy", java.util.Locale.US);
    /** How many lost optimistic-lock races a refund delivery absorbs before asking for a redelivery. */
    static final int REFUND_ATTEMPTS = 4;

    private final Supplier<Caller> callerSource;
    private final OrgCommands orgs;
    private final AuditCommands audit;
    private final PaymentRecorder recorder;
    private final BiFunction<PaymentProcessorConfig, Boolean, PaymentProcessor> processors;
    private final Supplier<PaymentMailer> mailerSource;
    /** Priced checkout's finalize step (docs/checkout.md); created lazily, it builds a PaymentCommands too. */
    private final Supplier<CheckoutCommands> checkoutSource;

    public PaymentCommands() {
        this(Caller::current);
    }

    /** The REST edge's constructor: same defaults, but the caller comes from the servlet session. */
    public PaymentCommands(final Supplier<Caller> callerSource) {
        this(callerSource, new OrgCommands(callerSource), new AuditCommands(), new PaymentRecorder(),
                PaymentProcessors::forConfig,
                () -> new PaymentMailer(org.paulsens.trip.api.Beans.get(MailCommands.class),
                        id -> DAO.getInstance().getPerson(id, Cached.YES).orElse(null)));
    }

    /** Test seam: every collaborator handed in. */
    public PaymentCommands(final Supplier<Caller> callerSource, final OrgCommands orgs,
            final AuditCommands audit, final PaymentRecorder recorder,
            final BiFunction<PaymentProcessorConfig, Boolean, PaymentProcessor> processors,
            final Supplier<PaymentMailer> mailerSource) {
        this(callerSource, orgs, audit, recorder, processors, mailerSource, () -> new CheckoutCommands(callerSource));
    }

    /** Test seam with the checkout finalizer handed in too. */
    public PaymentCommands(final Supplier<Caller> callerSource, final OrgCommands orgs,
            final AuditCommands audit, final PaymentRecorder recorder,
            final BiFunction<PaymentProcessorConfig, Boolean, PaymentProcessor> processors,
            final Supplier<PaymentMailer> mailerSource, final Supplier<CheckoutCommands> checkoutSource) {
        this.callerSource = callerSource;
        this.orgs = orgs;
        this.audit = audit;
        this.recorder = recorder;
        this.processors = processors;
        this.mailerSource = mailerSource;
        this.checkoutSource = checkoutSource;
    }

    // ------------------------------------------------------------------ page-facing views

    /**
     * Whether the signed-in user may flip the page into sandbox mode for THIS trip: exactly the trip's
     * {@link #isPaymentsAdmin payments admins}, since sandbox mode exercises the org's sandbox credentials.
     */
    public boolean isSandboxAllowed(final Trip trip) {
        return isPaymentsAdmin(trip);
    }

    /**
     * Whether the signed-in user is a payments admin for THIS trip: site admin, or a holder of {@code
     * paymentsAdmin} scoped to the trip's org (the grant follows the org -- the global variant is retired, org
     * migration 2026-08). It opens the trip editor's Payment Settings and the payment page's sandbox mode. An
     * org-less legacy trip has no org to hold the grant, so only site admins pass there.
     */
    public boolean isPaymentsAdmin(final Trip trip) {
        final Caller current = caller();
        if (current.isSiteAdmin()) {
            return true;
        }
        if (trip == null || trip.getOrgId() == null || trip.getOrgId().isBlank()) {
            return false;
        }
        return current.has(PrivilegeCommands.PAYMENTS_ADMIN, trip.getOrgId());
    }

    /**
     * The payment page's FROZEN member key list: the signed-in payer first, then (for a family manager)
     * every family member. Ids as strings -- the page's amount inputs bind into a map keyed by these.
     */
    public List<String> getPayMemberIds() {
        final Person me = currentPerson();
        if (me == null) {
            return List.of();
        }
        final List<String> ids = new ArrayList<>();
        ids.add(me.getId().getValue());
        final org.paulsens.trip.model.Family fam = (me.getFamilyId() == null) ? null
                : DAO.getInstance().getFamily(me.getFamilyId(), Cached.NO).orElse(null);
        if (fam != null && fam.isManager(me.getId())) {
            fam.getMemberIds().stream()
                    .map(Person.Id::getValue)
                    .filter(id -> !ids.contains(id))
                    .forEach(ids::add);
        }
        return ids;
    }

    /** A fresh amounts map for the page's view state (ids &rarr; typed dollar text; scalars only). */
    public Map<String, Object> newAmounts() {
        return new java.util.HashMap<>();
    }

    /** The live totals under the inputs -- recomputed server-side on every change (money math stays here). */
    public Quote quote(final Trip trip, final Map<String, Object> rawAmounts, final Object rawDonation) {
        final TripPaymentConfig effective = orgs.effectivePaymentConfig(trip);
        final PaymentProcessorConfig config = resolveConfig(trip, effective);
        final Quote quote = new Quote();
        quote.payable = config != null;
        quote.payerPays = effective.getFeesPaidBy() == FeesPaidBy.PAYER;
        quote.creditCents = sumAmounts(rawAmounts);
        quote.donationCents = effective.isDonationOn() ? parseLenient(rawDonation) : 0L;
        final Fees fees = feesFor(config, quote.payerPays, quote.creditCents, quote.donationCents, false);
        quote.creditFeeCents = fees.creditFeeCents();
        quote.donationFeeCents = fees.donationFeeCents();
        quote.totalCents = fees.totalCents();
        quote.processorLabel = (config == null) ? "" : displayNameOf(config);
        return quote;
    }

    // ------------------------------------------------------------------ start

    /**
     * Validates, persists the CREATED payment, audits the start, creates the processor order, and answers how
     * the payer completes it -- an approval URL to redirect to (PayPal, the fake checkout) or the inline
     * client secret the page's Payment Element / the apps' PaymentSheet confirm in place (Stripe) -- or null
     * with a growl. {@code pageUrl} is the payment page's own absolute URL; the processor return/cancel URLs
     * derive from it ({@code ?trip=..&payment=..}, which PayPal preserves and re-appends its token to).
     */
    public Start startPayment(final Trip trip, final Map<String, Object> rawAmounts,
            final Object rawDonation, final boolean sandbox, final String pageUrl) {
        final Person payer = currentPerson();
        if (payer == null || trip == null) {
            return failNull("Not signed in", "Sign in to make a payment.");
        }
        final TripPaymentConfig effective = orgs.effectivePaymentConfig(trip);
        final PaymentProcessorConfig config = resolveConfig(trip, effective);
        if (config == null) {
            return failNull("Payments not configured", "This trip is not set up to take payments.");
        }
        if (sandbox && !isSandboxAllowed(trip)) {
            return failNull("Not allowed", "Sandbox mode requires the paymentsAdmin privilege for this "
                    + "trip's organization.");
        }
        final List<Payment.Allocation> allocations = parseAllocations(payer, rawAmounts);
        if (allocations == null) {
            return null;    // parseAllocations already growled
        }
        final long donation = effective.isDonationOn() ? parseStrict(rawDonation) : 0L;
        if (donation < 0) {
            return failNull("Bad amount", "The donation amount is not a valid dollar amount.");
        }
        final long credits = allocations.stream().mapToLong(Payment.Allocation::getAmountCents).sum();
        if (credits <= 0 && donation <= 0) {
            return failNull("Nothing to pay", "Enter an amount for at least one traveler, or a donation.");
        }
        final Payment payment = newPayment(trip, payer, config, effective, allocations, donation, sandbox);
        return startPrepared(trip, payment, config, payer, pageUrl, trip.getTitle() + " - payment");
    }

    /** A payment's fee shares and the total to charge. */
    record Fees(long creditFeeCents, long donationFeeCents, long totalCents) {
    }

    /**
     * The ONE computation of a payment's fees and total: the live quote, the stored payment, a method re-price
     * and the checkout quote all read it, so the figure a payer agrees to is the figure the capture's
     * gross check expects. {@code bank} prices a US bank debit at the processor type's bank rate and cap; the
     * card rate is the config's. A null config (the trip takes no payments) carries no fee.
     */
    static Fees feesFor(final PaymentProcessorConfig config, final boolean payerPays, final long creditCents,
            final long donationCents, final boolean bank) {
        if (config == null) {
            return new Fees(0L, 0L, MoneyMath.totalChargedCents(creditCents, 0L, donationCents, payerPays));
        }
        final ProcessorType type = config.getType();
        final long creditFee = bank
                ? MoneyMath.bankCreditFeeCents(creditCents, type.getBankFeeBps(), type.getBankFeeCapCents())
                : MoneyMath.creditFeeCents(creditCents, config.getEffectiveFeeBps(),
                        config.getEffectiveFeeFixedCents());
        final long donationFee = bank
                ? MoneyMath.bankDonationFeeCents(donationCents, type.getBankFeeBps(), type.getBankFeeCapCents())
                : MoneyMath.donationFeeCents(donationCents, config.getEffectiveFeeBps());
        return new Fees(creditFee, donationFee,
                MoneyMath.totalChargedCents(creditCents, creditFee, donationCents, payerPays));
    }

    /**
     * A CREATED payment for these credits and donation, its fees per the effective config ({@link #feesFor}),
     * for the payment page and priced checkout alike.
     */
    Payment newPayment(final Trip trip, final Person payer, final PaymentProcessorConfig config,
            final TripPaymentConfig effective, final List<Payment.Allocation> allocations, final long donation,
            final boolean sandbox) {
        final long credits = allocations.stream().mapToLong(Payment.Allocation::getAmountCents).sum();
        final Fees fees = feesFor(config, effective.getFeesPaidBy() == FeesPaidBy.PAYER, credits, donation, false);
        return Payment.builder()
                .tripId(trip.getId())
                .orgId(trip.getOrgId())
                .payerId(payer.getId())
                .processorConfigId(config.getId().getValue())
                .processorType(config.getType())
                .allocations(allocations)
                .donationCents(donation)
                .feesPaidBy(effective.getFeesPaidBy())
                // Stored whoever pays it: the receipt and the mailer report the fee in the organization-pays case
                // too, and totalChargedCents (below) is what takes payerPays into account.
                .creditFeeCents(fees.creditFeeCents())
                .donationFeeCents(fees.donationFeeCents())
                .totalChargedCents(fees.totalCents())
                .sandbox(sandbox)
                .createdAt(LocalDateTime.now())
                .build();
    }

    /**
     * Persists a built CREATED payment, audits the start, creates the processor order and answers how the payer
     * completes it (the second half of {@link #startPayment}, shared with priced checkout, whose payment carries
     * its pending party).
     */
    Start startPrepared(final Trip trip, final Payment payment, final PaymentProcessorConfig config,
            final Person payer, final String pageUrl, final String orderDescription) {
        final Caller current = caller();
        try {
            DAO.getInstance().createPayment(payment);
        } catch (final RuntimeException | IOException ex) {
            log.error("Unable to persist payment for {}", payer.getId(), ex);
            return failNull("Unable to start", "The payment could not be started; nothing was charged.");
        }
        audit.payment(payment, true, sandboxTag(payment) + "Payment started by "
                + payer.getEmail() + ": " + MoneyMath.formatCents(payment.getTotalChargedCents())
                + " to trip " + trip.getId() + " (" + payment.getAllocations().size() + " traveler(s), donation "
                + MoneyMath.formatCents(payment.getDonationCents()) + ", via " + config.getDisplayLabel()
                + (payment.hasCheckout() ? ", registration checkout" : "") + ")",
                current.auditActor());
        try {
            final PaymentProcessor processor = processors.apply(config, payment.isSandbox());
            final String base = pageUrl + "?trip=" + trip.getId() + "&payment=" + payment.getPaymentId();
            final PaymentProcessor.CreatedOrder order = processor.createOrder(payment, orderDescription, base,
                    base + "&cancelled=true");
            payment.setOrderRef(order.orderRef());
            DAO.getInstance().transitionPayment(payment, Payment.Status.CREATED);
            return Start.of(payment, order, orgNameOf(payment), payer.getEmail());
        } catch (final PaymentProcessor.ProcessorException ex) {
            return startFailed(payment, current, ex.getMessage());
        } catch (final RuntimeException | IOException ex) {
            log.error("Order creation failed for payment {}", payment.getPaymentId(), ex);
            return startFailed(payment, current, "The payment processor could not be reached.");
        }
    }

    // ------------------------------------------------------------------ complete / cancel

    /** The return-URL callback (and the reconciliation retry): capture, verify, record, mail. */
    public Completion completePayment(final String paymentId, final String token) {
        final Caller current = caller();
        final Payment payment = DAO.getInstance().getPayment(paymentId).orElse(null);
        if (payment == null || !mayComplete(current, payment)) {
            return Completion.of("notfound", "Unknown payment.");
        }
        return complete(payment, token, current, Door.PAYER);
    }

    /**
     * Who may ACT on a payment (finish it from a return page, cancel it, re-price it for a method, see whom it
     * registers): its payer, a site admin, and for a registration checkout the trip's registration admins too
     * (an admin paying on a family's behalf comes back to their own page). The one spelling of the rule: the
     * re-price had its own copy without the registration admin, and charged a family the card total for a
     * bank debit.
     */
    boolean mayComplete(final Caller current, final Payment payment) {
        if (current.isSiteAdmin()) {
            return true;
        }
        if (current.isAuthenticated() && payment.getPayerId() != null
                && payment.getPayerId().equals(current.personId())) {
            return true;
        }
        return payment.hasCheckout() && current.has(PrivilegeCommands.REGISTRATION_ADMIN, payment.getTripId());
    }

    /**
     * Records a payment taken OUTSIDE any processor (docs/checkout.md): a check, cash or a comp an admin entered,
     * or a party whose total is $0. The payment arrives built and already CAPTURED (its capture id is the
     * method's label, its gross what was received) and runs the one completion path, so it records, registers
     * and mails exactly like a processor payment. Callers authorize (CheckoutCommands).
     */
    Completion recordOffline(final Payment payment) {
        final Caller current = caller();
        try {
            DAO.getInstance().createPayment(payment);
        } catch (final RuntimeException | IOException ex) {
            log.error("Unable to persist offline payment {}", payment.getPaymentId(), ex);
            return Completion.of("failed", "The registration could not be saved; try again.");
        }
        audit.payment(payment, true, sandboxTag(payment) + "Offline payment entered: "
                + MoneyMath.formatCents(payment.getCapturedGrossCents()) + " (" + payment.getCaptureId() + ") to trip "
                + payment.getTripId(), current.auditActor());
        return complete(payment, null, current, Door.PAYER).withOffline(payment);
    }

    /** Who is finishing a payment: decides what a held payment, or one already given up here, may do. */
    private enum Door {
        /** The payer's return page or REST completion (and an offline entry): never records a held payment. */
        PAYER,
        /** The processor's webhook: also notices money arriving for a payment that was given up here. */
        WEBHOOK,
        /** A site administrator's Finish: the one door that records a held payment. */
        ADMIN
    }

    private Completion complete(final Payment payment, final String token, final Caller current, final Door door) {
        switch (payment.getStatus()) {
            case RECORDED -> {
                if (payment.hasCheckout() && payment.getCheckout().getFinalizedAt() == null && !payment.isSandbox()) {
                    // Recorded by a build that predates checkout (a rolling deploy): the money is in, the
                    // registrations are owed. Finalizing is idempotent, so this only ever completes the job.
                    return finalizeRecorded(payment, current);
                }
                return recordedOutcome(payment, "Payment already recorded.");
            }
            case CANCELLED, FAILED -> {
                return (door == Door.WEBHOOK) ? paidAfterGivenUp(payment, current) : givenUpOutcome(payment);
            }
            case CREATED -> {
                final Completion captured = captureNow(payment, token, current, door);
                if (captured != null) {
                    return captured;
                }
            }
            case CAPTURED -> {
                // Crash recovery: money moved, the ledger write is owed -- unless the payment is HELD, which
                // only an administrator's Finish releases. Until 2026-11 nothing remembered the hold, so the
                // webhook (or a reload of the return page) recorded a mismatched payment at the expected amount.
                if (door != Door.ADMIN && isHeld(payment)) {
                    return heldOutcome(payment, door);
                }
            }
            default -> {
            }
        }
        return recordNow(payment, current);
    }

    private static Completion givenUpOutcome(final Payment payment) {
        return payment.getStatus() == Payment.Status.CANCELLED
                ? Completion.of("cancelled", "This payment was cancelled; nothing was charged.")
                : Completion.of("failed", "This payment failed; nothing was charged.");
    }

    /**
     * Whether a CAPTURED payment waits for an administrator. Rows captured before the reason was stored
     * (2026-11) are told by their amounts; an offline payment's gross is whatever was received, by design.
     */
    private static boolean isHeld(final Payment payment) {
        if (payment.getHeldReason() != null) {
            return true;
        }
        return payment.getProcessorType() != ProcessorType.OFFLINE
                && payment.getCapturedGrossCents() != payment.getTotalChargedCents();
    }

    /** Why the reconciliation page's row is held, for its Status column; "" when it is not. */
    public String holdOf(final Payment payment) {
        if (payment == null || payment.getStatus() != Payment.Status.CAPTURED || !isHeld(payment)) {
            return "";
        }
        return payment.getHeldReason() != null ? payment.getHeldReason() : mismatchReason(payment);
    }

    private static String mismatchReason(final Payment payment) {
        return "the processor captured " + MoneyMath.formatCents(payment.getCapturedGrossCents())
                + ", not the expected " + MoneyMath.formatCents(payment.getTotalChargedCents());
    }

    private Completion heldOutcome(final Payment payment, final Door door) {
        if (door == Door.ADMIN) {
            return Completion.of("mismatch", "Held: " + holdOf(payment) + ". Finish it again to record it as "
                    + "expected, or refund it at the processor.");
        }
        return Completion.of("mismatch", "Your payment was received and is waiting for an administrator to "
                + "review it. You will not be charged again.");
    }

    /**
     * The webhook says a payment that was given up here (CANCELLED or FAILED) has been PAID: the payer's saved
     * form completed it after a cancel, or a debit cleared that was abandoned while it settled. The money is
     * real, so the row comes back as CAPTURED and HELD (the reconciliation list shows it; an administrator
     * finishes or refunds it) instead of answering "cancelled" and letting the processor stop redelivering.
     * Reached only from the webhook door, whose processors' capture is a read.
     */
    private Completion paidAfterGivenUp(final Payment payment, final Caller current) {
        final Payment.Status was = payment.getStatus();
        final PaymentProcessor.CaptureResult result = readCapture(payment);
        if (result == null || (result.status() != PaymentProcessor.CaptureResult.Status.COMPLETED
                && result.status() != PaymentProcessor.CaptureResult.Status.ALREADY_CAPTURED)) {
            return givenUpOutcome(payment);
        }
        applyCapture(payment, result);
        payment.setHeldReason("paid at the processor after the payment was "
                + was.name().toLowerCase(java.util.Locale.ROOT) + " here");
        payment.setStatus(Payment.Status.CAPTURED);
        try {
            DAO.getInstance().transitionPayment(payment, was);
        } catch (final ConditionalCheckFailedException ex) {
            return adoptWinner(payment, null, current, Door.WEBHOOK);
        } catch (final RuntimeException | IOException ex) {
            log.error("Unable to bring paid payment {} back from {}", payment.getPaymentId(), was, ex);
            return Completion.of("pending", "The payment could not be updated; try again.");
        }
        audit.payment(payment, false, sandboxTag(payment) + "The processor reports "
                + MoneyMath.formatCents(result.grossCents()) + " PAID (capture id " + result.captureId()
                + ") for a payment that was " + was + " here; held as CAPTURED for an administrator to finish "
                + "or refund", current.auditActor());
        return heldOutcome(payment, Door.WEBHOOK);
    }

    /** The processor's answer about the payment's order, or null when the processor cannot be built or asked. */
    private PaymentProcessor.CaptureResult readCapture(final Payment payment) {
        if (payment.getOrderRef() == null) {
            return null;
        }
        try {
            return processors.apply(configOf(payment), payment.isSandbox()).capture(payment.getOrderRef());
        } catch (final RuntimeException ex) {
            log.warn("The processor could not be asked about payment {}: {}", payment.getPaymentId(),
                    ex.getMessage());
            return null;
        }
    }

    private static void applyCapture(final Payment payment, final PaymentProcessor.CaptureResult result) {
        payment.setCaptureId(result.captureId());
        payment.setCapturedGrossCents(result.grossCents());
        payment.setActualFeeCents(result.actualFeeCents().isPresent()
                ? result.actualFeeCents().getAsLong() : null);
        payment.setCapturedAt(LocalDateTime.now());
    }

    /** Runs the capture; null means "captured, continue to recording", anything else is the outcome. */
    private Completion captureNow(final Payment payment, final String token, final Caller current,
            final Door door) {
        final PaymentProcessorConfig config = configOf(payment);
        final PaymentProcessor processor;
        try {
            processor = processors.apply(config, payment.isSandbox());
        } catch (final PaymentProcessor.ProcessorException ex) {
            return Completion.of("failed", ex.getMessage());
        }
        final String orderRef = (payment.getOrderRef() != null) ? payment.getOrderRef() : token;
        final PaymentProcessor.CaptureResult result = processor.capture(orderRef);
        switch (result.status()) {
            case COMPLETED, ALREADY_CAPTURED -> { }
            case PENDING -> {
                if (result.isSettling()) {
                    // A bank debit in flight: nothing more from the payer; the webhook (or an admin's
                    // Finish) records it when it clears. The reference lets the page name it.
                    audit.payment(payment, true, sandboxTag(payment) + "Payment accepted and processing at "
                            + displayNameOf(config) + " (reference " + result.captureId() + ")",
                            current.auditActor());
                    return Completion.of("pending", "Your payment method was accepted and the payment is "
                            + "processing (bank payments can take several business days). We will email you "
                            + "when it clears; nothing more is needed from you.").withReference(payment);
                }
                return Completion.of("pending", "The payment has not completed at the processor yet. "
                        + "If you approved it, try again in a moment.").withReference(payment);
            }
            default -> {
                markFailed(payment, current, "capture failed at the processor");
                return Completion.of("failed", "The payment could not be completed; you were not charged.");
            }
        }
        applyCapture(payment, result);
        final boolean mismatch = result.grossCents() != payment.getTotalChargedCents();
        if (mismatch) {
            // Stored WITH the capture, in the same put: the hold has to outlive this request.
            payment.setHeldReason(mismatchReason(payment));
        }
        payment.setStatus(Payment.Status.CAPTURED);
        try {
            DAO.getInstance().transitionPayment(payment, Payment.Status.CREATED);
        } catch (final ConditionalCheckFailedException ex) {
            return adoptWinner(payment, token, current, door);
        } catch (final RuntimeException | IOException ex) {
            log.error("Unable to persist capture for payment {}", payment.getPaymentId(), ex);
        }
        if (mismatch) {
            audit.payment(payment, false, sandboxTag(payment)
                    + "Captured amount " + MoneyMath.formatCents(result.grossCents())
                    + " does not match the expected " + MoneyMath.formatCents(payment.getTotalChargedCents())
                    + "; NOT recorded (held for an administrator)", current.auditActor());
            return heldOutcome(payment, door);
        }
        noteMethodMismatch(payment, result, config, current);
        return null;
    }

    /**
     * A method other than the one the total was priced for paid it (a card at the bank-debit price). The
     * processor is asked to refuse that ({@code PaymentProcessor.reprice}'s lock), so this is the case where it
     * would not or could not: the money is in at the lower total, so the payment is recorded as paid and the
     * shortfall is put on the audit trail for the organization (owner's decision, 2026-11-12).
     */
    private void noteMethodMismatch(final Payment payment, final PaymentProcessor.CaptureResult result,
            final PaymentProcessorConfig config, final Caller current) {
        final String pricedFor = payment.getPricedForMethod();
        if (pricedFor == null || result.methodType() == null || pricedFor.equals(result.methodType())) {
            return;
        }
        final long anyMethodTotal = feesFor(config, payment.isPayerPaysFee(), payment.getCreditTotalCents(),
                payment.getDonationCents(), false).totalCents();
        audit.payment(payment, false, sandboxTag(payment) + "Paid with " + result.methodType()
                + " at the total priced for " + pricedFor + ": " + MoneyMath.formatCents(result.grossCents())
                + " collected, " + MoneyMath.formatCents(anyMethodTotal - payment.getTotalChargedCents())
                + " less than the " + MoneyMath.formatCents(anyMethodTotal) + " that method is charged; recorded "
                + "as paid, the organization absorbs the difference", current.auditActor());
    }

    private Completion recordNow(final Payment payment, final Caller current) {
        final Trip trip = DAO.getInstance().getTrip(payment.getTripId(), Cached.NO).orElse(null);
        final String orgName = orgNameOf(payment);
        final PaymentProcessorConfig config = configOf(payment);
        final String processorName = processorNameOf(payment, config);
        final PaymentRecorder.Result result;
        try {
            result = recorder.record(payment, orgName, processorName);
            if (payment.hasCheckout()) {
                // The party's registrations and Bills, in the SAME guarded step as the ledger write: a failure
                // leaves the row CAPTURED for the reconciliation Finish, which re-runs both idempotently. Before
                // the RECORDED transition, so that put also stores what finalize stamped on the party.
                checkoutSource.get().finalizeParty(payment, trip);
            }
        } catch (final RuntimeException ex) {
            // Money HAS moved; the payment stays CAPTURED and the reconciliation list owns it now.
            log.error("Ledger write failed for CAPTURED payment {}", payment.getPaymentId(), ex);
            audit.payment(payment, false, sandboxTag(payment)
                    + "Ledger write failed after capture; payment left in CAPTURED for reconciliation",
                    current.auditActor());
            return Completion.of("mismatch", "Your payment was received, but recording it hit a problem. "
                    + "An administrator will finish it up; you will not be charged again.");
        }
        payment.setTxIds(result.txIds());
        payment.setRecordedAt(LocalDateTime.now());
        payment.setStatus(Payment.Status.RECORDED);
        try {
            DAO.getInstance().transitionPayment(payment, Payment.Status.CAPTURED);
        } catch (final ConditionalCheckFailedException ex) {
            // The webhook and the return page raced to record (the ledger write was idempotent, so the rows
            // are the same): the winner owns the mail, the push and the audit line. Answer ITS outcome and
            // do none of them again -- until 2026-11 the loser mailed the payer a second confirmation.
            log.info("Payment {} was recorded by a concurrent writer", payment.getPaymentId());
            return adoptWinner(payment, null, current, Door.PAYER);
        } catch (final RuntimeException | IOException ex) {
            log.error("Unable to persist RECORDED for payment {}", payment.getPaymentId(), ex);
        }
        if (!payment.isSandbox()) {
            // The payer's phone/browser hears first; off-thread and never able to fail the record.
            org.paulsens.trip.push.PushNotifications.getInstance().paymentRecorded(payment, trip, orgName);
            final TripPaymentConfig effective = orgs.effectivePaymentConfig(trip);
            final boolean mailed = payment.hasCheckout()
                    ? mailerSource.get().sendCheckoutConfirmation(payment, trip, effective, orgName, processorName,
                            notifyEmail(), current.auditActor())
                    : mailerSource.get().sendConfirmation(payment, trip, effective, orgName, processorName,
                            notifyEmail(), current.auditActor());
            if (!mailed) {
                audit.payment(payment, false, "Confirmation email was not sent",
                        current.auditActor());
            }
        }
        audit.payment(payment, true, sandboxTag(payment) + "Payment recorded: "
                + MoneyMath.formatCents(payment.getCapturedGrossCents()) + " (capture id "
                + payment.getCaptureId() + "), " + result.txIds().size() + " ledger row(s)"
                + (payment.getHeldReason() == null ? ""
                        : "; it was held (" + payment.getHeldReason() + ") and an administrator finished it"),
                current.auditActor());
        return recordedOutcome(payment, sandboxTag(payment).isEmpty()
                ? "Payment received - thank you!"
                : "Sandbox payment completed; NO ledger rows were written.");
    }

    /** The RECORDED repair: finalize a checkout's party and store the stamp; never re-mails (the record did). */
    private Completion finalizeRecorded(final Payment payment, final Caller current) {
        final Trip trip = DAO.getInstance().getTrip(payment.getTripId(), Cached.NO).orElse(null);
        try {
            checkoutSource.get().finalizeParty(payment, trip);
            DAO.getInstance().transitionPayment(payment, Payment.Status.RECORDED);
        } catch (final RuntimeException | IOException ex) {
            log.error("Unable to finalize the registrations of recorded payment {}", payment.getPaymentId(), ex);
            return Completion.of("mismatch", "Your payment was received, but registering it hit a problem. "
                    + "An administrator will finish it up; you will not be charged again.");
        }
        audit.payment(payment, true, "Registrations written for a payment recorded earlier", current.auditActor());
        return recordedOutcome(payment, "Payment already recorded.");
    }

    /**
     * A conditional transition lost to a concurrent writer (the return page vs the webhook vs an admin's
     * Finish): re-read the row and continue from wherever the winner got to. A RECORDED winner answers its
     * outcome with NO side effects, since the winner already mailed, pushed and audited.
     */
    private Completion adoptWinner(final Payment payment, final String token, final Caller current,
            final Door door) {
        final Payment winner = DAO.getInstance().getPayment(payment.getPaymentId()).orElse(null);
        if (winner == null) {
            return Completion.of("failed", "The payment state could not be read.");
        }
        return complete(winner, token, current, door);
    }

    /** The cancel-URL callback: only an un-captured payment can cancel. */
    public boolean cancelPayment(final String paymentId) {
        final Caller current = caller();
        final Payment payment = DAO.getInstance().getPayment(paymentId).orElse(null);
        // Whoever may complete it may cancel it: that includes the registration admin who started a checkout
        // on someone's behalf (its payer is the person registered), whose "Change registration" cancels here.
        if (payment == null || payment.getStatus() != Payment.Status.CREATED || !mayComplete(current, payment)) {
            return false;
        }
        return giveUp(payment, current, "Payment cancelled by the payer");
    }

    /**
     * Gives a CREATED payment up: the PROCESSOR first, and our row only once it says nothing of the payer's is
     * held or in flight. The order used to be the other way round with a processor cancel that could not fail,
     * so a bank debit abandoned while it settled (it looks exactly like a walked-away payment here) cleared
     * days later into a CANCELLED row: money in, no ledger credit, nothing left to reconcile. A payment the
     * processor already has is finished instead of cancelled. False, with the reason growled, when it was not
     * cancelled.
     */
    private boolean giveUp(final Payment payment, final Caller current, final String note) {
        return switch (releaseAtProcessor(payment)) {
            case OPEN -> markCancelled(payment, current, note);
            case PAID -> finishInstead(payment, current);
            case SETTLING -> fail("Not cancelled", "This payment is already processing at the bank and can no "
                    + "longer be cancelled. It will be recorded when it clears.");
            case UNKNOWN -> fail("Not cancelled", "The payment processor could not be reached to cancel this "
                    + "payment. Try again in a moment.");
        };
    }

    private boolean finishInstead(final Payment payment, final Caller current) {
        complete(payment, null, current, Door.PAYER);
        return fail("Not cancelled", "This payment had already gone through, so it was recorded instead of "
                + "cancelled.");
    }

    /** CREATED -> CANCELLED, once the processor holds nothing for it (or said so itself, by webhook). */
    private boolean markCancelled(final Payment payment, final Caller current, final String note) {
        payment.setStatus(Payment.Status.CANCELLED);
        try {
            DAO.getInstance().transitionPayment(payment, Payment.Status.CREATED);
        } catch (final RuntimeException | IOException ex) {
            log.warn("Unable to mark payment {} cancelled", payment.getPaymentId(), ex);
            return false;
        }
        audit.payment(payment, true, sandboxTag(payment) + note, current.auditActor());
        return true;
    }

    private PaymentProcessor.OrderState releaseAtProcessor(final Payment payment) {
        if (payment.getOrderRef() == null) {
            return PaymentProcessor.OrderState.OPEN;
        }
        final PaymentProcessor processor;
        try {
            processor = processors.apply(configOf(payment), payment.isSandbox());
        } catch (final RuntimeException ex) {
            // No usable credentials (a removed or half-entered config): the processor can never be asked, and a
            // payment that can never complete must not stay un-cancellable forever.
            log.warn("No processor to cancel payment {} at: {}", payment.getPaymentId(), ex.getMessage());
            return PaymentProcessor.OrderState.OPEN;
        }
        try {
            return processor.cancel(payment.getOrderRef());
        } catch (final RuntimeException ex) {
            log.warn("Processor cancel failed for payment {}: {}", payment.getPaymentId(), ex.getMessage());
            return PaymentProcessor.OrderState.UNKNOWN;
        }
    }

    // ------------------------------------------------------------------ webhook entry points (trusted)

    /**
     * The webhook resource's door, after it has verified Stripe's signature and matched the event to this
     * row: finish the payment under the system actor. Amounts are never taken from the event; the normal
     * capture path re-reads the intent, so a forged or stale event can at worst trigger a verification read.
     */
    public Completion completeFromWebhook(final String paymentId) {
        final Payment payment = DAO.getInstance().getPayment(paymentId).orElse(null);
        if (payment == null) {
            return Completion.of("notfound", "Unknown payment.");
        }
        return complete(payment, null, systemCaller(), Door.WEBHOOK);
    }

    /**
     * The processor says the payer's bank refused the debit after accepting it (Stripe's
     * {@code payment_intent.payment_failed} for a bank account): CREATED -> FAILED, audited, and the payer is
     * told by mail -- FAILED is terminal and leaves the reconciliation list, so without this nobody would.
     * False when the payment is not CREATED (already recorded, cancelled, or failed).
     */
    public boolean failFromWebhook(final String paymentId, final String reason) {
        final Payment payment = DAO.getInstance().getPayment(paymentId).orElse(null);
        if (payment == null || payment.getStatus() != Payment.Status.CREATED) {
            return false;
        }
        final Caller current = systemCaller();
        payment.setStatus(Payment.Status.FAILED);
        try {
            DAO.getInstance().transitionPayment(payment, Payment.Status.CREATED);
        } catch (final RuntimeException | IOException ex) {
            log.warn("Unable to mark payment {} failed from the webhook", payment.getPaymentId(), ex);
            return false;
        }
        audit.payment(payment, false, sandboxTag(payment) + "Payment failed at the processor (via webhook): "
                + reason, current.auditActor());
        if (!payment.isSandbox()) {
            final Trip trip = DAO.getInstance().getTrip(payment.getTripId(), Cached.NO).orElse(null);
            final boolean mailed = mailerSource.get().sendPaymentFailed(payment, trip,
                    orgs.effectivePaymentConfig(trip), orgNameOf(payment), displayNameOf(configOf(payment)),
                    reason, current.auditActor());
            if (!mailed) {
                audit.payment(payment, false, "Payment-failed email was not sent", current.auditActor());
            }
        }
        return true;
    }

    /**
     * A failure that is NOT terminal for us -- a declined card the payer is retrying inside the Element or
     * the sheet, on the same intent. Audit only; the row stays CREATED and retryable.
     */
    public void noteFailure(final String paymentId, final String reason) {
        final Payment payment = DAO.getInstance().getPayment(paymentId).orElse(null);
        if (payment == null) {
            return;
        }
        audit.payment(payment, false, sandboxTag(payment) + "Payment attempt failed at the processor (via "
                + "webhook), payer may retry: " + reason, systemCaller().auditActor());
    }

    /** The processor reports the intent canceled (Stripe's {@code payment_intent.canceled}): CREATED -> CANCELLED. */
    public boolean cancelFromWebhook(final String paymentId) {
        final Payment payment = DAO.getInstance().getPayment(paymentId).orElse(null);
        if (payment == null || payment.getStatus() != Payment.Status.CREATED) {
            return false;
        }
        return markCancelled(payment, systemCaller(), "Payment cancelled at the processor (via webhook)");
    }

    /**
     * The processor says money went BACK (Stripe's {@code charge.refunded}, after an organization refunds in
     * its dashboard): re-read the cumulative refunded amount from the processor with the organization's own
     * key (never from the event) and write, or rewrite, the ONE refund row on the payer's ledger
     * ({@code PaymentRecorder.recordRefund}). Only a RECORDED payment can be refunded against the ledger;
     * anything else is audited and left alone. Statuses: {@code refunded} (row written), {@code sandbox}
     * (computed, nothing written), {@code unchanged} (nothing refunded, or not RECORDED), {@code pending}
     * (the processor could not be read, or the lock below never settled -- the webhook answers 503 so Stripe
     * redelivers), {@code notfound}.
     *
     * <p><b>Idempotency under concurrent deliveries.</b> Stripe may deliver the same event twice, and two
     * refunds made seconds apart deliver two events that run at the same time. Exact duplicates converge on
     * their own (same cumulative total, same row id, a put). The dangerous interleaving is a delivery that
     * read the processor BEFORE a second refund and writes its row AFTER the delivery that read the later
     * total: the ledger would end at the older figure with no further event to correct it. So the stored
     * {@code refundedCents} is an optimistic lock: the ledger row is written first, then the payment is
     * replaced only if it is byte-for-byte what this delivery read ({@code replacePaymentIfUnchanged}); a
     * lost race re-reads BOTH the payment and the processor and goes again, and because every pass rewrites
     * the ledger row, the loser's stale row is repaired by its own retry. Bounded attempts; exhaustion is a
     * {@code pending} (redelivery), never a silent drop.
     */
    public Completion refundFromWebhook(final String paymentId) {
        for (int attempt = 0; attempt < REFUND_ATTEMPTS; attempt++) {
            final Completion outcome = refundOnce(paymentId);
            if (outcome != null) {
                return outcome;
            }
        }
        log.warn("Refund for payment {} lost {} races in a row; asking for a redelivery", paymentId,
                REFUND_ATTEMPTS);
        return Completion.of("pending", "The refund could not be recorded; try again.");
    }

    /** One pass of {@link #refundFromWebhook}: the outcome, or null when the optimistic replace lost. */
    private Completion refundOnce(final String paymentId) {
        final PaymentDAO.Stored stored = DAO.getInstance().getPaymentStored(paymentId).orElse(null);
        if (stored == null) {
            return Completion.of("notfound", "Unknown payment.");
        }
        final Payment payment = stored.payment();
        final Caller current = systemCaller();
        if (payment.getStatus() != Payment.Status.RECORDED) {
            audit.payment(payment, false, sandboxTag(payment) + "Refund reported by the processor (via webhook) "
                    + "for a payment that is " + payment.getStatus() + ", not RECORDED; nothing written",
                    current.auditActor());
            return Completion.of("unchanged", "The payment is not recorded; no refund row was written.");
        }
        final PaymentProcessorConfig config = configOf(payment);
        final OptionalLong read;
        try {
            read = processors.apply(config, payment.isSandbox()).refundedCents(payment.getOrderRef());
        } catch (final RuntimeException ex) {
            log.warn("Refund read failed for payment {}: {}", payment.getPaymentId(), ex.getMessage());
            return Completion.of("pending", "The processor could not be read; try again.");
        }
        if (read.isEmpty()) {
            return Completion.of("pending", "The processor could not be read; try again.");
        }
        final long refunded = read.getAsLong();
        if (refunded <= 0L && payment.getRefundedCents() <= 0L) {
            return Completion.of("unchanged", "The processor reports nothing refunded.");
        }
        final String processorName = displayNameOf(config);
        final String txId;
        try {
            // Always, even when the total is unchanged: this is what repairs a stale row a lost race left.
            txId = recorder.recordRefund(payment, refunded, orgNameOf(payment), processorName);
        } catch (final RuntimeException ex) {
            log.error("Refund ledger write failed for payment {}", payment.getPaymentId(), ex);
            audit.payment(payment, false, sandboxTag(payment) + "Refund of " + MoneyMath.formatCents(refunded)
                    + " reported by " + processorName + " (via webhook) but the ledger write FAILED",
                    current.auditActor());
            return Completion.of("pending", "The refund could not be recorded; try again.");
        }
        final boolean alreadyNoted = refunded == payment.getRefundedCents()
                && (txId == null || payment.getTxIds().contains(txId));
        if (!alreadyNoted) {
            payment.setRefundedCents(refunded);
            if (txId != null && !payment.getTxIds().contains(txId)) {
                payment.getTxIds().add(txId);
            }
            try {
                DAO.getInstance().replacePaymentIfUnchanged(payment, stored.content());
            } catch (final ConditionalCheckFailedException ex) {
                log.info("Refund for payment {} raced another writer; re-reading", payment.getPaymentId());
                return null;
            } catch (final RuntimeException | IOException ex) {
                log.error("Unable to persist refund total on payment {}", payment.getPaymentId(), ex);
                return Completion.of("pending", "The refund could not be recorded; try again.");
            }
            audit.payment(payment, true, sandboxTag(payment) + "Refund of " + MoneyMath.formatCents(refunded)
                    + (refunded > 0L && refunded < payment.getCapturedGrossCents() ? " (partial)" : "")
                    + (refunded == 0L ? " (reversed)" : "") + " reported by " + processorName + " (via webhook)"
                    + (txId == null ? "; sandbox, nothing written" : "; ledger row " + txId), current.auditActor());
        }
        return Completion.of(payment.isSandbox() ? "sandbox" : "refunded",
                "Refund of " + MoneyMath.formatCents(refunded) + " recorded.");
    }

    private static Caller systemCaller() {
        return Caller.forActor(AuditActor.system());
    }

    // ------------------------------------------------------------------ method-aware re-pricing (inline)

    /**
     * The payer picked a payment method inside the processor's form, and the organization makes payers cover
     * fees: a US bank debit costs the processor far less than a card (Stripe: 0.8% capped at $5.00 against
     * 2.9% + $0.30), so the grossed-up total is recomputed for that method, the processor's order is
     * re-priced to match, and the new total is answered for the page to show. A card (or any other method)
     * re-prices back to the card estimate. Whoever may act on the payment ({@link #mayComplete}) may do this,
     * only while the payment is CREATED, and only for a processor with bank pricing and a re-priceable order;
     * everything else answers the CURRENT total unchanged, so the page never shows a figure the processor will
     * not charge. The apps' native sheet has no method callback, so they keep the card estimate.
     *
     * <p>The page's word about the method is not proof of what pays, so the bank price is LOCKED to bank
     * debits at the processor ({@code PaymentProcessor.reprice}) and the payment remembers what its total is
     * priced for ({@code Payment.pricedForMethod}); a card that pays it anyway is noticed at capture
     * ({@link #noteMethodMismatch}).
     */
    public Reprice selectMethod(final String paymentId, final String methodType) {
        final Caller current = caller();
        final Payment payment = DAO.getInstance().getPayment(paymentId).orElse(null);
        if (payment == null || !mayComplete(current, payment)) {
            return null;
        }
        final Reprice unchanged = Reprice.of(payment, false);
        final PaymentProcessorConfig config = configOf(payment);
        if (payment.getStatus() != Payment.Status.CREATED || !payment.isPayerPaysFee() || config == null
                || config.getType() == null || !config.getType().hasBankPricing()) {
            return unchanged;
        }
        final boolean bank = BANK_METHOD.equals(methodType);
        final Fees fees = feesFor(config, true, payment.getCreditTotalCents(), payment.getDonationCents(), bank);
        if (fees.totalCents() == payment.getTotalChargedCents()) {
            return unchanged;
        }
        final PaymentProcessor.Repriced repriced;
        try {
            repriced = processors.apply(config, payment.isSandbox()).reprice(payment.getOrderRef(),
                    fees.totalCents(), bank ? BANK_METHOD : null);
        } catch (final RuntimeException ex) {
            log.warn("Re-pricing payment {} at the processor failed: {}", payment.getPaymentId(), ex.getMessage());
            return unchanged;
        }
        if (repriced == PaymentProcessor.Repriced.UNSUPPORTED) {
            return unchanged;
        }
        final boolean locked = repriced == PaymentProcessor.Repriced.REPRICED_LOCKED;
        payment.setCreditFeeCents(fees.creditFeeCents());
        payment.setDonationFeeCents(fees.donationFeeCents());
        payment.setTotalChargedCents(fees.totalCents());
        payment.setPricedForMethod(bank ? BANK_METHOD : null);
        try {
            DAO.getInstance().transitionPayment(payment, Payment.Status.CREATED);
        } catch (final RuntimeException | IOException ex) {
            // The processor now expects the new total; the capture's gross-vs-expected check parks the
            // payment in CAPTURED for reconciliation if this row never catches up.
            log.error("Unable to persist re-priced payment {}", payment.getPaymentId(), ex);
            fail("Not re-priced", "The new total could not be saved.");
            return null;
        }
        audit.payment(payment, true, sandboxTag(payment) + "Payment re-priced for " + (bank ? "a bank debit"
                : "a card") + ": fee " + MoneyMath.formatCents(fees.creditFeeCents()) + ", total "
                + MoneyMath.formatCents(fees.totalCents()) + (locked ? " (the processor will refuse any other "
                        + "method at this total)" : ""), current.auditActor());
        return Reprice.of(payment, true, locked);
    }

    // ------------------------------------------------------------------ local-mode fake processor hooks

    /** LOCAL ONLY: the fake checkout's "pay as bank" -- the fake order becomes a settling PENDING. */
    public boolean fakeMarkAsync(final String orderRef) {
        return LocalMode.isLocal() && FakeProcessor.markAsync(orderRef);
    }

    /** LOCAL ONLY: a dashboard refund's stand-in -- the fake order's refunded total, then the webhook path. */
    public Completion fakeRefund(final String orderRef, final long cents) {
        if (!LocalMode.isLocal()) {
            return Completion.of("notfound", "Unknown payment.");
        }
        final String paymentId = FakeProcessor.refund(orderRef, cents);
        return (paymentId == null) ? Completion.of("notfound", "Unknown payment.") : refundFromWebhook(paymentId);
    }

    /** LOCAL ONLY: the fake checkout's "settle" -- the stand-in for Stripe's webhook finishing the payment. */
    public Completion fakeSettle(final String orderRef) {
        if (!LocalMode.isLocal()) {
            return Completion.of("notfound", "Unknown payment.");
        }
        final String paymentId = FakeProcessor.settle(orderRef);
        return (paymentId == null) ? Completion.of("notfound", "Unknown payment.") : completeFromWebhook(paymentId);
    }

    // ------------------------------------------------------------------ reconciliation (site admin)

    /**
     * Non-terminal, non-sandbox payments -- the money that may still be owed a ledger row -- of the
     * organizations the SITE this request is for lists ({@code ListingScope.reaches} on the payment's org;
     * an org's own payments reconcile on its own host, a hosted org's never on the shared one).
     */
    public List<Payment> getOpenPayments() {
        if (!caller().isSiteAdmin()) {
            return List.of();
        }
        return DAO.getInstance().getAllPayments().stream()
                .filter(payment -> payment.getStatus() == Payment.Status.CREATED
                        || payment.getStatus() == Payment.Status.CAPTURED)
                .filter(payment -> !payment.isSandbox())
                .filter(payment -> ListingScope.reachable(payment.getOrgId()))
                .sorted(Comparator.comparing(Payment::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /** The reconciliation "finish this payment" button: capture if needed, then record. Site admin only. */
    public Completion adminComplete(final String paymentId) {
        final Caller current = caller();
        if (!current.isSiteAdmin()) {
            return Completion.of("notfound", "Unknown payment.");
        }
        final Payment payment = DAO.getInstance().getPayment(paymentId).orElse(null);
        if (payment == null) {
            return Completion.of("notfound", "Unknown payment.");
        }
        return complete(payment, null, current, Door.ADMIN);
    }

    /**
     * The reconciliation "abandon" button for a CREATED payment the payer walked away from: an inline intent
     * never expires on its own, so this cancels it at the processor and marks the row CANCELLED. Site admin
     * only; refuses anything but CREATED (CAPTURED money is finished, never abandoned), and a CREATED payment
     * the processor is settling or already holds ({@link #giveUp}).
     */
    public boolean abandon(final String paymentId) {
        final Caller current = caller();
        if (!current.isSiteAdmin()) {
            return fail("Not allowed", "Only a site administrator can abandon a payment.");
        }
        final Payment payment = DAO.getInstance().getPayment(paymentId).orElse(null);
        if (payment == null || payment.getStatus() != Payment.Status.CREATED) {
            return fail("Not abandoned", "Only a CREATED payment can be abandoned.");
        }
        return giveUp(payment, current, "Payment abandoned by " + current.auditActor().email());
    }

    // ------------------------------------------------------------------ helpers

    private Start startFailed(final Payment payment, final Caller current, final String reason) {
        payment.setStatus(Payment.Status.FAILED);
        try {
            DAO.getInstance().transitionPayment(payment, Payment.Status.CREATED);
        } catch (final RuntimeException | IOException ex) {
            log.warn("Unable to mark payment {} failed", payment.getPaymentId(), ex);
        }
        audit.payment(payment, false, sandboxTag(payment) + "Order creation failed: " + reason,
                current.auditActor());
        fail("Payment not started", reason + " Nothing was charged.");
        return null;
    }

    private void markFailed(final Payment payment, final Caller current, final String reason) {
        payment.setStatus(Payment.Status.FAILED);
        try {
            DAO.getInstance().transitionPayment(payment, Payment.Status.CREATED);
        } catch (final RuntimeException | IOException ex) {
            log.warn("Unable to mark payment {} failed", payment.getPaymentId(), ex);
        }
        audit.payment(payment, false, sandboxTag(payment) + reason, current.auditActor());
    }

    private Completion recordedOutcome(final Payment payment, final String message) {
        final Completion completion = Completion.of(payment.isSandbox() ? "sandbox" : "recorded", message);
        if (payment.isSandbox()) {
            final PaymentProcessorConfig config = configOf(payment);
            completion.dryRunLines = recorder
                    .computeLines(payment, orgNameOf(payment), displayNameOf(config)).stream()
                    .map(line -> MoneyMath.formatCents(line.amountCents()) + " ("
                            + line.txType() + ") " + line.description())
                    .toList();
        }
        return completion;
    }

    /** The processor config a payment for this trip would use, or null when it takes no payments. */
    PaymentProcessorConfig processorConfigFor(final Trip trip) {
        return resolveConfig(trip, orgs.effectivePaymentConfig(trip));
    }

    private PaymentProcessorConfig resolveConfig(final Trip trip, final TripPaymentConfig effective) {
        if (trip == null || trip.getOrgId() == null || trip.getOrgId().isBlank()
                || !effective.isPayable()) {
            return null;
        }
        final PaymentProcessorConfig config = DAO.getInstance().getPaymentProcessorConfig(
                Organization.Id.from(trip.getOrgId()),
                PaymentProcessorConfig.Id.from(effective.getProcessorConfigId()), Cached.NO).orElse(null);
        return (config == null || !config.isEnabled()) ? null : config;
    }

    private static PaymentProcessorConfig configOf(final Payment payment) {
        if (payment.getOrgId() == null || payment.getProcessorConfigId() == null) {
            return null;
        }
        return DAO.getInstance().getPaymentProcessorConfig(Organization.Id.from(payment.getOrgId()),
                PaymentProcessorConfig.Id.from(payment.getProcessorConfigId()), Cached.NO).orElse(null);
    }

    /** The payment's processor as people read it: an offline payment has no config, only its type. */
    private static String processorNameOf(final Payment payment, final PaymentProcessorConfig config) {
        return payment.getProcessorType() == ProcessorType.OFFLINE ? ProcessorType.OFFLINE.getLabel()
                : displayNameOf(config);
    }

    /** The payment's processor as people read it, from its stored config: "Stripe", or "Offline payment". */
    public static String processorNameOf(final Payment payment) {
        return processorNameOf(payment, configOf(payment));
    }

    /**
     * How the money arrived, for a Method column: the offline receipt's method and reference ("Check 1042",
     * "Comp", "Free") or the processor's name. The reconciliation page's column; an instance method so EL can call it.
     */
    public String methodOf(final Payment payment) {
        final Payment.OfflineReceipt receipt = payment.getOfflineReceipt();
        return receipt == null ? processorNameOf(payment) : receipt.summary();
    }

    /** The export's Payment-Method cell: the offline method alone ("Check") or the processor's name. */
    public static String paymentMethodOf(final Payment payment) {
        final Payment.OfflineReceipt receipt = payment.getOfflineReceipt();
        return receipt == null ? processorNameOf(payment) : receipt.methodLabel();
    }

    /** The export's Payment-Reference cell: the check number, or the processor's capture id; "" when neither. */
    public static String paymentReferenceOf(final Payment payment) {
        final Payment.OfflineReceipt receipt = payment.getOfflineReceipt();
        final String reference = receipt == null ? payment.getCaptureId() : receipt.reference();
        return reference == null ? "" : reference;
    }

    /** The day the money arrived: the receipt's date for an offline payment, else the capture's; null when unknown. */
    public static java.time.LocalDate paymentReceivedOn(final Payment payment) {
        final Payment.OfflineReceipt receipt = payment.getOfflineReceipt();
        if (receipt != null) {
            return receipt.receivedOn();
        }
        return payment.getCapturedAt() == null ? null : payment.getCapturedAt().toLocalDate();
    }

    /**
     * One line saying how a registration was paid, for the breakdown dialog: "Check 1042, received 10/30/2026" for
     * an offline payment, "Stripe (id #pi_123), 10/30/2026" for a processor's.
     */
    public static String paidWith(final Payment payment) {
        final Payment.OfflineReceipt receipt = payment.getOfflineReceipt();
        if (receipt != null) {
            return receipt.describe();
        }
        final java.time.LocalDate received = paymentReceivedOn(payment);
        return processorNameOf(payment)
                + (payment.getCaptureId() == null ? "" : " (id #" + payment.getCaptureId() + ")")
                + (received == null ? "" : ", " + received.format(RECEIVED_DATE));
    }

    private static String displayNameOf(final PaymentProcessorConfig config) {
        if (config == null || config.getType() == null) {
            return "the payment processor";
        }
        return config.getType().getLabel();
    }

    private String orgNameOf(final Payment payment) {
        if (payment.getOrgId() == null) {
            return "the organization";
        }
        return DAO.getInstance().getOrganization(Organization.Id.from(payment.getOrgId()), Cached.YES)
                .map(Organization::getName).orElse("the organization");
    }

    private String notifyEmail() {
        return new ConfigCommands().getString(org.paulsens.trip.config.KnownSettings.PAYMENT_NOTIFY_EMAIL);
    }

    /** Allocations from the page's id->text map; null (with a growl) when anything is invalid. */
    private List<Payment.Allocation> parseAllocations(final Person payer,
            final Map<String, Object> rawAmounts) {
        final List<Payment.Allocation> allocations = new ArrayList<>();
        if (rawAmounts == null) {
            return allocations;
        }
        final PersonCommands people = PersonCommands.getPersonCommands();
        for (final Map.Entry<String, Object> entry : rawAmounts.entrySet()) {
            final long cents = parseStrict(entry.getValue());
            if (cents < 0) {
                fail("Bad amount", "'" + entry.getValue() + "' is not a valid dollar amount.");
                return null;
            }
            if (cents == 0) {
                continue;
            }
            final Person.Id target = Person.Id.from(entry.getKey());
            if (!people.canAccessUserId(payer, target)) {
                fail("Not allowed", "You can only pay for yourself and family members you manage.");
                return null;
            }
            allocations.add(new Payment.Allocation(target, cents));
        }
        allocations.sort(Comparator.comparing(allocation -> allocation.getPersonId().getValue()));
        return allocations;
    }

    private long sumAmounts(final Map<String, Object> rawAmounts) {
        if (rawAmounts == null) {
            return 0L;
        }
        return rawAmounts.values().stream().mapToLong(this::parseLenient).sum();
    }

    /** Quote-path parse: garbage counts as zero so a half-typed number never breaks the live totals. */
    private long parseLenient(final Object raw) {
        final long parsed = parseStrict(raw);
        return Math.max(parsed, 0L);
    }

    /** Start-path parse: -1 flags garbage so the submit refuses instead of guessing. */
    private long parseStrict(final Object raw) {
        if (raw == null) {
            return 0L;
        }
        final String text = String.valueOf(raw).trim().replace("$", "").replace(",", "");
        if (text.isEmpty()) {
            return 0L;
        }
        try {
            return MoneyMath.toCents(new BigDecimal(text));
        } catch (final RuntimeException ex) {
            return -1L;
        }
    }

    private String sandboxTag(final Payment payment) {
        return payment.isSandbox() ? "[SANDBOX] " : "";
    }

    private Caller caller() {
        return callerSource.get();
    }

    private Person currentPerson() {
        final Caller current = caller();
        return current.isAuthenticated()
                ? DAO.getInstance().getPerson(current.personId(), Cached.NO).orElse(null) : null;
    }

    private boolean fail(final String summary, final String detail) {
        return PageFeedback.refuse(summary, detail);
    }

    private Start failNull(final String summary, final String detail) {
        fail(summary, detail);
        return null;
    }

    // ------------------------------------------------------------------ page-facing value types

    /** Live totals for the page (plain getters, not a record: JSFT EL reads bean properties). */
    @Getter
    public static final class Quote implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

        private long creditCents;
        private long creditFeeCents;
        private long donationCents;
        private long donationFeeCents;
        private long totalCents;
        private boolean payerPays;
        private boolean payable;
        private String processorLabel = "";

        public String getCreditFeeFormatted() {
            return MoneyMath.formatCents(creditFeeCents);
        }

        public String getDonationFeeFormatted() {
            return MoneyMath.formatCents(donationFeeCents);
        }

        public String getTotalFormatted() {
            return MoneyMath.formatCents(totalCents);
        }
    }

    /**
     * How a started payment is completed: {@code approvalUrl} to redirect to (PayPal, the fake checkout), or
     * {@code clientSecret} + {@code publishableKey} for an inline form (Stripe's Payment Element on the page,
     * PaymentSheet in the apps; {@code scriptUrl} is the browser script that mounts it, null = the local
     * fake). Scalars only, so the page may park them in its view.
     */
    @Getter
    public static final class Start implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

        private String paymentId;
        private String approvalUrl;
        private String publishableKey;
        private String clientSecret;
        private String scriptUrl;
        private String merchantName;
        private String payerEmail;
        private long totalCents;
        /** Whether the payer covers fees -- the page offers method-aware re-pricing only then. */
        private boolean payerPays;

        static Start of(final Payment payment, final PaymentProcessor.CreatedOrder order,
                final String merchantName, final String payerEmail) {
            final Start start = new Start();
            start.paymentId = payment.getPaymentId();
            start.approvalUrl = order.approvalUrl();
            if (order.isInline()) {
                start.publishableKey = order.inline().publishableKey();
                start.clientSecret = order.inline().clientSecret();
                start.scriptUrl = order.inline().scriptUrl();
            }
            start.merchantName = merchantName;
            start.payerEmail = payerEmail;
            start.totalCents = payment.getTotalChargedCents();
            start.payerPays = payment.isPayerPaysFee();
            return start;
        }

        public boolean isInline() {
            return clientSecret != null;
        }

        public String getTotalFormatted() {
            return MoneyMath.formatCents(totalCents);
        }
    }

    /**
     * The answer to a method pick inside the inline form: the payment's (possibly re-priced) fee and total,
     * and whether anything changed. Returned over REST only (the page's script reads it), so a record.
     */
    public record Reprice(String paymentId, long creditFeeCents, long totalCents, String totalFormatted,
            boolean changed, boolean methodLocked) {
        static Reprice of(final Payment payment, final boolean changed) {
            return of(payment, changed, false);
        }

        /**
         * {@code methodLocked}: the processor now accepts ONLY the method this total is priced for. The page
         * must then leave its mounted form alone (a refresh would drop the other methods' tabs and with them
         * the payer's way back); picking another method re-prices and lifts the lock.
         */
        static Reprice of(final Payment payment, final boolean changed, final boolean methodLocked) {
            return new Reprice(payment.getPaymentId(), payment.getCreditFeeCents(), payment.getTotalChargedCents(),
                    MoneyMath.formatCents(payment.getTotalChargedCents()), changed, methodLocked);
        }
    }

    /**
     * A completion outcome for the page/REST: a status keyword, a message, sandbox dry-run lines, and for a
     * pending outcome the processor's reference to the in-flight payment (the fake checkout's settle link
     * needs it; a real one shows it to the payer).
     */
    @Getter
    public static final class Completion implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

        private String status;
        private String message;
        private List<String> dryRunLines = List.of();
        private String reference;
        /** Set by {@link #recordOffline}: the payment entered, and how it was received (echoed by REST). */
        private String paymentId;
        private Payment.OfflineReceipt offline;

        static Completion of(final String status, final String message) {
            final Completion completion = new Completion();
            completion.status = status;
            completion.message = message;
            return completion;
        }

        Completion withReference(final Payment payment) {
            this.reference = payment.getOrderRef();
            return this;
        }

        Completion withOffline(final Payment payment) {
            this.paymentId = payment.getPaymentId();
            this.offline = payment.getOfflineReceipt();
            return this;
        }
    }
}
