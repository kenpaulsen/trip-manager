package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.checkout.CheckoutCapacity;
import org.paulsens.trip.checkout.CheckoutPricing;
import org.paulsens.trip.checkout.CheckoutProblem;
import org.paulsens.trip.checkout.CheckoutValidator;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AgeBand;
import org.paulsens.trip.model.CheckoutConfig;
import org.paulsens.trip.model.Concession;
import org.paulsens.trip.model.DiscountCode;
import org.paulsens.trip.model.FeesPaidBy;
import org.paulsens.trip.model.OptionChoice;
import org.paulsens.trip.model.Payment;
import org.paulsens.trip.model.PaymentProcessorConfig;
import org.paulsens.trip.model.PendingParty;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PriceBreakdown;
import org.paulsens.trip.model.ProcessorType;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.RegistrationDraft;
import org.paulsens.trip.model.RegistrationOption;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripPaymentConfig;
import org.paulsens.trip.pay.Bills;
import org.paulsens.trip.pay.MoneyMath;
import org.paulsens.trip.util.Ages;

/**
 * Priced checkout (docs/checkout.md): a family registers and pays in one step, priced by the trip's typed
 * questions, age bands and discount codes. PAY-FIRST: nothing is registered until the money is recorded. The
 * party's registrations ride on the {@link Payment} as a {@link PendingParty}; {@link PaymentCommands} records the
 * payment along its one completion path (return page, Stripe webhook, admin Finish) and calls
 * {@link #finalizeParty} there, which writes the rows, the roster and the Bills. An abandoned checkout is therefore
 * just a CREATED payment: it never reaches the Registrations tab.
 *
 * <p>The flow logic is all here so the page ({@code trip/joinTrip.xhtml}) and REST are thin callers. Pricing,
 * validation and capacity are the pure {@code org.paulsens.trip.checkout} engine; money fields are computed by
 * {@code PaymentCommands.newPayment}, the same place the payment page's are.
 */
@Slf4j
@Named("checkout")
@ApplicationScoped
public class CheckoutCommands {
    /** The category of a registration's Bill row. */
    public static final String BILL_CATEGORY = "Registration";
    /** The offline methods an admin may record (docs/checkout.md, "Offline payments"). */
    public static final List<String> OFFLINE_METHODS = List.of("CHECK", "CASH", "COMP", "OTHER");

    private final Supplier<Caller> callerSource;
    private final Supplier<PaymentCommands> paymentSource;
    private final Supplier<LocalDateTime> clock;

    public CheckoutCommands() {
        this(Caller::current);
    }

    /** The REST edge's (and PaymentCommands') constructor: the caller comes from the request. */
    public CheckoutCommands(final Supplier<Caller> callerSource) {
        this(callerSource, () -> new PaymentCommands(callerSource), LocalDateTime::now);
    }

    /** Test seam. */
    CheckoutCommands(final Supplier<Caller> callerSource, final Supplier<PaymentCommands> paymentSource,
            final Supplier<LocalDateTime> clock) {
        this.callerSource = callerSource;
        this.paymentSource = paymentSource;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ views

    /** Whether this trip registers through priced checkout instead of the classic form. */
    public boolean isEnabled(final Trip trip) {
        return trip != null && trip.getCheckout().isOn();
    }

    /** Site admins and the trip's registration admins: register others, record offline payments. */
    public boolean canAdminister(final Trip trip) {
        final Caller caller = callerSource.get();
        return trip != null && (caller.isSiteAdmin()
                || caller.has(PrivilegeCommands.REGISTRATION_ADMIN, trip.getId()));
    }

    /** The offline methods, for the dialog's menu. */
    public List<String> getOfflineMethods() {
        return OFFLINE_METHODS;
    }

    /**
     * What one choice costs THIS person, after their age band (codes are not applied here: a code is typed later
     * and shows in the person's total): the registration form's choice labels.
     */
    public String choiceLabel(final Trip trip, final RegistrationOption question, final OptionChoice choice,
            final Person person) {
        if (choice == null) {
            return "";
        }
        if (!choice.isPriced()) {
            return choice.getLabel();
        }
        final long price = personalPrice(trip, question, choice, person == null ? null : person.getBirthdate());
        return choice.getLabel() + " (" + MoneyMath.formatCents(price) + ")";
    }

    static long personalPrice(final Trip trip, final RegistrationOption question, final OptionChoice choice,
            final LocalDate birthdate) {
        if (trip == null || trip.getStartDate() == null || question == null || !question.isAgeBandsApplyFlag()) {
            return choice.getPriceCents();
        }
        final java.util.OptionalInt age = Ages.ageAt(birthdate, trip.getStartDate().toLocalDate());
        final AgeBand band = age.isPresent() ? trip.getCheckout().bandFor(age.getAsInt()) : null;
        return band == null ? choice.getPriceCents() : band.getConcession().netCents(choice.getPriceCents(), 0);
    }

    /** One person's line of a quote. */
    public record PersonQuote(String personId, String name, PriceBreakdown breakdown,
            List<CheckoutProblem> problems) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    /**
     * The registration form's live totals: each selected person's price and problems, and the party's total with
     * the processor fee when the payer pays it. Recomputed on every change; never stored.
     */
    public record Quote(List<PersonQuote> persons, List<CheckoutProblem> problems, long creditCents, long feeCents,
            long totalCents, boolean payable) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        public boolean isFree() {
            return creditCents == 0;
        }

        public boolean isReady() {
            return problems.isEmpty() && persons.stream().allMatch(person -> person.problems().isEmpty());
        }

        public String getTotal() {
            return MoneyMath.formatCents(totalCents);
        }

        public String getFee() {
            return MoneyMath.formatCents(feeCents);
        }
    }

    /** The quote for the people selected in {@code draft}; {@code party} is everyone the form shows. */
    public Quote quote(final Trip trip, final RegistrationDraft draft, final List<Person> party) {
        final Prepared prepared = prepare(trip, draft, party, false);
        final long credits = prepared.totalCents();
        final PaymentCommands payments = paymentSource.get();
        final PaymentProcessorConfig config = trip == null ? null : payments.processorConfigFor(trip);
        final TripPaymentConfig effective = trip == null ? null : new OrgCommands(callerSource)
                .effectivePaymentConfig(trip);
        final boolean payerPays = effective != null && effective.getFeesPaidBy() == FeesPaidBy.PAYER;
        final long fee = (config == null || credits == 0) ? 0L
                : MoneyMath.creditFeeCents(credits, config.getEffectiveFeeBps(), config.getEffectiveFeeFixedCents());
        return new Quote(prepared.persons(), prepared.problems(), credits, payerPays ? fee : 0L,
                payerPays ? credits + fee : credits, config != null);
    }

    /**
     * Every reason {@link #start} would refuse this party, as coded problems (the REST edge answers them; the
     * page shows them as growls): the quote's checks plus who may register whom, already registered, unsigned
     * documents, and capacity. Empty when the start would go ahead.
     */
    public List<CheckoutProblem> problemsBeforeStart(final Trip trip, final RegistrationDraft draft,
            final List<Person> party) {
        final Prepared prepared = prepare(trip, draft, party, true);
        final List<CheckoutProblem> all = new ArrayList<>(prepared.problems());
        prepared.persons().forEach(person -> all.addAll(person.problems()));
        return all;
    }

    /**
     * Whether {@code question} is a PAID answer on {@code trip}: the trip registers through checkout and the question
     * carries a price. A paid answer is what the person paid for, so the self-service edit paths keep it as stored
     * (changing Friday-only to the full weekend would otherwise be free); staff change it on the admin pages.
     */
    public static boolean isPaidQuestion(final Trip trip, final RegistrationOption question) {
        return trip != null && question != null && trip.getCheckout().isOn() && question.isPriced();
    }

    /** {@code posted} with every paid answer (see {@link #isPaidQuestion}) put back to its {@code stored} value. */
    public static Map<String, String> keepPaidAnswers(final Trip trip, final Map<String, String> stored,
            final Map<String, String> posted) {
        final Map<String, String> kept = new java.util.LinkedHashMap<>(posted);
        if (trip == null || trip.getRegOptions() == null) {
            return kept;
        }
        for (final RegistrationOption question : trip.getRegOptions()) {
            if (!isPaidQuestion(trip, question)) {
                continue;
            }
            final String paid = stored.get(question.getKey());
            if (paid == null) {
                kept.remove(question.getKey());
            } else {
                kept.put(question.getKey(), paid);
            }
        }
        return kept;
    }

    // ------------------------------------------------------------------ start

    /**
     * How a started checkout continues: a processor {@code start} (redirect or inline form), or an immediate
     * {@code completion} when nothing is owed (the party was free and is already registered).
     */
    public record Started(PaymentCommands.Start start, PaymentCommands.Completion completion)
            implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    /**
     * Starts the selected party's checkout, or answers null with growls naming every problem. Waiver agreements in
     * the draft are recorded first (consent stands even if the payment is abandoned, as in party registration).
     * A party that owes nothing is registered at once through an OFFLINE "free" payment, the same pipeline.
     *
     * @param payerId whose payment this is (the signed-in person, or the person an admin is registering for)
     * @param pageUrl the registration page's own absolute URL: the processor returns there
     */
    public Started start(final Trip trip, final RegistrationDraft draft, final List<Person> party,
            final Person.Id payerId, final boolean sandbox, final String pageUrl) {
        final Person payer = payerOf(trip, payerId);
        if (payer == null) {
            return refuse("You cannot register this party.");
        }
        applyWaiverAgreements(trip, draft);
        final Prepared prepared = prepare(trip, draft, party, true);
        if (!prepared.problems().isEmpty() || prepared.persons().stream().anyMatch(p -> !p.problems().isEmpty())) {
            growlProblems(prepared);
            return null;
        }
        if (prepared.rows().isEmpty()) {
            return refuse("Nothing to register: select at least one person.");
        }
        if (prepared.totalCents() == 0) {
            final Payment free = offlinePayment(trip, payer.getId(), prepared, 0L, "FREE", LocalDateTime.now());
            return new Started(null, paymentSource.get().recordOffline(free));
        }
        final PaymentCommands payments = paymentSource.get();
        final PaymentProcessorConfig config = payments.processorConfigFor(trip);
        if (config == null) {
            return refuse("This event is not set up to take payments.");
        }
        if (sandbox && !payments.isSandboxAllowed(trip)) {
            return refuse("Sandbox mode requires the paymentsAdmin privilege for this event's organization.");
        }
        final TripPaymentConfig effective = new OrgCommands(callerSource).effectivePaymentConfig(trip);
        final Payment payment = payments.newPayment(trip, payer, config, effective, prepared.allocations(), 0L,
                sandbox);
        payment.setCheckout(prepared.party(payment.getPaymentId()));
        final PaymentCommands.Start start = payments.startPrepared(trip, payment, config, payer, pageUrl,
                "Registration - " + trip.getTitle());
        return start == null ? null : new Started(start, null);
    }

    /**
     * Records the selected party as paid OUTSIDE any processor (docs/checkout.md): a check, cash, a comp or other.
     * Registration admins only. The amount must equal the party's total unless {@code partial} (then the Bills
     * still bill the full prices and each balance shows what is left); a COMP is always the full total, credited
     * as complimentary. Answers the completion, or null with growls.
     */
    public PaymentCommands.Completion recordOffline(final Trip trip, final RegistrationDraft draft,
            final List<Person> party, final Person.Id payerId, final String method, final String reference,
            final Double amountDollars, final LocalDate receivedOn, final boolean partial) {
        if (!canAdminister(trip)) {
            return refuse("Only a registration admin may record an offline payment.");
        }
        final String kind = method == null ? "" : method.trim().toUpperCase(java.util.Locale.ROOT);
        if (!OFFLINE_METHODS.contains(kind)) {
            return refuse("Choose how the payment was made.");
        }
        applyWaiverAgreements(trip, draft);
        final Prepared prepared = prepare(trip, draft, party, true);
        if (!prepared.problems().isEmpty() || prepared.persons().stream().anyMatch(p -> !p.problems().isEmpty())) {
            growlProblems(prepared);
            return null;
        }
        if (prepared.rows().isEmpty()) {
            return refuse("Nothing to register: select at least one person.");
        }
        final long total = prepared.totalCents();
        final long received = "COMP".equals(kind) ? total
                : amountDollars == null ? -1L : Math.round(amountDollars * 100);
        if (received < 0 || received > total || (!partial && received != total)) {
            return refuse("The amount received must be " + MoneyMath.formatCents(total)
                    + (partial ? " or less" : "") + ".");
        }
        final String label = kind + (reference == null || reference.isBlank() ? "" : " " + reference.trim());
        final LocalDateTime when = receivedOn == null ? LocalDateTime.now() : receivedOn.atStartOfDay();
        final Payment payment = offlinePayment(trip, payerId == null ? callerSource.get().personId() : payerId,
                prepared, received, label, when);
        return paymentSource.get().recordOffline(payment);
    }

    /** Finishes a checkout's return (or cancel) from the processor: the page's completion door. */
    public PaymentCommands.Completion complete(final String paymentId, final String token) {
        return paymentSource.get().completePayment(paymentId, token);
    }

    // ------------------------------------------------------------------ finalize (called by PaymentCommands)

    /**
     * Writes a recorded checkout's registrations: each pending row not already written (re-running is harmless),
     * the roster for the CONFIRMED ones, org membership, and each person's Bill for their price, so a paid
     * person's trip balance is zero. Someone registered in the meantime by another route is never overwritten,
     * only noted. Capacity is re-counted afterwards and an overrun noted: money has moved, so recording never
     * fails for it. A SANDBOX payment writes nothing (its ledger rows are dry-run too). Throws when a write fails:
     * the payment then stays CAPTURED for the reconciliation Finish.
     */
    public void finalizeParty(final Payment payment, final Trip given) {
        final PendingParty party = payment.getCheckout();
        if (party == null || payment.isSandbox()) {
            return;
        }
        final Trip trip = DAO.getInstance().getTrip(payment.getTripId(), Cached.NO).orElse(given);
        if (trip == null) {
            throw new IllegalStateException("The trip of checkout payment " + payment.getPaymentId() + " is gone");
        }
        final List<String> warnings = new ArrayList<>();
        final List<Registration> confirmed = new ArrayList<>();
        for (final Registration row : party.getRows()) {
            final Registration written = writeRow(trip, row, payment, warnings);
            if (written != null && written.getStatus() == Registration.Status.CONFIRMED) {
                confirmed.add(written);
            }
            billRow(trip, row, payment);
        }
        if (!confirmed.isEmpty()) {
            confirmed.forEach(row -> trip.getPeople().add(row.getUserId()));
            new TripCommands().saveTrip(trip);
            final RegistrationCommands regs = new RegistrationCommands(callerSource);
            confirmed.forEach(row -> regs.afterConfirmed(trip, row));
        }
        warnings.addAll(capacityWarnings(trip));
        party.setFinalizedAt(clock.get());
        party.setWarnings(warnings);
    }

    /** Writes one pending row unless it is already there; answers what was written, or null. */
    private Registration writeRow(final Trip trip, final Registration row, final Payment payment,
            final List<String> warnings) {
        final Registration stored = DAO.getInstance().getRegistration(trip.getId(), row.getUserId(), Cached.NO)
                .orElse(null);
        if (stored != null && stored.getStatus() != Registration.Status.NOT_REGISTERED) {
            if (!payment.getPaymentId().equals(stored.getPaymentId())) {
                warnings.add(nameOf(row.getUserId()) + " was already registered (" + stored.getStatus()
                        + ") when this payment was recorded.");
            }
            return null;
        }
        final Registration toWrite = RegistrationCommands.withTicket(row.withPaymentId(payment.getPaymentId()));
        try {
            if (!DAO.getInstance().saveRegistration(toWrite)) {
                throw new IllegalStateException("Registration write failed for " + row.getUserId());
            }
        } catch (final IOException ex) {
            throw new IllegalStateException("Registration write failed for " + row.getUserId(), ex);
        }
        new OrgCommands(callerSource).joinOnRegistration(trip, row.getUserId());
        return toWrite;
    }

    /** The person's Bill for their price ({@code {paymentId}-bill-{personId}}, deterministic, so idempotent). */
    private static void billRow(final Trip trip, final Registration row, final Payment payment) {
        final PriceBreakdown breakdown = row.getPriceBreakdown();
        final long price = breakdown == null ? 0L : breakdown.getTotalCents();
        final String type = row.getRegistrationType() == null ? "Registration" : row.getRegistrationType();
        Bills.apply(new TransactionsCommands(), billTxId(payment, row.getUserId()), row.getUserId(), -price,
                BILL_CATEGORY, type + " for " + trip.getTitle() + ": "
                        + (breakdown == null ? MoneyMath.formatCents(price) : breakdown.summary())
                        + " (payment id #" + payment.getCaptureId() + ")", trip.getId(), null);
    }

    /** A registration's Bill row id. */
    public static String billTxId(final Payment payment, final Person.Id personId) {
        return payment.getPaymentId() + "-bill-" + personId.getValue();
    }

    private static List<String> capacityWarnings(final Trip trip) {
        final List<CheckoutProblem> over = CheckoutCapacity.problems(trip,
                DAO.getInstance().getRegistrations(trip.getId(), Cached.NO), List.of(Map.of()));
        return over.stream().map(problem -> "Over capacity after this payment: " + problem.message()).toList();
    }

    // ------------------------------------------------------------------ admin views

    /** The stored breakdown of one registration, or null: the Registrations tab's "details". */
    public PriceBreakdown breakdownFor(final List<Registration> regs, final String userId) {
        final Registration reg = new RegistrationCommands(callerSource).rowRegistration(regs, userId);
        return reg == null ? null : reg.getPriceBreakdown();
    }

    /** "3 people, $310.00, confirmed" for a checkout payment on the reconciliation list; "" otherwise. */
    public String intentSummary(final Payment payment) {
        if (payment == null || !payment.hasCheckout()) {
            return "";
        }
        final PendingParty party = payment.getCheckout();
        final long total = party.getRows().stream().mapToLong(CheckoutCommands::priceOf).sum();
        final String state = party.getFinalizedAt() != null ? "registered"
                : payment.getStatus() == Payment.Status.CREATED ? "awaiting payment" : "not yet registered";
        return party.getRows().size() + (party.getRows().size() == 1 ? " person, " : " people, ")
                + MoneyMath.formatCents(total) + ", " + state
                + (party.getWarnings().isEmpty() ? "" : " (" + String.join(" ", party.getWarnings()) + ")");
    }

    private static long priceOf(final Registration row) {
        return row.getPriceBreakdown() == null ? 0L : row.getPriceBreakdown().getTotalCents();
    }

    // ------------------------------------------------------------------ preparing a party

    /** The selected people, priced and checked, and the rows a payment for them would write. */
    record Prepared(List<PersonQuote> persons, List<CheckoutProblem> problems, List<Registration> rows,
            List<Payment.Allocation> allocations, long totalCents, Person.Id registeredBy, String partyId) {

        PendingParty party(final String paymentId) {
            final List<Registration> withPayment = rows.stream().map(row -> row.withPaymentId(paymentId)).toList();
            return new PendingParty(partyId, registeredBy, withPayment, null, null);
        }
    }

    /**
     * Prices and checks every selected person of {@code draft}. {@code forStart} adds the checks that only matter
     * when money is about to move (who may register whom, already registered, capacity) and builds the rows.
     */
    Prepared prepare(final Trip trip, final RegistrationDraft draft, final List<Person> party,
            final boolean forStart) {
        final List<PersonQuote> persons = new ArrayList<>();
        final List<CheckoutProblem> problems = new ArrayList<>();
        final List<Registration> rows = new ArrayList<>();
        final List<Payment.Allocation> allocations = new ArrayList<>();
        final Caller caller = callerSource.get();
        if (trip == null || draft == null || party == null) {
            return new Prepared(persons, problems, rows, allocations, 0L, caller.personId(), null);
        }
        final LocalDateTime now = clock.get();
        final List<Registration> existing = DAO.getInstance().getRegistrations(trip.getId(), Cached.NO);
        final String partyId = UUID.randomUUID().toString();
        final List<Map<String, String>> partyAnswers = new ArrayList<>();
        final Map<String, Integer> codeUses = new HashMap<>();
        long total = 0L;
        for (final Person person : party) {
            final String pid = person.getId().getValue();
            if (!Boolean.TRUE.equals(draft.getSel().get(pid))) {
                continue;
            }
            final Registration draftReg = draft.getRegs().get(pid);
            final Map<String, String> answers = draftReg == null ? Map.of() : draftReg.getOptions();
            final String code = draft.getCodes().get(pid);
            final Set<String> attested = attestedFor(draft, pid);
            final int uses = CheckoutCapacity.codeUses(existing, code) + codeUses.getOrDefault(normalized(code), 0);
            final List<CheckoutProblem> own = new ArrayList<>(
                    CheckoutValidator.problems(trip, pid, answers, code, attested, now, uses));
            if (forStart) {
                own.addAll(startProblems(trip, person, existing));
            }
            final PriceBreakdown breakdown = CheckoutPricing.price(trip, person.getBirthdate(), answers, code, now);
            persons.add(new PersonQuote(pid, person.getPreferredName() + " " + person.getLast(), breakdown, own));
            partyAnswers.add(answers);
            if (breakdown.getInputs().getCodeText() != null) {
                codeUses.merge(breakdown.getInputs().getCodeText(), 1, Integer::sum);
            }
            total += breakdown.getTotalCents();
            allocations.add(new Payment.Allocation(person.getId(), breakdown.getTotalCents()));
            rows.add(rowFor(trip, person, answers, attested, breakdown, partyId, draft, caller));
        }
        if (forStart) {
            problems.addAll(CheckoutCapacity.problems(trip, existing, partyAnswers));
        }
        return new Prepared(persons, problems, rows, allocations, total, caller.personId(), partyId);
    }

    private List<CheckoutProblem> startProblems(final Trip trip, final Person person,
            final List<Registration> existing) {
        final List<CheckoutProblem> problems = new ArrayList<>();
        final String pid = person.getId().getValue();
        if (!canAdminister(trip) && !new RegistrationCommands(callerSource).canRegister(person)) {
            problems.add(new CheckoutProblem("NOT_ALLOWED", pid, null,
                    "You cannot register " + person.getPreferredName() + "."));
        }
        if (existing.stream().anyMatch(reg -> reg.getUserId().equals(person.getId())
                && reg.getStatus() != Registration.Status.NOT_REGISTERED)) {
            problems.add(new CheckoutProblem("ALREADY_REGISTERED", pid, null,
                    person.getPreferredName() + " is already registered."));
        }
        if (!new WaiverCommands(callerSource).isSatisfied(trip, person.getId())) {
            problems.add(new CheckoutProblem("WAIVERS_REQUIRED", pid, null, person.getPreferredName()
                    + " must agree to every required document: tick both boxes under each one."));
        }
        return problems;
    }

    /** The pending registration for one person: everything but its payment id, which the payment supplies. */
    private Registration rowFor(final Trip trip, final Person person, final Map<String, String> answers,
            final Set<String> attested, final PriceBreakdown breakdown, final String partyId,
            final RegistrationDraft draft, final Caller caller) {
        final CheckoutConfig policy = trip.getCheckout();
        final Map<String, String> options = new HashMap<>(answers);
        options.put(Registration.OPT_PARTY, partyId);
        if (caller.personId() != null) {
            options.put(Registration.OPT_REGISTERED_BY, caller.personId().getValue());
        }
        final Registration row = new Registration(trip.getId(), person.getId(), clock.get(),
                policy.isConfirmPaid() ? Registration.Status.CONFIRMED : Registration.Status.PENDING, options)
                .withRegistrationType(breakdown.getRegistrationType())
                .withPriceBreakdown(breakdown)
                .withAttestations(attestationTexts(trip, attested));
        if (trip.getChatEnabled()) {
            new ChatCommands().setDigestChoice(row,
                    !Boolean.FALSE.equals(draft.getDigest().get(person.getId().getValue())));
        }
        return RegistrationCommands.withTicket(row);
    }

    /** The attestation TEXT each ticked choice showed, as worded when it was ticked. */
    private static Map<String, String> attestationTexts(final Trip trip, final Set<String> attested) {
        final Map<String, String> texts = new HashMap<>();
        for (final RegistrationOption question : trip.getRegOptions()) {
            for (final OptionChoice choice : question.getChoices()) {
                if (choice.isAttestationRequired() && attested.contains(choice.getId())) {
                    texts.put(choice.getId(), choice.getAttestationText());
                }
            }
        }
        return texts;
    }

    private static Set<String> attestedFor(final RegistrationDraft draft, final String pid) {
        final Set<String> attested = new HashSet<>();
        final String prefix = pid + "|";
        draft.getAttest().forEach((key, value) -> addIfTicked(attested, key, value, prefix));
        return attested;
    }

    private static void addIfTicked(final Set<String> attested, final String key, final Object value,
            final String prefix) {
        if (key.startsWith(prefix) && Boolean.TRUE.equals(value)) {
            attested.add(key.substring(prefix.length()));
        }
    }

    private static String normalized(final String code) {
        return org.paulsens.trip.model.DiscountCode.normalize(code);
    }

    private void applyWaiverAgreements(final Trip trip, final RegistrationDraft draft) {
        if (draft == null || trip == null || trip.getWaivers().isEmpty()) {
            return;
        }
        final WaiverCommands waivers = new WaiverCommands(callerSource);
        WaiverCommands.agreementsFromChecks(draft.getWaivers()).forEach((pid, agreements) -> waivers
                .applyAgreements(trip, Person.Id.from(pid), agreements, WaiverCommands.ClientInfo.web()));
    }

    // ------------------------------------------------------------------ helpers

    /** The payer: the caller, or (for an admin) the person they are registering for. */
    private Person payerOf(final Trip trip, final Person.Id payerId) {
        final Caller caller = callerSource.get();
        final Person.Id who = payerId == null ? caller.personId() : payerId;
        if (who == null || (!who.equals(caller.personId()) && !canAdminister(trip))) {
            return null;
        }
        return DAO.getInstance().getPerson(who, Cached.NO).orElse(null);
    }

    /** An OFFLINE payment for the prepared party, already CAPTURED: see PaymentCommands.recordOffline. */
    private Payment offlinePayment(final Trip trip, final Person.Id payerId, final Prepared prepared,
            final long receivedCents, final String label, final LocalDateTime receivedAt) {
        final Payment payment = Payment.builder()
                .tripId(trip.getId())
                .orgId(trip.getOrgId())
                .payerId(payerId)
                .processorType(ProcessorType.OFFLINE)
                .allocations(allocate(prepared.allocations(), receivedCents))
                .feesPaidBy(FeesPaidBy.ORGANIZATION)
                .totalChargedCents(receivedCents)
                .captureId(label)
                .capturedGrossCents(receivedCents)
                .status(Payment.Status.CAPTURED)
                .createdAt(LocalDateTime.now())
                .capturedAt(receivedAt)
                .build();
        payment.setCheckout(prepared.party(payment.getPaymentId()));
        return payment;
    }

    /** Spreads what was received over the party in order, each person up to their own price. */
    static List<Payment.Allocation> allocate(final List<Payment.Allocation> prices, final long receivedCents) {
        final List<Payment.Allocation> allocated = new ArrayList<>();
        long left = receivedCents;
        for (final Payment.Allocation price : prices) {
            final long share = Math.min(left, price.getAmountCents());
            allocated.add(new Payment.Allocation(price.getPersonId(), share));
            left -= share;
        }
        return allocated;
    }

    private static void growlProblems(final Prepared prepared) {
        prepared.persons().forEach(person -> person.problems().forEach(p -> PageFeedback.warn(p.message())));
        prepared.problems().forEach(problem -> PageFeedback.warn(problem.message()));
    }

    private static <T> T refuse(final String message) {
        PageFeedback.warn(message);
        return null;
    }

    private static String nameOf(final Person.Id personId) {
        return DAO.getInstance().getPerson(personId, Cached.YES)
                .map(person -> person.getPreferredName() + " " + person.getLast())
                .orElse(personId.getValue());
    }

    // ================================================================== page view helpers
    //
    // EL-friendly views for trip/joinTrip.xhtml, WEB-INF/regOptionsDialog.xhtml and admin/tripRegistrations.xhtml.
    // No flow logic: each one reads, formats, or (for the pricing editor) adds and removes a row of the trip
    // editor's DRAFT, which nothing persists until the trip page's Save.

    /** The draft's attestation key for one person's choice ({@link RegistrationDraft#attestKey}). */
    public String attestKey(final String personId, final String choiceId) {
        return RegistrationDraft.attestKey(personId, choiceId);
    }

    /** The choices of {@code question} picked in {@code answers} that require an attestation, in pick order. */
    public List<OptionChoice> attestationsFor(final RegistrationOption question, final Map<String, String> answers) {
        final List<OptionChoice> picked = new ArrayList<>();
        if (question == null || answers == null || !question.isChoiceType()) {
            return picked;
        }
        for (final String choiceId : question.choiceIdsOf(answers.get(question.getKey()))) {
            final OptionChoice choice = question.choice(choiceId);
            if (choice != null && choice.isAttestationRequired()) {
                picked.add(choice);
            }
        }
        return picked;
    }

    /** {@code $1,234.56}, for EL. */
    public String formatCents(final long cents) {
        return MoneyMath.formatCents(cents);
    }

    /** One breakdown line as a page shows it: the price of a base or add-on, a signed change otherwise. */
    public record LineView(String label, String amount, String kind) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    /** The ordered lines of a breakdown, ready to print; empty for none. */
    public List<LineView> breakdownLines(final PriceBreakdown breakdown) {
        final List<LineView> lines = new ArrayList<>();
        if (breakdown != null) {
            breakdown.getLines().forEach(line -> lines.add(lineView(line)));
        }
        return lines;
    }

    private static LineView lineView(final PriceBreakdown.Line line) {
        final long delta = line.getDeltaCents();
        final String amount = switch (line.getKind()) {
            case BASE, ADD_ON -> MoneyMath.formatCents(delta);
            case NOTE -> "";
            default -> delta < 0 ? "-" + MoneyMath.formatCents(-delta) : "+" + MoneyMath.formatCents(delta);
        };
        return new LineView(line.getLabel(), amount, line.getKind().name());
    }

    /**
     * Who pays when the page's SUBJECT is registered: the subject when the caller administers registration (an
     * admin registering on someone's behalf), else the caller (a family manager acting for a member still pays
     * as themselves; {@link #start} refuses any other payer).
     */
    public Person.Id payerFor(final Trip trip, final Person.Id subjectId) {
        return canAdminister(trip) && subjectId != null ? subjectId : callerSource.get().personId();
    }

    /** The ids of the people selected in {@code draft}, in party order: what a result panel lists afterwards. */
    public List<String> selectedIds(final RegistrationDraft draft, final List<Person> party) {
        final List<String> ids = new ArrayList<>();
        if (draft == null || party == null) {
            return ids;
        }
        for (final Person person : party) {
            final String pid = person.getId().getValue();
            if (Boolean.TRUE.equals(draft.getSel().get(pid))) {
                ids.add(pid);
            }
        }
        return ids;
    }

    /**
     * The person ids a checkout payment registers, for its result panel; empty unless the caller may finish the
     * payment (its payer, a site admin, or the trip's registration admin) or it is not a checkout.
     */
    public List<String> partyIdsOf(final String paymentId) {
        final Payment payment = paymentId == null ? null : DAO.getInstance().getPayment(paymentId).orElse(null);
        if (payment == null || !payment.hasCheckout()) {
            return new ArrayList<>();
        }
        final Caller caller = callerSource.get();
        final boolean mine = payment.getPayerId() != null && payment.getPayerId().equals(caller.personId());
        if (!mine && !caller.isSiteAdmin() && !caller.has(PrivilegeCommands.REGISTRATION_ADMIN, payment.getTripId())) {
            return new ArrayList<>();
        }
        return payment.getCheckout().getRows().stream().map(row -> row.getUserId().getValue())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /** One registered person on a checkout's result panel. */
    public record ResultRow(String personId, String name, String registrationType, String price, String status,
            String ticketCode) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    /**
     * The stored registrations of {@code personIds} on {@code trip}, read fresh: the result panel after a
     * checkout. A person the caller may neither register nor administer is left out.
     */
    public List<ResultRow> resultRows(final Trip trip, final List<String> personIds) {
        final List<ResultRow> rows = new ArrayList<>();
        if (trip == null || personIds == null) {
            return rows;
        }
        final boolean admin = canAdminister(trip);
        final RegistrationCommands regs = new RegistrationCommands(callerSource);
        for (final String pid : personIds) {
            final Person person = DAO.getInstance().getPerson(Person.Id.from(pid), Cached.YES).orElse(null);
            if (person != null && (admin || regs.canRegister(person))) {
                rows.add(resultRow(trip, person));
            }
        }
        return rows;
    }

    private static ResultRow resultRow(final Trip trip, final Person person) {
        final Registration reg = DAO.getInstance().getRegistration(trip.getId(), person.getId(), Cached.NO)
                .orElse(null);
        final String name = person.getPreferredName() + " " + person.getLast();
        if (reg == null) {
            return new ResultRow(person.getId().getValue(), name, "", "", "Not Registered", "");
        }
        return new ResultRow(person.getId().getValue(), name, blank(reg.getRegistrationType()),
                reg.getPriceBreakdown() == null ? "" : MoneyMath.formatCents(reg.getPriceBreakdown().getTotalCents()),
                reg.getStatus().getDescription(), blank(reg.getTicketCode()));
    }

    private static String blank(final String text) {
        return text == null ? "" : text;
    }

    // ------------------------------------------------------------------ the trip editor's Pricing fieldset

    /** What is wrong with the trip's checkout policy as the editor left it; empty when it may be saved. */
    public List<String> configProblems(final Trip trip) {
        return trip == null ? List.of() : trip.getCheckout().problems();
    }

    /**
     * The Pricing fieldset's Done: growls every problem and publishes {@code pricingOk} so the dialog stays open
     * on a refusal (closing on a growl reads as "saved anyway"). Answers whether the policy is acceptable.
     */
    public boolean acceptConfig(final Trip trip) {
        final List<String> problems = configProblems(trip);
        problems.forEach(PageFeedback::warn);
        PageFeedback.callbackParam("pricingOk", problems.isEmpty());
        return problems.isEmpty();
    }

    /** The adjustments an age band or a code may make (lodging's per-night kind has no meaning here). */
    public List<Concession.Kind> getConcessionKinds() {
        return List.of(Concession.Kind.PERCENT,
                Concession.Kind.TOTAL,
                Concession.Kind.FIXED_PRICE,
                Concession.Kind.COMPLIMENTARY);
    }

    /** The menu label of a concession kind. */
    public String kindLabel(final Concession.Kind kind) {
        if (kind == null) {
            return "";
        }
        return switch (kind) {
            case PERCENT -> "% off";
            case TOTAL -> "$ off";
            case FIXED_PRICE -> "At most $";
            case COMPLIMENTARY -> "Free";
            case PER_NIGHT -> "$ off per night";
        };
    }

    /**
     * The editor's ONE "$ or %" cell for a concession: a percentage for PERCENT, dollars otherwise. Kind is
     * decoded before amount (column order), so the write lands in the field the new kind reads.
     */
    public ConcessionAmount amountOf(final Concession concession) {
        return new ConcessionAmount(concession);
    }

    /** The bindable adapter behind {@link #amountOf}. */
    public static final class ConcessionAmount {
        private final Concession concession;

        ConcessionAmount(final Concession concession) {
            this.concession = concession;
        }

        public Double getValue() {
            if (concession == null) {
                return null;
            }
            return concession.getKind() == Concession.Kind.PERCENT
                    ? concession.getPercentBps() / 100.0 : concession.getAmountCents() / 100.0;
        }

        public void setValue(final Double value) {
            if (concession == null) {
                return;
            }
            final long hundredths = (value == null || value < 0) ? 0L : Math.round(value * 100);
            if (concession.getKind() == Concession.Kind.PERCENT) {
                concession.setPercentBps((int) Math.min(10_000L, hundredths));
            } else {
                concession.setAmountCents(hundredths);
            }
        }
    }

    /** A priced choice as the code editor's "valid for" menu lists it. */
    public record ChoiceItem(String id, String label) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    /** Every priced choice of the trip's pick-one and pick-any questions, labelled "Question: Choice". */
    public List<ChoiceItem> pricedChoices(final Trip trip) {
        final List<ChoiceItem> items = new ArrayList<>();
        if (trip == null) {
            return items;
        }
        for (final RegistrationOption question : trip.getRegOptions()) {
            for (final OptionChoice choice : question.getChoices()) {
                if (question.isChoiceType() && choice.isPriced()) {
                    items.add(new ChoiceItem(choice.getId(), question.getShortDesc() + ": " + choice.getLabel()));
                }
            }
        }
        return items;
    }

    /**
     * A row's identity in the editor's tables, baked into its remove button at render: the draft is ONE heap
     * instance for the whole edit (TripEditDrafts), so the object a render saw is the object the click finds,
     * whatever the rows' positions by then. Age bands have no id of their own, and a new code no code yet.
     */
    public String rowKey(final Object row) {
        return row == null ? "" : Integer.toHexString(System.identityHashCode(row));
    }

    /** Appends an empty age band to the draft's policy. */
    public void addBand(final Trip trip) {
        if (trip != null) {
            trip.getCheckout().getAgeBands().add(new AgeBand());
        }
    }

    /** Removes the draft's age band whose {@link #rowKey} is {@code key}; answers whether one went. */
    public boolean removeBand(final Trip trip, final String key) {
        return trip != null && key != null
                && trip.getCheckout().getAgeBands().removeIf(band -> key.equals(rowKey(band)));
    }

    /** Appends an empty discount code to the draft's policy. */
    public void addCode(final Trip trip) {
        if (trip != null) {
            trip.getCheckout().getCodes().add(new DiscountCode());
        }
    }

    /** Removes the draft's discount code whose {@link #rowKey} is {@code key}; answers whether one went. */
    public boolean removeCode(final Trip trip, final String key) {
        return trip != null && key != null
                && trip.getCheckout().getCodes().removeIf(code -> key.equals(rowKey(code)));
    }

    // ------------------------------------------------------------------ the Registrations tab

    /** The registration type of a row ("Full weekend"), or "". */
    public String typeOf(final List<Registration> regs, final String userId) {
        final Registration reg = new RegistrationCommands(callerSource).rowRegistration(regs, userId);
        return reg == null ? "" : blank(reg.getRegistrationType());
    }

    /** What a row's checkout charged (its stored price) when a payment registered it, or "". */
    public String paidText(final List<Registration> regs, final String userId) {
        final Registration reg = new RegistrationCommands(callerSource).rowRegistration(regs, userId);
        if (reg == null || reg.getPaymentId() == null) {
            return "";
        }
        return reg.getPriceBreakdown() == null ? "paid"
                : MoneyMath.formatCents(reg.getPriceBreakdown().getTotalCents());
    }

    /** Everything the Registrations tab's "details" dialog explains about one row's price. */
    public record RegDetails(String name, String registrationType, Integer ageAtStart, LocalDate birthdate,
            List<String> choices, String code, List<String> attestations, List<LineView> lines, String total,
            String paymentId, String ticketCode) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    /** The details of {@code userId}'s registration in {@code regs}, or null when there is none. */
    public RegDetails details(final Trip trip, final List<Registration> regs, final String userId) {
        final Registration reg = new RegistrationCommands(callerSource).rowRegistration(regs, userId);
        if (trip == null || reg == null) {
            return null;
        }
        final Person person = DAO.getInstance().getPerson(reg.getUserId(), Cached.YES).orElse(null);
        final PriceBreakdown breakdown = reg.getPriceBreakdown();
        final PriceBreakdown.Inputs inputs = breakdown == null ? null : breakdown.getInputs();
        final List<String> choices = new ArrayList<>();
        if (inputs != null) {
            inputs.getChoiceIds().forEach(id -> choices.add(choiceLabelOf(trip, id)));
        }
        final List<String> attestations = new ArrayList<>();
        reg.getAttestations().forEach((id, text) -> attestations.add(choiceLabelOf(trip, id) + ": " + text));
        return new RegDetails(person == null ? userId : person.getPreferredName() + " " + person.getLast(),
                blank(reg.getRegistrationType()), inputs == null ? null : inputs.getAgeAtStart(),
                person == null ? null : person.getBirthdate(), choices,
                inputs == null ? "" : blank(inputs.getCodeText()), attestations, breakdownLines(breakdown),
                breakdown == null ? "" : MoneyMath.formatCents(breakdown.getTotalCents()), blank(reg.getPaymentId()),
                blank(reg.getTicketCode()));
    }

    /** A choice id's label on this trip, or the id itself when no question offers it any more. */
    private static String choiceLabelOf(final Trip trip, final String choiceId) {
        for (final RegistrationOption question : trip.getRegOptions()) {
            final OptionChoice choice = question.choice(choiceId);
            if (choice != null) {
                return choice.getLabel();
            }
        }
        return choiceId;
    }
}
