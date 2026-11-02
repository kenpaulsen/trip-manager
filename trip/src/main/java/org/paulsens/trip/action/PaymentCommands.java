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
    /** What a payment recorded outside any processor (a check, cash, a comp, a $0 registration) is called. */
    public static final String OFFLINE_NAME = "Offline payment";
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
     * Whether the signed-in user may flip the page into sandbox mode for THIS trip: site admin, or a holder
     * of {@code paymentsAdmin} scoped to the trip's org (sandbox mode exercises that org's sandbox
     * credentials, so the grant follows the org -- the global variant is retired, org migration 2026-08).
     * An org-less legacy trip has no org to hold the grant, so only site admins get sandbox there.
     */
    public boolean isSandboxAllowed(final Trip trip) {
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
        final int rateBps = (config == null) ? 0 : config.getEffectiveFeeBps();
        final int fixedCents = (config == null) ? 0 : config.getEffectiveFeeFixedCents();
        quote.creditFeeCents = MoneyMath.creditFeeCents(quote.creditCents, rateBps, fixedCents);
        quote.donationFeeCents = MoneyMath.donationFeeCents(quote.donationCents, rateBps);
        quote.totalCents = MoneyMath.totalChargedCents(quote.creditCents, quote.creditFeeCents,
                quote.donationCents, quote.payerPays);
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
        final Caller current = caller();
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

    /**
     * A CREATED payment for these credits and donation, its fees per the effective config: the one place a
     * payment's money fields are computed, for the payment page and priced checkout alike.
     */
    Payment newPayment(final Trip trip, final Person payer, final PaymentProcessorConfig config,
            final TripPaymentConfig effective, final List<Payment.Allocation> allocations, final long donation,
            final boolean sandbox) {
        final long credits = allocations.stream().mapToLong(Payment.Allocation::getAmountCents).sum();
        final boolean payerPays = effective.getFeesPaidBy() == FeesPaidBy.PAYER;
        final long creditFee = MoneyMath.creditFeeCents(credits, config.getEffectiveFeeBps(),
                config.getEffectiveFeeFixedCents());
        return Payment.builder()
                .tripId(trip.getId())
                .orgId(trip.getOrgId())
                .payerId(payer.getId())
                .processorConfigId(config.getId().getValue())
                .processorType(config.getType())
                .allocations(allocations)
                .donationCents(donation)
                .feesPaidBy(effective.getFeesPaidBy())
                .creditFeeCents(payerPays ? creditFee : creditFee)
                .donationFeeCents(MoneyMath.donationFeeCents(donation, config.getEffectiveFeeBps()))
                .totalChargedCents(MoneyMath.totalChargedCents(credits, creditFee, donation, payerPays))
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
        return complete(payment, token, current);
    }

    /**
     * Who may finish a payment from a return page: its payer, a site admin, and for a registration checkout the
     * trip's registration admins too (an admin paying on a family's behalf comes back to their own page).
     */
    private boolean mayComplete(final Caller current, final Payment payment) {
        if (current.isSiteAdmin()) {
            return true;
        }
        final Person me = currentPerson();
        if (me != null && payment.getPayerId().equals(me.getId())) {
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
        return complete(payment, null, current);
    }

    private Completion complete(final Payment payment, final String token, final Caller current) {
        switch (payment.getStatus()) {
            case RECORDED -> {
                if (payment.hasCheckout() && payment.getCheckout().getFinalizedAt() == null && !payment.isSandbox()) {
                    // Recorded by a build that predates checkout (a rolling deploy): the money is in, the
                    // registrations are owed. Finalizing is idempotent, so this only ever completes the job.
                    return finalizeRecorded(payment, current);
                }
                return recordedOutcome(payment, "Payment already recorded.");
            }
            case CANCELLED -> {
                return Completion.of("cancelled", "This payment was cancelled; nothing was charged.");
            }
            case FAILED -> {
                return Completion.of("failed", "This payment failed; nothing was charged.");
            }
            case CREATED -> {
                final Completion captured = captureNow(payment, token, current);
                if (captured != null) {
                    return captured;
                }
            }
            case CAPTURED -> {
                // Crash recovery: money moved, the ledger write is owed. Fall through to record.
            }
            default -> {
            }
        }
        return recordNow(payment, current);
    }

    /** Runs the capture; null means "captured, continue to recording", anything else is the outcome. */
    private Completion captureNow(final Payment payment, final String token, final Caller current) {
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
        payment.setCaptureId(result.captureId());
        payment.setCapturedGrossCents(result.grossCents());
        payment.setActualFeeCents(result.actualFeeCents().isPresent()
                ? result.actualFeeCents().getAsLong() : null);
        payment.setCapturedAt(LocalDateTime.now());
        payment.setStatus(Payment.Status.CAPTURED);
        try {
            DAO.getInstance().transitionPayment(payment, Payment.Status.CREATED);
        } catch (final ConditionalCheckFailedException ex) {
            return adoptWinner(payment, token, current);
        } catch (final RuntimeException | IOException ex) {
            log.error("Unable to persist capture for payment {}", payment.getPaymentId(), ex);
        }
        if (result.grossCents() != payment.getTotalChargedCents()) {
            audit.payment(payment, false, sandboxTag(payment)
                    + "Captured amount " + MoneyMath.formatCents(result.grossCents())
                    + " does not match the expected " + MoneyMath.formatCents(payment.getTotalChargedCents())
                    + "; NOT recorded (needs reconciliation)", current.auditActor());
            return Completion.of("mismatch", "The processor reported a different amount than expected. "
                    + "Your payment was received and an administrator will reconcile it.");
        }
        return null;
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
            return adoptWinner(payment, null, current);
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
                + payment.getCaptureId() + "), " + result.txIds().size() + " ledger row(s)",
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
    private Completion adoptWinner(final Payment payment, final String token, final Caller current) {
        final Payment winner = DAO.getInstance().getPayment(payment.getPaymentId()).orElse(null);
        if (winner == null) {
            return Completion.of("failed", "The payment state could not be read.");
        }
        return complete(winner, token, current);
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
        return cancelCreated(payment, current, "Payment cancelled by the payer");
    }

    /** CREATED -> CANCELLED plus the processor's own cancel (an inline intent must not stay confirmable). */
    private boolean cancelCreated(final Payment payment, final Caller current, final String note) {
        payment.setStatus(Payment.Status.CANCELLED);
        try {
            DAO.getInstance().transitionPayment(payment, Payment.Status.CREATED);
        } catch (final RuntimeException | IOException ex) {
            log.warn("Unable to mark payment {} cancelled", payment.getPaymentId(), ex);
            return false;
        }
        releaseAtProcessor(payment);
        audit.payment(payment, true, sandboxTag(payment) + note, current.auditActor());
        return true;
    }

    private void releaseAtProcessor(final Payment payment) {
        if (payment.getOrderRef() == null) {
            return;
        }
        try {
            processors.apply(configOf(payment), payment.isSandbox()).cancel(payment.getOrderRef());
        } catch (final RuntimeException ex) {
            // Our row is already CANCELLED; a processor that cannot be reached just keeps a dangling intent.
            log.warn("Processor cancel failed for payment {}: {}", payment.getPaymentId(), ex.getMessage());
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
        return complete(payment, null, systemCaller());
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
        return cancelCreated(payment, systemCaller(), "Payment cancelled at the processor (via webhook)");
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
     * re-prices back to the card estimate. Only the payer (or a site admin) may do this, only while the
     * payment is CREATED, and only for a processor with bank pricing and a re-priceable order; everything
     * else answers the CURRENT total unchanged, so the page never shows a figure the processor will not
     * charge. The apps' native sheet has no method callback, so they keep the card estimate.
     */
    public Reprice selectMethod(final String paymentId, final String methodType) {
        final Caller current = caller();
        final Payment payment = DAO.getInstance().getPayment(paymentId).orElse(null);
        final Person me = currentPerson();
        if (payment == null
                || (!current.isSiteAdmin() && (me == null || !payment.getPayerId().equals(me.getId())))) {
            return null;
        }
        final Reprice unchanged = Reprice.of(payment, false);
        final PaymentProcessorConfig config = configOf(payment);
        if (payment.getStatus() != Payment.Status.CREATED || !payment.isPayerPaysFee() || config == null
                || config.getType() == null || !config.getType().hasBankPricing()) {
            return unchanged;
        }
        final boolean bank = "us_bank_account".equals(methodType);
        final long credits = payment.getCreditTotalCents();
        final long creditFee = bank
                ? MoneyMath.bankCreditFeeCents(credits, config.getType().getBankFeeBps(),
                        config.getType().getBankFeeCapCents())
                : MoneyMath.creditFeeCents(credits, config.getEffectiveFeeBps(), config.getEffectiveFeeFixedCents());
        final long donationFee = bank
                ? MoneyMath.bankDonationFeeCents(payment.getDonationCents(), config.getType().getBankFeeBps(),
                        config.getType().getBankFeeCapCents())
                : MoneyMath.donationFeeCents(payment.getDonationCents(), config.getEffectiveFeeBps());
        final long total = MoneyMath.totalChargedCents(credits, creditFee, payment.getDonationCents(), true);
        if (total == payment.getTotalChargedCents()) {
            return unchanged;
        }
        try {
            if (!processors.apply(config, payment.isSandbox()).updateAmount(payment.getOrderRef(), total)) {
                return unchanged;
            }
        } catch (final RuntimeException ex) {
            log.warn("Re-pricing payment {} at the processor failed: {}", payment.getPaymentId(), ex.getMessage());
            return unchanged;
        }
        payment.setCreditFeeCents(creditFee);
        payment.setDonationFeeCents(donationFee);
        payment.setTotalChargedCents(total);
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
                : "a card") + ": fee " + MoneyMath.formatCents(creditFee) + ", total "
                + MoneyMath.formatCents(total), current.auditActor());
        return Reprice.of(payment, true);
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
        return complete(payment, null, current);
    }

    /**
     * The reconciliation "abandon" button for a CREATED payment the payer walked away from: an inline intent
     * never expires on its own, so this cancels it at the processor and marks the row CANCELLED. Site admin
     * only; refuses anything but CREATED (CAPTURED money is finished, never abandoned).
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
        return cancelCreated(payment, current, "Payment abandoned by " + current.auditActor().email());
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

    private PaymentProcessorConfig configOf(final Payment payment) {
        if (payment.getOrgId() == null || payment.getProcessorConfigId() == null) {
            return null;
        }
        return DAO.getInstance().getPaymentProcessorConfig(Organization.Id.from(payment.getOrgId()),
                PaymentProcessorConfig.Id.from(payment.getProcessorConfigId()), Cached.NO).orElse(null);
    }

    /** The payment's processor as people read it: an offline payment has no config, only its type. */
    private String processorNameOf(final Payment payment, final PaymentProcessorConfig config) {
        return payment.getProcessorType() == ProcessorType.OFFLINE ? OFFLINE_NAME : displayNameOf(config);
    }

    private String displayNameOf(final PaymentProcessorConfig config) {
        if (config == null || config.getType() == null) {
            return "the payment processor";
        }
        return switch (config.getType()) {
            case PAYPAL -> "PayPal";
            case STRIPE -> "Stripe";
            case ZEFFY -> "Zeffy";
            case FAKE -> "Test Processor";
            case OFFLINE -> OFFLINE_NAME;
        };
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
            boolean changed) {
        static Reprice of(final Payment payment, final boolean changed) {
            return new Reprice(payment.getPaymentId(), payment.getCreditFeeCents(), payment.getTotalChargedCents(),
                    MoneyMath.formatCents(payment.getTotalChargedCents()), changed);
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
    }
}
