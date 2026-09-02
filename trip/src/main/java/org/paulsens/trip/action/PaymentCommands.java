package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.inject.Named;
import java.io.IOException;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.site.ListingScope;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Payment;
import org.paulsens.trip.model.PaymentProcessorConfig;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.FeesPaidBy;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripPaymentConfig;
import org.paulsens.trip.pay.MoneyMath;
import org.paulsens.trip.pay.PaymentMailer;
import org.paulsens.trip.pay.PaymentProcessor;
import org.paulsens.trip.pay.PaymentProcessors;
import org.paulsens.trip.pay.PaymentRecorder;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * The payment flow, ALL of it in Java (a user-locked rule: the mir2026 xhtml-driven callback handling made
 * mobile impossible): quote &rarr; start (order + redirect) &rarr; complete (capture + record + mail) &rarr;
 * cancel, plus the site-admin reconciliation actions. The page and the REST resource are both thin callers
 * of these same methods.
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
    private final Supplier<Caller> callerSource;
    private final OrgCommands orgs;
    private final AuditCommands audit;
    private final PaymentRecorder recorder;
    private final BiFunction<PaymentProcessorConfig, Boolean, PaymentProcessor> processors;
    private final Supplier<PaymentMailer> mailerSource;

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
        this.callerSource = callerSource;
        this.orgs = orgs;
        this.audit = audit;
        this.recorder = recorder;
        this.processors = processors;
        this.mailerSource = mailerSource;
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
     * Validates, persists the CREATED payment, audits the start, creates the processor order, and answers
     * the approval URL to redirect to -- or null with a growl. {@code pageUrl} is the payment page's own
     * absolute URL; the processor return/cancel URLs derive from it ({@code ?trip=..&payment=..}, which
     * PayPal preserves and re-appends its token to).
     */
    public String startPayment(final Trip trip, final Map<String, Object> rawAmounts,
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
        final boolean payerPays = effective.getFeesPaidBy() == FeesPaidBy.PAYER;
        final long creditFee = MoneyMath.creditFeeCents(credits, config.getEffectiveFeeBps(),
                config.getEffectiveFeeFixedCents());
        final Payment payment = Payment.builder()
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
        try {
            DAO.getInstance().createPayment(payment);
        } catch (final RuntimeException | IOException ex) {
            log.error("Unable to persist payment for {}", payer.getId(), ex);
            return failNull("Unable to start", "The payment could not be started; nothing was charged.");
        }
        audit.payment(payment, true, sandboxTag(payment) + "Payment started by "
                + payer.getEmail() + ": " + MoneyMath.formatCents(payment.getTotalChargedCents())
                + " to trip " + trip.getId() + " (" + allocations.size() + " traveler(s), donation "
                + MoneyMath.formatCents(donation) + ", via " + config.getDisplayLabel() + ")",
                current.auditActor());
        try {
            final PaymentProcessor processor = processors.apply(config, sandbox);
            final String base = pageUrl + "?trip=" + trip.getId() + "&payment=" + payment.getPaymentId();
            final PaymentProcessor.CreatedOrder order = processor.createOrder(payment,
                    trip.getTitle() + " - payment", base, base + "&cancelled=true");
            payment.setOrderRef(order.orderRef());
            DAO.getInstance().transitionPayment(payment, Payment.Status.CREATED);
            return order.approvalUrl();
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
        if (payment == null) {
            return Completion.of("notfound", "Unknown payment.");
        }
        if (!current.isSiteAdmin()
                && (currentPerson() == null || !payment.getPayerId().equals(currentPerson().getId()))) {
            return Completion.of("notfound", "Unknown payment.");
        }
        return complete(payment, token, current);
    }

    private Completion complete(final Payment payment, final String token, final Caller current) {
        switch (payment.getStatus()) {
            case RECORDED -> {
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
                return Completion.of("pending", "The payment has not completed at the processor yet. "
                        + "If you approved it, try again in a moment.");
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
            // A concurrent return-page retry won; adopt ITS row and continue from wherever it got to.
            final Payment winner = DAO.getInstance().getPayment(payment.getPaymentId()).orElse(null);
            return (winner == null) ? Completion.of("failed", "The payment state could not be read.")
                    : complete(winner, token, current);
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
        final String processorName = displayNameOf(config);
        final PaymentRecorder.Result result;
        try {
            result = recorder.record(payment, orgName, processorName);
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
            log.info("Payment {} was recorded by a concurrent writer", payment.getPaymentId());
        } catch (final RuntimeException | IOException ex) {
            log.error("Unable to persist RECORDED for payment {}", payment.getPaymentId(), ex);
        }
        if (!payment.isSandbox()) {
            final TripPaymentConfig effective = orgs.effectivePaymentConfig(trip);
            final boolean mailed = mailerSource.get().sendConfirmation(payment, trip, effective, orgName,
                    processorName, notifyEmail(), current.auditActor());
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

    /** The cancel-URL callback: only an un-captured payment can cancel. */
    public boolean cancelPayment(final String paymentId) {
        final Caller current = caller();
        final Payment payment = DAO.getInstance().getPayment(paymentId).orElse(null);
        final Person me = currentPerson();
        if (payment == null || payment.getStatus() != Payment.Status.CREATED
                || (!current.isSiteAdmin() && (me == null || !payment.getPayerId().equals(me.getId())))) {
            return false;
        }
        payment.setStatus(Payment.Status.CANCELLED);
        try {
            DAO.getInstance().transitionPayment(payment, Payment.Status.CREATED);
        } catch (final RuntimeException | IOException ex) {
            log.warn("Unable to mark payment {} cancelled", payment.getPaymentId(), ex);
            return false;
        }
        audit.payment(payment, true, sandboxTag(payment) + "Payment cancelled by the payer",
                current.auditActor());
        return true;
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

    // ------------------------------------------------------------------ helpers

    private String startFailed(final Payment payment, final Caller current, final String reason) {
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

    private String displayNameOf(final PaymentProcessorConfig config) {
        if (config == null || config.getType() == null) {
            return "the payment processor";
        }
        return switch (config.getType()) {
            case PAYPAL -> "PayPal";
            case STRIPE -> "Stripe";
            case ZEFFY -> "Zeffy";
            case FAKE -> "Test Processor";
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
        TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, summary + ": " + detail, detail);
        return false;
    }

    private String failNull(final String summary, final String detail) {
        fail(summary, detail);
        return null;
    }

    // ------------------------------------------------------------------ page-facing value types

    /** Live totals for the page (plain getters, not a record: JSFT EL reads bean properties). */
    @Getter
    public static final class Quote implements Serializable {
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

    /** A completion outcome for the page/REST: a status keyword, a message, and sandbox dry-run lines. */
    @Getter
    public static final class Completion implements Serializable {
        private String status;
        private String message;
        private List<String> dryRunLines = List.of();

        static Completion of(final String status, final String message) {
            final Completion completion = new Completion();
            completion.status = status;
            completion.message = message;
            return completion;
        }
    }
}
