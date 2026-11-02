package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.OptionChoice;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.RegistrationOption;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.pay.MoneyMath;
import org.paulsens.trip.security.TicketCodes;
import org.paulsens.trip.site.SiteUrls;
import org.paulsens.trip.util.Ages;
import org.paulsens.trip.util.TicketQr;

/**
 * Tickets and event check-in ({@code docs/checkin.md}): the gates, staff lookup by name or ticket, the check-in
 * stamp, the live counts, a person's trip-bound balance, the ticket page's view, batch badges and the export rows.
 * The REST check-in endpoints ({@code RegistrationsResource}) wrap this bean with their own caller.
 *
 * <p>Every gate answers false for a visitor who is not signed in BEFORE it reads anything, so the anonymous ticket
 * page asks them without touching a session. Registrations are read fresh (they are uncached by design), and every
 * write re-reads the row it changes.
 */
@Slf4j
@Named("checkin")
@ApplicationScoped
public class CheckinCommands {
    /** The most rows one staff search answers: a name search is for finding one person, not listing a hall. */
    public static final int LOOKUP_CAP = 50;
    /** The bucket a registration without a registration type counts under. */
    public static final String NO_TYPE = "(none)";

    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US);
    private static final String MEMO_PREFIX = "checkin:";

    private final Supplier<Caller> callerSource;
    private final Supplier<Map<String, Object>> requestMap;
    private final Supplier<LocalDateTime> clock;

    public CheckinCommands() {
        this(Caller::current);
    }

    /** The REST seam: the resource's own caller (bearer or session), no Faces request map. */
    public CheckinCommands(final Supplier<Caller> callerSource) {
        this(callerSource, CheckinCommands::facesRequestMap, LocalDateTime::now);
    }

    /** Test seam: a caller, a request map for the per-request memo (null for none) and the clock. */
    CheckinCommands(final Supplier<Caller> callerSource, final Supplier<Map<String, Object>> requestMap,
            final Supplier<LocalDateTime> clock) {
        this.callerSource = callerSource;
        this.requestMap = requestMap;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ gates

    /** May check people in: a site admin, the trip's managers, or its Check-in Staff. */
    public boolean canCheckIn(final String tripId) {
        return signedInOnKnownTrip(tripId)
                && (has(PrivilegeCommands.TRIP_MGR, tripId) || has(PrivilegeCommands.CHECKIN_STAFF, tripId));
    }

    /** May undo a check-in: a site admin or the trip's managers, never Check-in Staff (they scan, they do not edit). */
    public boolean canClearCheckIn(final String tripId) {
        return signedInOnKnownTrip(tripId) && has(PrivilegeCommands.TRIP_MGR, tripId);
    }

    /**
     * May read the counts: whoever checks people in, plus the Reports dashboard's union (tripView, the two finance
     * viewers, tripFinAdmin; tripMgr is already a checker), so the Check-in tab and the dashboard card agree.
     */
    public boolean canViewCounts(final String tripId) {
        if (canCheckIn(tripId)) {
            return true;
        }
        return signedInOnKnownTrip(tripId) && (has(PrivilegeCommands.TRIP_VIEW, tripId)
                || has(PrivilegeCommands.TRIP_FIN_VIEW, tripId) || has(PrivilegeCommands.TRIP_FIN_ADMIN, tripId)
                || caller().has(PrivilegeCommands.VIEW_FINANCES));
    }

    /** May see the money row: the dashboard's finance union (site admin, viewFinances, tripFinView@trip). */
    public boolean canSeeMoney(final String tripId) {
        return signedInOnKnownTrip(tripId) && (caller().has(PrivilegeCommands.VIEW_FINANCES)
                || has(PrivilegeCommands.TRIP_FIN_VIEW, tripId));
    }

    /** May open the batch badges: the trip's viewers (who print the roster today) or anyone who checks people in. */
    public boolean canPrintBadges(final String tripId) {
        return canCheckIn(tripId) || (signedInOnKnownTrip(tripId) && has(PrivilegeCommands.TRIP_VIEW, tripId));
    }

    /**
     * The trips the Admin menu lists as "Check-in: {title}": active trips where the signed-in person holds Check-in
     * Staff EXPLICITLY. Staff are not on the roster, so the trip is absent from their Trips menu; a site admin is not
     * given an entry per trip.
     */
    public List<Trip> staffTrips() {
        final Caller caller = caller();
        if (!caller.isAuthenticated()) {
            return List.of();
        }
        return memo("staffTrips", () -> staffTripsOf(caller.personId(), new TripCommands().getActiveTrips(1)));
    }

    /** The candidates where {@code me} holds Check-in Staff explicitly (no site-admin short-circuit). */
    static List<Trip> staffTripsOf(final Person.Id me, final List<Trip> candidates) {
        final PrivilegeCommands privileges = new PrivilegeCommands();
        return candidates.stream()
                .filter(trip -> privileges.check(PrivilegeCommands.CHECKIN_STAFF, trip.getId(), me))
                .toList();
    }

    // ------------------------------------------------------------------ lookup

    /**
     * Staff search, as rows: a query that is a whole ticket code (typed, grouped or with look-alike digits) matches
     * that ticket EXACTLY and nothing else, never a prefix; any other query must match every whitespace-separated
     * token somewhere in the first, last or preferred name or the email. CONFIRMED and PENDING registrations only,
     * at most {@value #LOOKUP_CAP}, by name. Empty for a caller who may not check in.
     */
    public List<RegistrationCommands.RegRow> lookup(final String tripId, final String query) {
        if (query == null || query.isBlank() || !canCheckIn(tripId)) {
            return List.of();
        }
        final Trip trip = knownTrip(tripId);
        final boolean ticket = TicketCodes.normalize(query) != null;
        final List<String> tokens = tokensOf(query);
        final List<RegistrationCommands.RegRow> rows = new ArrayList<>();
        for (final Registration reg : registrations(tripId)) {
            if (isActive(reg) && (ticket ? TicketCodes.matches(reg.getTicketCode(), query)
                    : matchesTokens(person(reg.getUserId()), tokens))) {
                rows.add(rowOf(trip, reg));
            }
        }
        rows.sort(BY_NAME);
        return rows.size() > LOOKUP_CAP ? new ArrayList<>(rows.subList(0, LOOKUP_CAP)) : rows;
    }

    private static final Comparator<RegistrationCommands.RegRow> BY_NAME =
            Comparator.comparing(RegistrationCommands.RegRow::getName, String.CASE_INSENSITIVE_ORDER);

    /** The lower-cased whitespace-separated tokens of a search query (shared with the Contacts page's search). */
    static List<String> tokensOf(final String query) {
        final List<String> tokens = new ArrayList<>();
        for (final String token : query.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    static boolean matchesTokens(final Person person, final List<String> tokens) {
        if (person == null || tokens.isEmpty()) {
            return false;
        }
        final String haystack = String.join(" ", lower(person.getFirst()), lower(person.getLast()),
                lower(person.getNickname()), lower(person.getEmail()));
        return tokens.stream().allMatch(haystack::contains);
    }

    private static String lower(final String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    /** The registration whose ticket this is, any status, or null; an exact constant-time match, never a prefix. */
    public Registration byTicket(final String tripId, final String code) {
        if (tripId == null || TicketCodes.normalize(code) == null) {
            return null;
        }
        return registrations(tripId).stream()
                .filter(reg -> TicketCodes.matches(reg.getTicketCode(), code))
                .findFirst().orElse(null);
    }

    /**
     * This person's ticket code, minting and storing one first when a CONFIRMED registration predates tickets (the
     * lazy backfill; every new confirmation mints its own). Null when there is no confirmed registration, or when
     * the caller is neither staff here nor the person (or their family manager).
     */
    public String ticketFor(final String tripId, final String personId) {
        final Person.Id subject = idOf(personId);
        if (subject == null || !(canCheckIn(tripId) || mayActFor(subject))) {
            return null;
        }
        return ensureTicket(fresh(tripId, subject));
    }

    private String ensureTicket(final Registration reg) {
        if (reg == null || reg.getStatus() != Registration.Status.CONFIRMED) {
            return null;
        }
        if (reg.getTicketCode() != null) {
            return reg.getTicketCode();
        }
        final Registration minted = RegistrationCommands.withTicket(reg);
        return save(minted) ? minted.getTicketCode() : null;
    }

    /**
     * The URL a ticket's QR and the confirmation email carry: the trip's org's own site when it has a subdomain,
     * else the registration-mail base URL (the chat-invite rule, {@code SiteUrls.baseUrlForTrip}). Blank without a
     * code.
     */
    public String ticketUrl(final Trip trip, final String code) {
        return SiteUrls.ticketUrl(trip, code, new ConfigCommands());
    }

    /** {@link #ticketUrl} for one person's registration, minting the ticket if it must: the focus panel's link. */
    public String ticketUrlFor(final String tripId, final String personId) {
        return ticketUrl(knownTrip(tripId), ticketFor(tripId, personId));
    }

    /** The scheme and host of {@link #ticketUrl}: the one other origin the camera scanner accepts. */
    public String ticketOrigin(final String tripId) {
        final Trip trip = knownTrip(tripId);
        return trip == null ? "" : SiteUrls.baseUrlForTrip(trip, KnownSettings.REG_MAIL_BASE_URL, new ConfigCommands());
    }

    /** {@code content} as an inline SVG QR code (never {@code p:barcode}: see {@link TicketQr}). */
    public String qrSvg(final String content) {
        return TicketQr.svg(content);
    }

    // ------------------------------------------------------------------ check-in

    /** How a check-in or clear went. The REST edge maps these to statuses; the page to growls. */
    public enum Outcome {
        CHECKED_IN, ALREADY_CHECKED_IN, NOT_CONFIRMED, CLEARED, NOT_CHECKED_IN, NOT_FOUND, NOT_ALLOWED, STORE_FAILED
    }

    /** An outcome and the registration as it now stands (null when there is none or the caller was refused). */
    public record Result(Outcome outcome, Registration registration) {
    }

    /**
     * Checks one person in: refused unless the caller may check in here and the registration (read fresh) is
     * CONFIRMED; an existing stamp is never overwritten (a second scan answers ALREADY_CHECKED_IN with the first
     * stamp). The stamp carries the second it happened and who did it, and is audited as a registration event.
     */
    public Result checkInOutcome(final String tripId, final String personId) {
        final Person.Id subject = idOf(personId);
        if (subject == null || !canCheckIn(tripId)) {
            return new Result(Outcome.NOT_ALLOWED, null);
        }
        final Registration reg = fresh(tripId, subject);
        if (reg == null || reg.getStatus() == Registration.Status.NOT_REGISTERED) {
            return new Result(Outcome.NOT_FOUND, reg);
        }
        if (reg.getStatus() != Registration.Status.CONFIRMED) {
            return new Result(Outcome.NOT_CONFIRMED, reg);
        }
        if (reg.isCheckedIn()) {
            return new Result(Outcome.ALREADY_CHECKED_IN, reg);
        }
        final Registration stamped = RegistrationCommands.withTicket(reg)
                .withCheckedInAt(clock.get().withNano(0))
                .withCheckedInBy(caller().personId());
        if (!save(stamped)) {
            return new Result(Outcome.STORE_FAILED, reg);
        }
        new AuditCommands().checkedIn(person(subject), knownTrip(tripId), caller().auditActor());
        return new Result(Outcome.CHECKED_IN, stamped);
    }

    /** {@link #checkInOutcome} for the page: growls what happened and answers whether anything changed. */
    public boolean checkIn(final String tripId, final String personId) {
        final Result result = checkInOutcome(tripId, personId);
        final String name = result.registration() == null ? "" : displayName(person(result.registration().getUserId()));
        switch (result.outcome()) {
            case CHECKED_IN -> PageFeedback.info("Checked in: " + name);
            case ALREADY_CHECKED_IN -> PageFeedback.warn(name + " was already checked in "
                    + stampText(result.registration()) + ".");
            case NOT_CONFIRMED -> PageFeedback.warn(name + " is not confirmed ("
                    + result.registration().getStatus().getDescription() + "): see the registration desk.");
            case NOT_FOUND -> PageFeedback.warn("That person is not registered for this event.");
            case NOT_ALLOWED -> PageFeedback.error("You may not check people in for this event.");
            default -> PageFeedback.failure("The check-in could not be saved; try again.");
        }
        return result.outcome() == Outcome.CHECKED_IN;
    }

    /** Undoes a check-in (the wrong person was scanned): the trip's managers and site admins only. */
    public Result clearCheckInOutcome(final String tripId, final String personId) {
        final Person.Id subject = idOf(personId);
        if (subject == null || !canClearCheckIn(tripId)) {
            return new Result(Outcome.NOT_ALLOWED, null);
        }
        final Registration reg = fresh(tripId, subject);
        if (reg == null) {
            return new Result(Outcome.NOT_FOUND, null);
        }
        if (!reg.isCheckedIn()) {
            return new Result(Outcome.NOT_CHECKED_IN, reg);
        }
        final Registration cleared = reg.withCheckedInAt(null).withCheckedInBy(null);
        if (!save(cleared)) {
            return new Result(Outcome.STORE_FAILED, reg);
        }
        new AuditCommands().checkInCleared(person(subject), knownTrip(tripId), caller().auditActor());
        return new Result(Outcome.CLEARED, cleared);
    }

    /** {@link #clearCheckInOutcome} for the page. */
    public boolean clearCheckIn(final String tripId, final String personId) {
        final Outcome outcome = clearCheckInOutcome(tripId, personId).outcome();
        switch (outcome) {
            case CLEARED -> PageFeedback.info("Check-in cleared.");
            case NOT_CHECKED_IN -> PageFeedback.warn("That person is not checked in.");
            case NOT_ALLOWED -> PageFeedback.error("Only the event's managers may clear a check-in.");
            case NOT_FOUND -> PageFeedback.warn("That person is not registered for this event.");
            default -> PageFeedback.failure("The check-in could not be cleared; try again.");
        }
        return outcome == Outcome.CLEARED;
    }

    /** "at 9:14 AM by Ann Lee" for a stamp, "" without one. */
    public String stampText(final Registration reg) {
        if (reg == null || reg.getCheckedInAt() == null) {
            return "";
        }
        final String when = "at " + reg.getCheckedInAt().format(DateTimeFormatter.ofPattern("MMM d h:mm a",
                Locale.US));
        return reg.getCheckedInBy() == null ? when : when + " by " + displayName(person(reg.getCheckedInBy()));
    }

    // ------------------------------------------------------------------ the focus panel and rows

    /**
     * One person's registration on this trip for the staff focus panel, read fresh once per request (the panel reads
     * it a dozen times; a check-in in the same request forgets it, so the render shows the new stamp); null when
     * refused or absent.
     */
    public Registration focus(final String tripId, final String personId) {
        final Person.Id subject = idOf(personId);
        if (subject == null || !canCheckIn(tripId)) {
            return null;
        }
        return memo("focus:" + tripId + ":" + subject.getValue(), () -> fresh(tripId, subject));
    }

    /** The person behind a scanned ticket on THIS trip, as an id, or null (no such ticket here, or not staff). */
    public String personForTicket(final String tripId, final String code) {
        final Registration reg = canCheckIn(tripId) ? byTicket(tripId, code) : null;
        return reg == null ? null : reg.getUserId().getValue();
    }

    /**
     * Where the ticket page sends this viewer, or null to stay: staff go to the check-in controls. Decided here so the
     * page's script is one null test (the jsft parser is not trusted with a method call inside an {@code if}).
     */
    public String ticketRedirect(final String tripId, final String code) {
        return ticketView(tripId, code).level() == Level.STAFF ? checkinUrl(tripId, code) : null;
    }

    /** Where the ticket page sends staff: the check-in controls, the code normalized (never the raw parameter). */
    public String checkinUrl(final String tripId, final String code) {
        final String normalized = TicketCodes.normalize(code);
        return "/admin/checkin.jsf?trip=" + tripId + (normalized == null ? "" : "&t=" + normalized);
    }

    /** {@link #registrationType} for EL, which cannot reach a static method. */
    public String typeOf(final Trip trip, final Registration reg) {
        return registrationType(trip, reg);
    }

    /**
     * The answers check-in staff see in the focus panel: the shown PICK questions (admission, add-ons, sizes, yes/no),
     * never free text, where people put medical and dietary notes that staff at a door have no need of.
     */
    public List<Answer> staffAnswers(final Trip trip, final Registration reg) {
        if (trip == null || reg == null) {
            return List.of();
        }
        final List<Answer> answers = new ArrayList<>();
        for (final RegistrationOption question : typedQuestions(trip)) {
            final String text = Boolean.TRUE.equals(question.getShow())
                    ? question.displayOf(reg.getOptions().get(question.getKey())) : null;
            if (text != null) {
                answers.add(new Answer(question.getShortDesc(), text));
            }
        }
        return answers;
    }

    /** "Full name" for EL. */
    public String nameOf(final String personId) {
        final Person.Id id = idOf(personId);
        return id == null ? "" : displayName(person(id));
    }

    /**
     * What a registration counts as: its stored {@code registrationType} (checkout writes it), else the label of its
     * answer to the trip's type question ({@link RegistrationOption#typeQuestion}); null for neither.
     */
    public static String registrationType(final Trip trip, final Registration reg) {
        if (reg == null) {
            return null;
        }
        if (reg.getRegistrationType() != null && !reg.getRegistrationType().isBlank()) {
            return reg.getRegistrationType();
        }
        final RegistrationOption question = trip == null ? null : RegistrationOption.typeQuestion(trip.getRegOptions());
        return question == null ? null : question.displayOf(reg.getOptions().get(question.getKey()));
    }

    /**
     * The days of the event a registration attends: its type answer's choice {@code days}, or every day of the trip
     * when the choice names none, there is no type question, or it is unanswered.
     */
    public static List<LocalDate> daysOf(final Trip trip, final Registration reg) {
        final RegistrationOption question = RegistrationOption.typeQuestion(trip.getRegOptions());
        final OptionChoice choice = (question == null || reg == null) ? null
                : question.choice(reg.getOptions().get(question.getKey()));
        final List<LocalDate> days = new ArrayList<>();
        if (choice != null) {
            for (final String day : choice.getDays()) {
                final LocalDate parsed = parseDate(day);
                if (parsed != null) {
                    days.add(parsed);
                }
            }
        }
        return days.isEmpty() ? tripDays(trip) : days;
    }

    private static List<LocalDate> tripDays(final Trip trip) {
        final List<LocalDate> days = new ArrayList<>();
        if (trip.getStartDate() == null) {
            return days;
        }
        final LocalDate last = trip.getEndDate() == null ? trip.getStartDate().toLocalDate()
                : trip.getEndDate().toLocalDate();
        for (LocalDate day = trip.getStartDate().toLocalDate(); !day.isAfter(last); day = day.plusDays(1)) {
            days.add(day);
        }
        return days;
    }

    private RegistrationCommands.RegRow rowOf(final Trip trip, final Registration reg) {
        return new RegistrationCommands.RegRow(reg.getUserId().getValue(), reg.getCreated(),
                reg.getStatus().getDescription(), reg.getParty(), sortName(person(reg.getUserId())),
                registrationType(trip, reg), reg.getCheckedInAt());
    }

    /** The CONFIRMED people not yet checked in, by name: the counts panel's list. Empty unless counts are visible. */
    public List<RegistrationCommands.RegRow> notCheckedIn(final String tripId) {
        if (!canViewCounts(tripId)) {
            return List.of();
        }
        final Trip trip = knownTrip(tripId);
        final List<RegistrationCommands.RegRow> rows = new ArrayList<>();
        for (final Registration reg : registrations(tripId)) {
            if (reg.getStatus() == Registration.Status.CONFIRMED && !reg.isCheckedIn()) {
                rows.add(rowOf(trip, reg));
            }
        }
        rows.sort(BY_NAME);
        return rows;
    }

    // ------------------------------------------------------------------ counts

    /** One line of a breakdown: how many are expected and how many of those are checked in. */
    public record Bucket(String label, int total, int checkedIn) {
    }

    /**
     * The check-in picture of a trip: registered (CONFIRMED + PENDING), confirmed, pending, checked in, and the
     * confirmed people bucketed by registration type and by day.
     */
    public record Counts(int registered, int confirmed, int pending, int checkedIn, List<Bucket> byType,
            List<Bucket> byDay) {
        /** "37 of 120 checked in". */
        public String headline() {
            return checkedIn + " of " + confirmed + " checked in";
        }
    }

    /** The counts, or null when the caller may not see them. Memoized per request (the page reads it repeatedly). */
    public Counts counts(final String tripId) {
        if (!canViewCounts(tripId)) {
            return null;
        }
        return memo("counts:" + tripId, () -> countsOf(knownTrip(tripId), registrations(tripId)));
    }

    static Counts countsOf(final Trip trip, final List<Registration> regs) {
        int confirmed = 0;
        int pending = 0;
        int checkedIn = 0;
        final Map<String, int[]> types = seededTypes(trip);
        final Map<LocalDate, int[]> days = new TreeMap<>();
        for (final Registration reg : regs) {
            if (reg.getStatus() == Registration.Status.PENDING) {
                pending++;
            }
            if (reg.getStatus() != Registration.Status.CONFIRMED) {
                continue;
            }
            confirmed++;
            final int in = reg.isCheckedIn() ? 1 : 0;
            checkedIn += in;
            final String type = registrationType(trip, reg);
            tally(types.computeIfAbsent(type == null ? NO_TYPE : type, key -> new int[2]), in);
            for (final LocalDate day : daysOf(trip, reg)) {
                tally(days.computeIfAbsent(day, key -> new int[2]), in);
            }
        }
        final List<Bucket> byType = new ArrayList<>();
        types.forEach((label, tally) -> addIfAny(byType, label, tally));
        final List<Bucket> byDay = new ArrayList<>();
        days.forEach((day, tally) -> addIfAny(byDay, day.format(DAY_LABEL), tally));
        return new Counts(confirmed + pending, confirmed, pending, checkedIn, byType, byDay);
    }

    /** The type question's choices first, in the editor's order, so the breakdown reads like the price list. */
    private static Map<String, int[]> seededTypes(final Trip trip) {
        final Map<String, int[]> types = new LinkedHashMap<>();
        final RegistrationOption question = RegistrationOption.typeQuestion(trip.getRegOptions());
        if (question != null) {
            question.getChoices().forEach(choice -> types.put(choice.getLabel(), new int[2]));
        }
        return types;
    }

    private static void tally(final int[] counts, final int checkedIn) {
        counts[0]++;
        counts[1] += checkedIn;
    }

    private static void addIfAny(final List<Bucket> buckets, final String label, final int[] tally) {
        if (tally[0] > 0) {
            buckets.add(new Bucket(label, tally[0], tally[1]));
        }
    }

    // ------------------------------------------------------------------ money

    /** One person's money on ONE trip, in cents: paid (payments), billed (everything else), and still due. */
    public record TripBalance(long paidCents, long billedCents) {
        public long balanceCents() {
            return paidCents - billedCents;
        }

        public long dueCents() {
            return Math.max(0, billedCents - paidCents);
        }

        public String paid() {
            return MoneyMath.formatCents(paidCents);
        }

        public String due() {
            return MoneyMath.formatCents(dueCents());
        }
    }

    /**
     * This person's balance on THIS trip: only their ledger rows bound TRANSACTION -> TRIP to it (what the payment
     * recorder and the bill writers bind), never their whole ledger ({@code getBalance}), which mixes every trip and
     * donation they ever had: the mir2026 "Paid" column summed exactly that. Null for a caller who is neither staff
     * here, a finance viewer, nor the person (or their family manager).
     */
    public TripBalance tripBalance(final String tripId, final String personId) {
        final Person.Id subject = idOf(personId);
        if (subject == null || !(canCheckIn(tripId) || canSeeMoney(tripId) || mayActFor(subject))) {
            return null;
        }
        return balanceOf(tripId, subject);
    }

    static TripBalance balanceOf(final String tripId, final Person.Id personId) {
        final TransactionsCommands txs = new TransactionsCommands();
        long paid = 0;
        long billed = 0;
        for (final Transaction tx : DAO.getInstance().getTransactions(personId, Cached.NO)) {
            final Float amount = txs.getUserAmount(tx);
            if (amount != null && boundTo(tripId, personId, tx)) {
                final long cents = Math.round(amount * 100d);
                if (tx.isPayment()) {
                    paid += cents;
                } else {
                    billed -= cents;
                }
            }
        }
        return new TripBalance(paid, billed);
    }

    private static boolean boundTo(final String tripId, final Person.Id personId, final Transaction tx) {
        final String key = new BindingCommands().key(personId.getValue(), tx.getTxId());
        return DAO.getInstance().getBindings(key, BindingType.TRANSACTION, BindingType.TRIP, Cached.YES)
                .contains(tripId);
    }

    /** The trip's money among its CONFIRMED people: totals, and how many are paid up vs still owe. */
    public record Money(long paidCents, long dueCents, int paidUp, int owing) {
        public String paid() {
            return MoneyMath.formatCents(paidCents);
        }

        public String due() {
            return MoneyMath.formatCents(dueCents);
        }
    }

    /** The money row, or null unless the caller sees finances. One ledger read per confirmed person: not polled. */
    public Money money(final String tripId) {
        if (!canSeeMoney(tripId)) {
            return null;
        }
        return memo("money:" + tripId, () -> moneyOf(tripId));
    }

    private Money moneyOf(final String tripId) {
        long paid = 0;
        long due = 0;
        int paidUp = 0;
        int owing = 0;
        for (final Registration reg : registrations(tripId)) {
            if (reg.getStatus() == Registration.Status.CONFIRMED) {
                final TripBalance balance = balanceOf(tripId, reg.getUserId());
                paid += balance.paidCents();
                due += balance.dueCents();
                if (balance.dueCents() > 0) {
                    owing++;
                } else {
                    paidUp++;
                }
            }
        }
        return new Money(paid, due, paidUp, owing);
    }

    // ------------------------------------------------------------------ the ticket page

    /** How much of a ticket the viewer may see. */
    public enum Level {
        /** No such ticket (or a cancelled one, to anyone but its owner). */
        INVALID,
        /** Anyone holding the link: event, name, type, the QR and the code. */
        MINIMAL,
        /** The person or their family manager: also status, answers and the trip balance. */
        OWNER,
        /** Someone who checks people in here: the page sends them to the check-in controls. */
        STAFF
    }

    /** One answer as a person reads it. */
    public record Answer(String question, String text) {
    }

    /**
     * Everything the ticket page shows, resolved here so the page branches on {@link #level} alone. Never an email,
     * a cell number or a person id: the link is a bearer secret that gets forwarded.
     */
    public record TicketView(Level level, String tripTitle, String dates, String name, String registrationType,
            String qrSvg, String groupedCode, String status, boolean checkedIn, List<Answer> answers,
            TripBalance balance) {
        static TicketView invalid() {
            return new TicketView(Level.INVALID, null, null, null, null, null, null, null, false, List.of(), null);
        }
    }

    /** The ticket page's view of {@code ?trip=&t=}; INVALID for an unknown trip or ticket. */
    public TicketView ticketView(final String tripId, final String code) {
        final Trip trip = knownTrip(tripId);
        final Registration reg = trip == null ? null : byTicket(tripId, code);
        if (reg == null) {
            return TicketView.invalid();
        }
        if (canCheckIn(tripId)) {
            return viewOf(Level.STAFF, trip, reg);
        }
        if (mayActFor(reg.getUserId())) {
            return viewOf(Level.OWNER, trip, reg);
        }
        return reg.getStatus() == Registration.Status.CONFIRMED ? viewOf(Level.MINIMAL, trip, reg)
                : TicketView.invalid();
    }

    private TicketView viewOf(final Level level, final Trip trip, final Registration reg) {
        final boolean owner = level != Level.MINIMAL;
        return new TicketView(level, trip.getTitle(), datesOf(trip), displayName(person(reg.getUserId())),
                registrationType(trip, reg), TicketQr.svg(ticketUrl(trip, reg.getTicketCode())),
                TicketCodes.grouped(reg.getTicketCode()), owner ? reg.getStatus().getDescription() : null,
                owner && reg.isCheckedIn(), owner ? answersOf(trip, reg) : List.of(),
                owner ? balanceOf(trip.getId(), reg.getUserId()) : null);
    }

    /** The shown questions' answers, by label, unanswered ones left out. */
    public static List<Answer> answersOf(final Trip trip, final Registration reg) {
        final List<Answer> answers = new ArrayList<>();
        for (final RegistrationOption question : trip.getRegOptions()) {
            final String text = Boolean.TRUE.equals(question.getShow())
                    ? question.displayOf(reg.getOptions().get(question.getKey())) : null;
            if (text != null) {
                answers.add(new Answer(question.getShortDesc(), text));
            }
        }
        return answers;
    }

    private static String datesOf(final Trip trip) {
        if (trip.getStartDate() == null) {
            return "";
        }
        final DateTimeFormatter format = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);
        final String start = trip.getStartDate().format(format);
        final String end = trip.getEndDate() == null ? start : trip.getEndDate().format(format);
        return start.equals(end) ? start : start + " - " + end;
    }

    // ------------------------------------------------------------------ batch badges

    /** One printed badge: the names, the type and the ticket's QR. */
    public record Badge(String personId, String preferredName, String last, String registrationType, String qrSvg) {
    }

    /**
     * The person ids the batch badge page prints, frozen into its view so "Mark these N as printed" stamps exactly
     * what was on the sheet: CONFIRMED registrations, by last then first name, optionally only those registered
     * after {@code after} (an ISO date or date-time; the mir2026 badge cutoff) and only those never printed.
     */
    public List<String> badgeIds(final String tripId, final String after, final boolean unprinted) {
        if (!canPrintBadges(tripId)) {
            return new ArrayList<>();
        }
        final LocalDateTime cutoff = parseCutoff(after);
        final List<Registration> picked = new ArrayList<>();
        for (final Registration reg : registrations(tripId)) {
            if (reg.getStatus() == Registration.Status.CONFIRMED
                    && (cutoff == null || reg.getCreated().isAfter(cutoff))
                    && (!unprinted || reg.getBadgePrintedAt() == null)) {
                picked.add(reg);
            }
        }
        final List<String> ids = new ArrayList<>();
        picked.stream()
                .map(reg -> person(reg.getUserId()))
                .sorted(Comparator.comparing(CheckinCommands::sortName, String.CASE_INSENSITIVE_ORDER))
                .forEach(found -> ids.add(found.getId().getValue()));
        return ids;
    }

    /** The badges for a frozen id list, resolved per request; a person whose registration vanished is skipped. */
    public List<Badge> badges(final String tripId, final List<String> ids) {
        if (ids == null || !canPrintBadges(tripId)) {
            return List.of();
        }
        final Trip trip = knownTrip(tripId);
        final List<Badge> badges = new ArrayList<>();
        for (final String id : ids) {
            final Registration reg = fresh(tripId, Person.Id.from(id));
            final String code = ensureTicket(reg);
            if (code != null) {
                final Person found = person(reg.getUserId());
                badges.add(new Badge(id, found.getPreferredName(), found.getLast(), registrationType(trip, reg),
                        TicketQr.svg(ticketUrl(trip, code))));
            }
        }
        return badges;
    }

    /** Stamps {@code badgePrintedAt} on each listed registration; answers how many were stamped. */
    public int markPrinted(final String tripId, final List<String> ids) {
        if (ids == null || !canPrintBadges(tripId)) {
            return 0;
        }
        final LocalDateTime now = clock.get().withNano(0);
        int stamped = 0;
        for (final String id : ids) {
            final Registration reg = fresh(tripId, Person.Id.from(id));
            if (reg != null && save(reg.withBadgePrintedAt(now))) {
                stamped++;
            }
        }
        return stamped;
    }

    /** {@link #markPrinted} for the badge page's button: growls how many were stamped. */
    public int markPrintedFromUi(final String tripId, final List<String> ids) {
        final int stamped = markPrinted(tripId, ids);
        PageFeedback.info("Marked " + stamped + (stamped == 1 ? " badge" : " badges") + " as printed.");
        return stamped;
    }

    /** How many CONFIRMED people have never had a badge printed: the dashboard card's number. */
    public int unprintedBadges(final String tripId) {
        if (!canPrintBadges(tripId)) {
            return 0;
        }
        return (int) registrations(tripId).stream()
                .filter(reg -> reg.getStatus() == Registration.Status.CONFIRMED && reg.getBadgePrintedAt() == null)
                .count();
    }

    private static LocalDateTime parseCutoff(final String after) {
        if (after == null || after.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(after.trim());
        } catch (final DateTimeParseException ex) {
            final LocalDate day = parseDate(after);
            return day == null ? null : day.atStartOfDay();
        }
    }

    // ------------------------------------------------------------------ export

    /**
     * The people the trip export lists: the roster, plus (when asked) everyone with a PENDING registration who is not
     * on it yet. A mutable list: the export table filters it in place.
     */
    public List<Person.Id> exportPeople(final Trip trip, final boolean includePending) {
        final List<Person.Id> people = new ArrayList<>(trip.getPeople());
        if (includePending) {
            for (final Registration reg : registrations(trip.getId())) {
                if (reg.getStatus() == Registration.Status.PENDING && !people.contains(reg.getUserId())) {
                    people.add(reg.getUserId());
                }
            }
        }
        return people;
    }

    /**
     * The questions the export offers a column each for: every typed (non-free-text) question. Empty for no trip:
     * the export builds its columns before its gate redirects a signed-out visitor.
     */
    public List<RegistrationOption> typedQuestions(final Trip trip) {
        if (trip == null) {
            return List.of();
        }
        return trip.getRegOptions().stream()
                .filter(q -> q.getType() != RegistrationOption.QuestionType.TEXT)
                .toList();
    }

    /** The map key of a typed question's column in {@link #exportRow}. */
    public static String questionKey(final RegistrationOption question) {
        return "q" + question.getKey();
    }

    /**
     * One person's export row: the cells the export table cannot read straight off the Person. {@code name},
     * {@code tripOpts}, {@code exportOpts} and {@code age} are what the page built in EL before this moved here, byte
     * for byte, so an export with the default columns is unchanged; the rest feed the new optional columns. Strings
     * and numbers only (the page keeps these rows in its view). Empty unless the caller is the trip's viewer.
     */
    public Map<String, Object> exportRow(final Trip trip, final Person.Id personId) {
        final Map<String, Object> row = new HashMap<>();
        if (trip == null || personId == null || !signedInOnKnownTrip(trip.getId())
                || !has(PrivilegeCommands.TRIP_VIEW, trip.getId())) {
            return row;
        }
        final Person found = person(personId);
        final Registration reg = fresh(trip.getId(), personId);
        final Registration answered = reg == null ? new Registration(trip.getId(), personId) : reg;
        row.put("name", (found.getLast() == null ? "" : found.getLast()) + ", " + found.getPreferredName());
        putAnswers(row, trip, answered);
        final OptionalInt age = Ages.ageAt(found.getBirthdate(), LocalDate.now());
        row.put("age", age.isPresent() ? Integer.valueOf(age.getAsInt()) : "");
        final String type = registrationType(trip, answered);
        row.put("regType", type == null ? "" : type);
        putCheckout(row, trip, answered);
        row.put("ticket", answered.getTicketCode() == null ? "" : TicketCodes.grouped(answered.getTicketCode()));
        row.put("checkedIn", answered.getCheckedInAt() == null ? "" : answered.getCheckedInAt().format(STAMP));
        row.put("registered", reg == null ? "" : reg.getCreated().toLocalDate().toString());
        row.put("status", answered.getStatus().getDescription());
        for (final RegistrationOption question : typedQuestions(trip)) {
            final String text = question.displayOf(answered.getOptions().get(question.getKey()));
            row.put(questionKey(question), text == null ? "" : text);
        }
        return row;
    }

    /**
     * The priced-checkout columns (docs/checkout.md): the stored price, the discount code it used and every
     * attestation ticked, as "choice: statement" joined by "; ". Blank for a registration no checkout priced.
     */
    private static void putCheckout(final Map<String, Object> row, final Trip trip, final Registration reg) {
        final org.paulsens.trip.model.PriceBreakdown breakdown = reg.getPriceBreakdown();
        row.put("total", breakdown == null ? "" : MoneyMath.formatCents(breakdown.getTotalCents()));
        final String code = breakdown == null ? null : breakdown.getInputs().getCodeText();
        row.put("code", code == null ? "" : code);
        final List<String> attested = new java.util.ArrayList<>();
        reg.getAttestations().forEach((choiceId, text) -> attested.add(choiceLabel(trip, choiceId) + ": " + text));
        row.put("attestation", String.join("; ", attested));
        // How it was paid (docs/checkout.md, "Offline payments"): the method, reference and date, structured, so a
        // treasurer can total the checks. Read only for a registration a payment registered.
        final org.paulsens.trip.model.Payment payment = reg.getPaymentId() == null ? null
                : DAO.getInstance().getPayment(reg.getPaymentId()).orElse(null);
        final LocalDate received = payment == null ? null : PaymentCommands.paymentReceivedOn(payment);
        row.put("paymentMethod", payment == null ? "" : PaymentCommands.paymentMethodOf(payment));
        row.put("paymentReference", payment == null ? "" : PaymentCommands.paymentReferenceOf(payment));
        row.put("paymentReceived", received == null ? "" : received.toString());
    }

    /** A choice id's label on this trip, or the id when no question offers it any more. */
    private static String choiceLabel(final Trip trip, final String choiceId) {
        return trip.getRegOptions().stream()
                .map(question -> question.choice(choiceId))
                .filter(java.util.Objects::nonNull)
                .map(org.paulsens.trip.model.OptionChoice::getLabel)
                .findFirst()
                .orElse(choiceId);
    }

    /** The Options column: an HTML list for the screen and newline-joined text for the file, as the page built them. */
    private static void putAnswers(final Map<String, Object> row, final Trip trip, final Registration reg) {
        final StringBuilder html = new StringBuilder("<ul>");
        final StringBuilder text = new StringBuilder();
        for (final RegistrationOption opt : trip.getRegOptions()) {
            final String value = opt.getShortDesc() == null || opt.getShortDesc().startsWith("Join") ? null
                    : opt.displayOf(reg.getOptions().get(opt.getKey()));
            if (value != null) {
                final String escaped = com.sun.jsft.util.Util.htmlEscape(value);
                html.append("<li><b>").append(opt.getShortDesc()).append("</b> ").append(escaped).append("</li>");
                text.append(opt.getShortDesc()).append(' ').append(escaped).append('\n');
            }
        }
        row.put("tripOpts", html.append("</ul>").toString());
        row.put("exportOpts", text.isEmpty() ? "" : text.substring(0, text.length() - 1));
    }

    // ------------------------------------------------------------------ helpers

    private boolean signedInOnKnownTrip(final String tripId) {
        return tripId != null && !tripId.isBlank() && caller().isAuthenticated() && knownTrip(tripId) != null;
    }

    private boolean has(final String privilege, final String tripId) {
        return caller().has(privilege, tripId);
    }

    /** Self, or a family member they manage: the family switcher's reach. Site admin is not implied. */
    private boolean mayActFor(final Person.Id subject) {
        final Caller caller = caller();
        if (!caller.isAuthenticated() || subject == null) {
            return false;
        }
        return subject.equals(caller.personId())
                || PersonCommands.getPersonCommands().canAccessUserId(person(caller.personId()), subject);
    }

    /** The trip, or null for an unknown one or one this site does not serve ({@code getTrip} answers blank). */
    private Trip knownTrip(final String tripId) {
        if (tripId == null || tripId.isBlank()) {
            return null;
        }
        return memo("trip:" + tripId, () -> existing(tripId));
    }

    private static Trip existing(final String tripId) {
        final Trip trip = new TripCommands().getTrip(tripId);
        return (trip == null || !tripId.equals(trip.getId())) ? null : trip;
    }

    private List<Registration> registrations(final String tripId) {
        return memo("regs:" + tripId, () -> new RegistrationCommands(callerSource).getRegistrations(tripId));
    }

    private static Registration fresh(final String tripId, final Person.Id personId) {
        try {
            return DAO.getInstance().getRegistration(tripId, personId, Cached.NO).orElse(null);
        } catch (final RuntimeException ex) {
            log.error("Unable to read the registration of {} on {}", personId, tripId, ex);
            return null;
        }
    }

    private boolean save(final Registration reg) {
        try {
            final boolean saved = Boolean.TRUE.equals(DAO.getInstance().saveRegistration(reg));
            // What this request already read about the trip is stale now: the render after an action re-reads.
            forget("regs:" + reg.getTripId());
            forget("counts:" + reg.getTripId());
            forget("money:" + reg.getTripId());
            forget("focus:" + reg.getTripId() + ":" + reg.getUserId().getValue());
            return saved;
        } catch (final IOException | RuntimeException ex) {
            log.error("Unable to save the registration of {} on {}", reg.getUserId(), reg.getTripId(), ex);
            return false;
        }
    }

    private static Person person(final Person.Id id) {
        return DAO.getInstance().getPerson(id, Cached.YES).orElseGet(() -> unknownPerson(id));
    }

    private static Person unknownPerson(final Person.Id id) {
        final Person blank = new Person();
        blank.setId(id);
        return blank;
    }

    /** "Preferred Last", the name a badge and a growl use. */
    public static String displayName(final Person person) {
        final String first = person.getPreferredName() == null ? "" : person.getPreferredName();
        final String last = person.getLast() == null ? "" : person.getLast();
        return (first + " " + last).trim();
    }

    /** "Last, Preferred", the order staff search a list in. */
    private static String sortName(final Person person) {
        final String first = person.getPreferredName() == null ? "" : person.getPreferredName();
        return person.getLast() == null || person.getLast().isBlank() ? first : person.getLast() + ", " + first;
    }

    private static boolean isActive(final Registration reg) {
        return reg.getStatus() == Registration.Status.CONFIRMED || reg.getStatus() == Registration.Status.PENDING;
    }

    private static Person.Id idOf(final String personId) {
        return personId == null || personId.isBlank() ? null : Person.Id.from(personId.trim());
    }

    private static LocalDate parseDate(final String text) {
        try {
            return text == null ? null : LocalDate.parse(text.trim());
        } catch (final DateTimeParseException ex) {
            return null;
        }
    }

    private Caller caller() {
        return memo("caller", callerSource);
    }

    @SuppressWarnings("unchecked")
    private <T> T memo(final String key, final Supplier<T> compute) {
        final Map<String, Object> map = requestMap.get();
        if (map == null) {
            return compute.get();
        }
        final String scoped = MEMO_PREFIX + key;
        if (map.containsKey(scoped)) {
            return (T) map.get(scoped);
        }
        final T value = compute.get();
        map.put(scoped, value);
        return value;
    }

    private void forget(final String key) {
        final Map<String, Object> map = requestMap.get();
        if (map != null) {
            map.remove(MEMO_PREFIX + key);
        }
    }

    private static Map<String, Object> facesRequestMap() {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        return ctx == null ? null : ctx.getExternalContext().getRequestMap();
    }
}
