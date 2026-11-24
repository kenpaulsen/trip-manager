package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.CompositeKey;
import org.paulsens.trip.model.Family;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import org.paulsens.trip.pay.MoneyMath;
import org.paulsens.trip.site.ListingScope;
import org.paulsens.trip.util.PersonNames;

/**
 * The per-organization ledger ({@code #{ledger}}): every balance and statement this bean answers names exactly
 * ONE organization. A person's rows live in one partition whatever organization they are with, and until this
 * bean every balance on the site was "all the rows this host reaches" ({@code TransactionsCommands.getBalance}),
 * which on a shared host mixed organizations: a traveler could not tell whom they owed, and an organizer saw
 * money that was not their organization's ({@code docs/payments.md}, "Balances are per organization").
 *
 * <p><b>Reach is two gates, both asked of every row.</b> The SITE gate is {@code ListingScope.reaches} on the
 * row's organization, the same one {@code TransactionsCommands.getTransactions} applies. The VIEWER gate is new:
 * the subject themself, someone who may act for them ({@code Person.managedUsers}, the family switcher's reach)
 * and a site admin see every organization the site lists; anyone else is staff and sees only the organizations
 * they manage or hold {@code peopleAdmin} in ({@link PeopleReach}) or hold {@code viewFinances} in. Staff never
 * see a row with no organization.
 *
 * <p><b>One share rule.</b> Every figure is a fold of {@code TransactionsCommands.getUserAmount} (the exact
 * Float share, no per-row cent rounding) in partition order, so a person's per-organization balances add up to
 * the org-blind {@code getBalance} for the same reach. Totals are rounded ONCE, where they are shown: the
 * {@code *Cents} accessors.
 *
 * <p><b>Reads.</b> One partition read per subject and no binding reads for the balances; a statement reads the
 * bindings of the chosen organization's rows only; the Trip Contacts figures take ONE trip-side binding read
 * for the whole trip. Everything the bean reads from the store goes through {@link Reads}, which is how the
 * tests count them.
 *
 * <p>The result types are plain records that are deliberately NOT {@code Serializable}: they hold money rows and
 * belong in {@code requestScope} only, and a page that put one in a view would fail at session save instead of
 * snapshotting a balance into the session ({@code docs/page-state-and-identity.md}).
 */
@Slf4j
@Named("ledger")
@ApplicationScoped
public class LedgerCommands {

    /** The pseudo-organization a row with no organization is reported under (it must never vanish). */
    public static final String UNASSIGNED_ORG_ID = "unassigned";

    /** The name the {@link #UNASSIGNED_ORG_ID} pseudo-organization is shown under, in the request's language. */
    static String unassignedOrgName() {
        return Text.get("feedback.ledgerCommands.unassignedOrgName");
    }

    /**
     * The title of a statement's trailing group, rows not bound to a trip (or to one of another org), in the
     * request's language.
     */
    static String unassignedTripTitle() {
        return Text.get("feedback.ledgerCommands.unassignedTripTitle");
    }

    /**
     * Where an account stands with one organization, and the ONE spelling of each state in words: the Balance
     * page's chip, its plain-language line, its family pills and its closing line all read these, so a wording
     * change (or a fifth state) lands on every one of them at once.
     */
    public enum State {
        /** The balance is negative: money is owed to the organization. */
        OWES("feedback.ledgerCommands.stateDue", "due", "feedback.ledgerCommands.line.owes",
                "feedback.ledgerCommands.amountDue", "balOwe"),
        /** The balance is positive: the organization holds a credit. */
        CREDIT("feedback.ledgerCommands.stateCredit", "ok", "feedback.ledgerCommands.line.credit",
                "feedback.ledgerCommands.amountCredit", "balCredit"),
        /** There are rows and they net to zero. */
        PAID_UP("feedback.ledgerCommands.statePaidUp", "ok", "feedback.ledgerCommands.line.paidUp", null, ""),
        /** No rows at all. */
        NO_ACTIVITY("feedback.ledgerCommands.stateNoActivity", "", "feedback.ledgerCommands.line.noActivity",
                null, "");

        /**
         * Who a {@link #line} is about, as the page names it: a stable code, never the English words, because the
         * sentence is one message per state AND subject (a verb agrees with its subject in every language).
         */
        public static final List<String> SUBJECTS = List.of("you", "yourFamily", "theFamily", "person");

        // The words are message KEYS: a constant is built once, and each reader gets their own language.
        private final String labelKey;
        private final String chipStyle;
        /** The plain-language line's keys: this prefix, a dot and one of {@link #SUBJECTS}. */
        private final String lineKey;
        /** The sentence for an amount ("{0} due", "{0} credit"); null for a state that shows no amount. */
        private final String amountKey;
        private final String amountStyle;

        State(final String labelKey, final String chipStyle, final String lineKey, final String amountKey,
                final String amountStyle) {
            this.labelKey = labelKey;
            this.chipStyle = chipStyle;
            this.lineKey = lineKey;
            this.amountKey = amountKey;
            this.amountStyle = amountStyle;
        }

        /** The chip's word: "Due", "Credit", "Paid up", "No activity". */
        public String label() {
            return Text.get(labelKey);
        }

        /** The chip's style class beside {@code balChip}: "due", "ok", or none. */
        public String chipStyle() {
            return chipStyle;
        }

        /**
         * The plain-language line, one whole sentence: "You owe Acme", "Ken has a credit with Acme".
         *
         * @param subject one of {@link #SUBJECTS}; anything else reads as {@code person}
         * @param name    the person's name, used by the {@code person} sentence only
         * @param orgName the organization the balance is with
         */
        public String line(final String subject, final String name, final String orgName) {
            final String who = SUBJECTS.contains(subject) ? subject : "person";
            return Text.get(lineKey + "." + who, name, orgName);
        }

        /** What a family pill says: the amount and its word ("$12.00 due"), or only the chip's word ("Paid up"). */
        public String pillText(final long cents) {
            return amountKey == null ? label() : Text.get(amountKey, MoneyMath.formatCents(Math.abs(cents)));
        }

        /** The printed statement's closing balance: "$12.00 due", "$5.00 credit", or the bare "$0.00". */
        public String closingText(final long cents) {
            final String amount = MoneyMath.formatCents(Math.abs(cents));
            return amountKey == null ? amount : Text.get(amountKey, amount);
        }

        /** The amount's style class: "balOwe" when owed, "balCredit" for a credit, none otherwise. */
        public String amountStyle() {
            return amountStyle;
        }

        static State of(final long cents, final int rows) {
            if (rows == 0) {
                return NO_ACTIVITY;
            }
            return cents < 0 ? OWES : (cents > 0 ? CREDIT : PAID_UP);
        }
    }

    /**
     * One organization's balance for the subject(s). {@code balance} is the exact fold (negative = owed);
     * {@code balanceCents} is that rounded once, for display and for every zero test.
     */
    public record OrgBalance(String orgId, String orgName, boolean unassigned, double balance, long balanceCents,
            int rowCount, State state) {
        /** The balance without its sign, for "owes $X" / "has a credit of $X" wording. */
        public double magnitude() {
            return Math.abs(balanceCents) / 100d;
        }

        /** Whether a notice about this organization is worth showing: the rounded balance is not zero. */
        public boolean nonZero() {
            return balanceCents != 0;
        }
    }

    /**
     * One member's balance with the statement's organization (the family breakdown). {@code name} is the full
     * name, {@code preferredName} the first name the person goes by (a pill's label; blank for nobody known).
     */
    public record PersonBalance(Person.Id personId, String name, String preferredName, double balance,
            long balanceCents, int rowCount, State state) {
        /** The balance without its sign, for "$X due" / "$X credit" wording. */
        public double magnitude() {
            return Math.abs(balanceCents) / 100d;
        }
    }

    /**
     * One ledger row as a statement shows it. {@code amount} is this person's SHARE; {@code running} is the
     * balance within the row's trip group after it, {@code statementRunning} the balance of the whole statement
     * after it in date order. {@code group} marks a Shared or Batch row (edited on the group page, by
     * {@code groupId}); the ids are for links and for commands that resolve the row again at click time.
     * {@code personName} is the row owner's full name, {@code personPreferredName} the first name they go by.
     */
    public record Row(String txId, Person.Id personId, String personName, String personPreferredName,
            String groupId, boolean group,
            boolean shared, LocalDateTime date, String category, String note, String txType, double amount,
            double running, double statementRunning, String tripId, String eventId, String eventTitle) {
        /** Whether the amount counts against the person (a charge) rather than for them. */
        public boolean charge() {
            return amount < 0;
        }
    }

    /**
     * The statement's rows on one trip, in date order. {@code charges} is what the negative rows add up to (as a
     * positive figure), {@code payments} the positive ones, {@code balance} their difference. Sign, not
     * {@code txType}, decides which is which: rows entered by hand before the type existed are all "Payment".
     * {@code assigned} is false for the one trailing group of rows that are on no trip. {@code payableInApp} says
     * whether the trip takes a payment in the app ({@code OrgCommands.effectivePaymentConfig(trip).payable});
     * it is resolved only for a trip with an amount due, and is false for every other group.
     *
     * <p>{@code coveredByCredit} (owner decision 2026-11-20) is true for a group with an amount due on a
     * statement whose ORGANIZATION balance is not owed (zero, or a credit): the traveler overpaid another trip
     * with the same organization, so nothing is owed anybody and the card must not read as a demand. The
     * statement decides it for all its subjects together (a family's statement by the family's balance), and it
     * is always false on the one-trip statement of {@code tripStatement}, which has no organization balance to
     * judge by. It changes no figure: charges, payments and the balance stay this trip's own.
     */
    public record TripGroup(String tripId, String title, LocalDateTime startDate, LocalDateTime endDate,
            boolean assigned, List<Row> rows, double charges, double payments, double balance, long balanceCents,
            boolean payableInApp, boolean coveredByCredit) {

        /** What the Balance page calls the one group of rows on no trip (its card's {@code data-trip}). */
        public static final String UNASSIGNED_KEY = "unassigned";

        /**
         * How much of this trip's charges has been paid, 0 to 100, for the card's progress bar: rounded DOWN, so
         * the bar is full only when the charges are covered, and full when there is nothing to pay. The page
         * used to work this out in EL, twice.
         */
        public int paidPercent() {
            if (charges <= 0d || payments >= charges) {
                return 100;
            }
            return (int) Math.max(0L, Math.floor(payments * 100d / charges));
        }

        /**
         * Whether the card offers "Make a payment": the trip takes one in the app, has an amount due, and that
         * amount is really owed (not {@code coveredByCredit}). Paying a trip whose shortfall a credit elsewhere
         * already covers would only grow the credit. Who may pay is the page's own question.
         */
        public boolean payOffered() {
            return payableInApp && !coveredByCredit;
        }

        /** The balance without its sign, for "$X due" / "$X credit" wording. */
        public double magnitude() {
            return Math.abs(balanceCents) / 100d;
        }

        /** The card's key on the page: the trip's id, or {@link #UNASSIGNED_KEY} for the rows on no trip. */
        public String key() {
            return assigned ? tripId : UNASSIGNED_KEY;
        }
    }

    /**
     * One organization's statement for the subject(s): the header (who is owed, how much, {@link State}), the
     * rows grouped by trip (newest trip first, unassigned rows last) and, for several subjects, each one's
     * balance. An organization the viewer may not see, or none at all, is the EMPTY statement: no name, no rows.
     */
    public record Statement(String orgId, String orgName, boolean unassigned, State state, double balance,
            long balanceCents, double charges, double payments, int rowCount, List<TripGroup> trips,
            List<PersonBalance> people) {
        static Statement none() {
            return new Statement(null, "", false, State.NO_ACTIVITY, 0d, 0L, 0d, 0d, 0, List.of(), List.of());
        }

        /** The balance without its sign, for "owes $X" / "has a credit of $X" wording. */
        public double magnitude() {
            return Math.abs(balanceCents) / 100d;
        }

        /** Whether there is a per-person breakdown to show (several subjects). */
        public boolean hasBreakdown() {
            return people.size() > 1;
        }

        /**
         * Whether the amount owed is less than the trips' amounts due add up to, because another trip holds a
         * credit: the organization is owed, one group has a credit and another an amount due. The organization
         * card then says so in a line under its amount, since the due cards below it sum to more than it shows.
         * (When the organization is NOT owed, the due cards say it themselves: {@code coveredByCredit}.)
         */
        public boolean hasOffsettingCredit() {
            return balanceCents < 0 && trips.stream().anyMatch(group -> group.balanceCents() > 0L)
                    && trips.stream().anyMatch(group -> group.balanceCents() < 0L);
        }

        /**
         * The {@link TripGroup#key} of the card whose rows are open when the page arrives, or blank for none:
         * the trip the visitor came from ({@code pickedTripId}, the page's {@code ?trip=}) when it is on this
         * statement, else the first group that has a balance, an amount due that a credit elsewhere covers not
         * counting as one (nothing there asks for the visitor's attention). Decided here so the page renders the
         * card open instead of opening it from a script after the fact.
         */
        public String openKey(final String pickedTripId) {
            return trips.stream()
                    .filter(group -> group.assigned() && group.tripId().equals(pickedTripId))
                    .findFirst()
                    .or(() -> trips.stream().filter(Statement::opensByBalance).findFirst())
                    .map(TripGroup::key)
                    .orElse("");
        }

        private static boolean opensByBalance(final TripGroup group) {
            return group.balanceCents() != 0L && !group.coveredByCredit();
        }
    }

    /**
     * The family pills of the Balance page: the whole family's balance with one organization and each member's
     * own, whoever of them the statement beside it is for. {@code people} is empty unless there are several
     * members the viewer may see.
     */
    public record FamilyBalances(String orgId, State state, double balance, long balanceCents,
            List<PersonBalance> people) {
        static FamilyBalances none() {
            return new FamilyBalances(null, State.NO_ACTIVITY, 0d, 0L, List.of());
        }

        static FamilyBalances of(final Statement statement) {
            return new FamilyBalances(statement.orgId(), statement.state(), statement.balance(),
                    statement.balanceCents(), statement.people());
        }

        /** The balance without its sign, for "$X due" / "$X credit" wording. */
        public double magnitude() {
            return Math.abs(balanceCents) / 100d;
        }

        /** Whether there are pills to show (several members). */
        public boolean hasBreakdown() {
            return people.size() > 1;
        }
    }

    /**
     * Everything the Balance page shows, from one partition read per subject: every reachable organization's
     * balance, the organization chosen ({@code orgId}: the one asked for when it is reachable, else the default),
     * its statement, the OTHER organizations that have a nonzero balance (the page's notice), and the family
     * pills' figures ({@code family}: the statement's own breakdown from {@link #overview}, the whole family's
     * from {@link #familyOverview}).
     */
    public record Overview(List<OrgBalance> orgs, String orgId, Statement statement, List<OrgBalance> others,
            FamilyBalances family) {
        /** Whether there is more than one organization to choose between. */
        public boolean hasChoice() {
            return orgs.size() > 1;
        }
    }

    /**
     * One traveler's two figures on the Trip Contacts page: the balance of their rows on this trip's books, and
     * their balance with the trip's organization. {@code visible} is false (and both figures zero) for a row
     * the viewer may not see money on. {@code differ} says whether the organization figure adds anything.
     */
    public record TripOrgBalance(Person.Id personId, boolean visible, double tripBalance, long tripCents,
            double orgBalance, long orgCents) {
        static TripOrgBalance hidden(final Person.Id personId) {
            return new TripOrgBalance(personId, false, 0d, 0L, 0d, 0L);
        }

        public boolean differ() {
            return tripCents != orgCents;
        }
    }

    /** {@link TripOrgBalance} for everyone on a trip's roster; {@link #forPerson} never answers null. */
    public record TripBalances(String tripId, String orgId, String orgName, Map<Person.Id, TripOrgBalance> byPerson) {
        static TripBalances none(final String tripId) {
            return new TripBalances(tripId, null, "", Map.of());
        }

        public TripOrgBalance forPerson(final Person.Id personId) {
            final TripOrgBalance found = personId == null ? null : byPerson.get(personId);
            return found == null ? TripOrgBalance.hidden(personId) : found;
        }
    }

    /** The store reads the ledger does, behind one seam so a test can count them. */
    interface Reads {
        /** A person's live rows: the whole partition, every organization. */
        List<Transaction> partition(Person.Id personId);

        List<String> bindings(String id, BindingType type, BindingType destType);

        List<Registration> registrations(String tripId);

        /** The trips this site lists that have not ended: where a registration without a roster seat can be. */
        List<Trip> activeTrips();
    }

    /** The real reads: the ledger partition exactly as {@code getTransactions} reads it, the rest near-cached. */
    private static final class StoreReads implements Reads {
        @Override
        public List<Transaction> partition(final Person.Id personId) {
            return DAO.getInstance().getTransactions(personId, Cached.NO);
        }

        @Override
        public List<String> bindings(final String id, final BindingType type, final BindingType destType) {
            return DAO.getInstance().getBindings(id, type, destType, Cached.YES);
        }

        @Override
        public List<Registration> registrations(final String tripId) {
            return DAO.getInstance().getRegistrations(tripId, Cached.YES);
        }

        @Override
        public List<Trip> activeTrips() {
            return new TripCommands().getActiveTrips(0);
        }
    }

    private final Supplier<Caller> callerSource;
    private final OrgCommands orgs;
    private final TripCommands trips;
    private final Reads reads;
    private final Supplier<LocalDate> today;

    public LedgerCommands() {
        this(Caller::current);
    }

    /** As this caller: the REST edge's way in ({@code Caller.current()} needs a Faces request), and a test's. */
    public LedgerCommands(final Supplier<Caller> callerSource) {
        this(callerSource, new StoreReads(), LocalDate::now);
    }

    /** Test seam: the store reads (to count them) and the date "upcoming" is judged against. */
    LedgerCommands(final Supplier<Caller> callerSource, final Reads reads, final Supplier<LocalDate> today) {
        this.callerSource = callerSource;
        this.orgs = new OrgCommands(callerSource);
        this.trips = new TripCommands(() -> orgs, callerSource);
        this.reads = reads;
        this.today = today;
    }

    // ------------------------------------------------------------------ the page-facing reads

    /**
     * The reachable organizations the subject(s) have live rows with, or a trip in, each with its balance:
     * sorted by name, the unassigned pseudo-organization last. One partition read per subject, no binding reads.
     */
    public List<OrgBalance> orgBalances(final Collection<Person.Id> subjects) {
        return balancesOf(snapshot(subjects));
    }

    private OrgBalance balanceWith(final Snapshot snap, final String orgId) {
        return balancesOf(snap).stream().filter(entry -> entry.orgId().equals(orgId)).findFirst()
                .orElseGet(() -> new OrgBalance(orgId, snap.viewer.nameOfReachable(snap.subjects, orgId), false,
                        0d, 0L, 0, State.NO_ACTIVITY));
    }

    /**
     * The payment page's figures: each row's own balance with the trip's organization, and the family line's.
     * {@code family} is null unless the line is shown (the payer manages the family).
     */
    public record PayBalances(Map<String, OrgBalance> byPerson, OrgBalance family) {
        /** One row's figure (never null: an id this call was not given has no activity). */
        public OrgBalance forPerson(final String personId) {
            return byPerson.getOrDefault(personId, new OrgBalance(null, null, false, 0d, 0L, 0, State.NO_ACTIVITY));
        }
    }

    /**
     * The balance with ONE organization (the entry {@link #orgBalances} would list, or a zero
     * {@link State#NO_ACTIVITY} one) for every row of the payment page and for its family line, in ONE call: each
     * person's partition is read once however many figures it is part of. The page used to ask per row and again
     * for the family, each call reading its partitions afresh (a manager of five read ten partitions for
     * six figures, on every re-render). The family line only {@code withFamily} (the payer manages
     * {@code family}); else {@code family()} is null.
     */
    public PayBalances payBalances(final Collection<String> rowIds, final Family family, final boolean withFamily,
            final String orgId) {
        final Collection<Person.Id> familyIds = withFamily && family != null ? family.getMemberIds() : null;
        final Map<Person.Id, List<Transaction>> partitions = new HashMap<>();
        final Map<String, OrgBalance> byPerson = new LinkedHashMap<>();
        for (final String id : rowIds == null ? List.<String>of() : rowIds) {
            byPerson.put(id, balanceWith(new Snapshot(List.of(Person.Id.from(id)), partitions, false), orgId));
        }
        final OrgBalance familyLine = familyIds == null || familyIds.isEmpty() ? null
                : balanceWith(new Snapshot(familyIds, partitions, false), orgId);
        return new PayBalances(byPerson, familyLine);
    }

    /**
     * The organization the Balance page opens on when none is asked for, among the REACHABLE ones only: the
     * organization of the next upcoming trip a subject is on (roster, or a registration that is not Not
     * Registered), else of their most recent past trip, else of their most recent row; null when there is none
     * ("no account activity yet").
     */
    public String defaultOrg(final Collection<Person.Id> subjects) {
        return defaultOrgOf(snapshot(subjects));
    }

    /** The other reachable organizations that have a NONZERO balance: the Balance page's notice. */
    public List<OrgBalance> otherOrgBalances(final Collection<Person.Id> subjects, final String orgId) {
        return othersOf(balancesOf(snapshot(subjects)), orgId);
    }

    /**
     * One organization's statement for the subject(s). Binding reads only for this organization's rows. An
     * organization nobody here may see (or null) answers {@link Statement#none()}.
     */
    public Statement statement(final Collection<Person.Id> subjects, final String orgId) {
        return statementOf(snapshot(subjects), orgId);
    }

    /**
     * The whole Balance page in one call: see {@link Overview}. {@code requestedOrgId} is the page's
     * {@code ?org=}; one the viewer cannot reach here (or blank) falls back to {@link #defaultOrg}.
     */
    public Overview overview(final Collection<Person.Id> subjects, final String requestedOrgId) {
        return overviewOf(snapshot(subjects), requestedOrgId, null);
    }

    /**
     * {@link #overview} for a family manager's Balance page, in ONE call: the statement is the {@code subjects}'
     * (one member, or all of them), while {@code family()} carries EVERY member's own balance with the chosen
     * organization, so the pills read the same whichever member is selected. With no organization chosen for the
     * subjects (a member with no activity), the pills fall back to the family's own default organization. Each
     * member's partition is read once, and the pills cost no binding reads. A member the viewer may not see
     * simply shows no activity: reach is asked per member, exactly as for a subject.
     */
    public Overview familyOverview(final Collection<Person.Id> subjects, final Collection<Person.Id> members,
            final String requestedOrgId) {
        final Map<Person.Id, List<Transaction>> partitions = new HashMap<>();
        final Snapshot own = new Snapshot(subjects, partitions, false);
        final Snapshot family = new Snapshot(members, partitions, false);
        return overviewOf(own, requestedOrgId, own.subjects.equals(family.subjects) ? null : family);
    }

    private Overview overviewOf(final Snapshot snap, final String requestedOrgId, final Snapshot family) {
        final List<OrgBalance> balances = balancesOf(snap);
        final String orgId = chosenOrg(snap, balances, requestedOrgId);
        final Statement statement = statementOf(snap, orgId);
        return new Overview(balances, orgId, statement, othersOf(balances, orgId),
                family == null ? FamilyBalances.of(statement) : familyOf(family, orgId));
    }

    /** The organization asked for when the subject(s) have it, else the default. */
    private String chosenOrg(final Snapshot snap, final List<OrgBalance> balances, final String requestedOrgId) {
        final boolean asked = balances.stream().anyMatch(entry -> entry.orgId().equals(requestedOrgId));
        return asked ? requestedOrgId : defaultOrgOf(snap);
    }

    /** The family's total and per-member balances with the chosen organization (or the family's default one). */
    private FamilyBalances familyOf(final Snapshot family, final String requestedOrgId) {
        final List<OrgBalance> balances = balancesOf(family);
        final String orgId = chosenOrg(family, balances, requestedOrgId);
        return balances.stream().filter(entry -> entry.orgId().equals(orgId)).findFirst()
                .map(total -> new FamilyBalances(orgId, total.state(), total.balance(), total.balanceCents(),
                        family.ledger.breakdown(orgId)))
                .orElseGet(FamilyBalances::none);
    }

    /**
     * ONE trip's books for one person (or for the trip itself: its own ledger), as a statement of a single trip
     * group: for someone who is on the Balance page only by a finance role on that trip, whom the
     * per-organization reads above answer nothing. The rows are those {@code txCmds.getTransactionsOnBooks}
     * lists ({@link TripFinanceGate#onBooks}, among the rows this site lists), folded by the same code as every
     * other statement. Authorized by the trip's finance roles ({@link TripFinanceGate#canReadLedgers}) or wider:
     * whoever passes the money pages' rule for the trip, and the person themself or whoever acts for them.
     * Anyone else, an unknown trip and one this site does not serve get the EMPTY statement (no trip group).
     */
    public Statement tripStatement(final Person.Id personId, final String tripId) {
        final Trip trip = knownTrip(tripId);
        if (personId == null || trip == null || !mayReadBooks(personId, trip)) {
            return Statement.none();
        }
        return new Snapshot(List.of(personId), new HashMap<>(), true).ledger.tripStatement(trip);
    }

    private boolean mayReadBooks(final Person.Id personId, final Trip trip) {
        final Caller caller = callerSource.get();
        return TripFinanceGate.forCaller(callerSource).canReadLedgers(trip.getId())
                || TripFinanceGate.seesMoney(caller, trip) || new Viewer().full(personId);
    }

    /**
     * The Trip Contacts figures: for everyone on the trip's roster, the balance on THIS trip's books and the
     * balance with the trip's organization. A traveler's own row (and a family member's they manage) is always
     * theirs to see; everyone else's needs the money pages' rule ({@link TripFinanceGate#seesMoney}). ONE
     * trip-side binding read for the whole trip plus one partition read per visible person, never a binding read
     * per row.
     *
     * <p>The organization figure is the owner's deliberate exception to "a trip finance role reaches that trip's
     * books only" (decided 2026-11-20): it is shown to every viewer who passes the money rule on this trip, a
     * holder of {@code tripFinView} on this trip alone included, and to the traveler. Do not narrow it to
     * organization reach as a fix; {@code LedgerCommandsTest} and {@code TripBooksPwIT} pin it.
     */
    public TripBalances tripAndOrgBalances(final String tripId) {
        final Trip trip = knownTrip(tripId);
        if (trip == null) {
            return TripBalances.none(tripId);
        }
        final Viewer viewer = new Viewer();
        final boolean staff = TripFinanceGate.seesMoney(viewer.caller, trip);
        final Set<String> onBooks = new HashSet<>(reads.bindings(trip.getId(), BindingType.TRIP,
                BindingType.TRANSACTION));
        final Map<Person.Id, TripOrgBalance> byPerson = new LinkedHashMap<>();
        for (final Person.Id personId : trip.getPeople()) {
            if (personId != null && (staff || viewer.full(personId))) {
                byPerson.put(personId, tripAndOrg(personId, trip, onBooks));
            }
        }
        return new TripBalances(trip.getId(), trip.getOrgId(), nameOf(trip.getOrgId()), byPerson);
    }

    /**
     * The travelers' rows on ONE trip's books (its TRIP to TRANSACTION bindings, as {@code tripTransactions.jsf}
     * lists them, so someone who has left the roster is still there), date sorted and mutable. For the trip
     * payments report, which used to list every roster member's WHOLE ledger: every other trip's rows, and every
     * other organization's this host listed. The trip's own rows (its expense side) are not travelers' rows and
     * are left out. Empty unless the viewer passes the money pages' rule ({@link TripFinanceGate#seesMoney}).
     */
    public List<Transaction> tripBooks(final String tripId) {
        final Trip trip = knownTrip(tripId);
        final List<Transaction> rows = new ArrayList<>();
        if (trip == null || !TripFinanceGate.seesMoney(callerSource.get(), trip)) {
            return rows;
        }
        final ListingScope site = ListingScope.forSite();
        for (final Map.Entry<Person.Id, Set<String>> owner : rowIdsByOwner(trip.getId()).entrySet()) {
            for (final Transaction tx : partitionOf(owner.getKey())) {
                if (owner.getValue().contains(tx.getTxId()) && site.reaches(tx.getOrgId())) {
                    rows.add(tx);
                }
            }
        }
        rows.sort(LedgerStatements.BY_DATE);
        return rows;
    }

    /**
     * Whether the caller may read this person's balances at all: themself, someone they may act for, a site
     * admin, or staff with money reach in at least one organization (which then bounds what they are shown).
     * The REST edge's 403; a page needs no such question, since every read here already answers by reach.
     */
    public boolean mayView(final Person.Id subject) {
        final Viewer viewer = new Viewer();
        return subject != null && (viewer.full(subject) || viewer.hasStaffReach());
    }

    // ------------------------------------------------------------------ who sees what

    /** The request's two gates, resolved once per call: the site's listing rule and who is looking. */
    private final class Viewer {
        private final Caller caller = callerSource.get();
        private final ListingScope site = ListingScope.forSite();
        private final Set<Person.Id> managed = managedBy(caller);
        private final Map<String, Boolean> staffAnswers = new HashMap<>();
        private PeopleReach people;

        /** The subject themself, someone who may act for them, or a site admin: every org the site lists. */
        boolean full(final Person.Id subject) {
            return caller.isSiteAdmin()
                    || (caller.isAuthenticated() && (subject.equals(caller.personId()) || managed.contains(subject)));
        }

        /** Whether this viewer is shown {@code subject}'s rows of this organization (null: org-less rows). */
        boolean sees(final Person.Id subject, final String orgId) {
            if (!site.reaches(orgId)) {
                return false;
            }
            return full(subject) || (orgId != null && staffSees(orgId));
        }

        private boolean staffSees(final String orgId) {
            return staffAnswers.computeIfAbsent(orgId, this::staffHolds);
        }

        private boolean staffHolds(final String orgId) {
            return caller.isAuthenticated()
                    && (people().admits(orgId) || caller.has(PrivilegeCommands.VIEW_FINANCES, orgId));
        }

        private PeopleReach people() {
            if (people == null) {
                people = PeopleReach.of(orgs);
            }
            return people;
        }

        boolean hasStaffReach() {
            return caller.isAuthenticated() && (!people().isNone() || orgs.getOrganizations().stream()
                    .anyMatch(org -> caller.has(PrivilegeCommands.VIEW_FINANCES, org.getId().getValue())));
        }

        /** The organization's name when any subject's rows of it would be shown; blank for one out of reach. */
        String nameOfReachable(final List<Person.Id> subjects, final String orgId) {
            if (orgId == null || UNASSIGNED_ORG_ID.equals(orgId)
                    || subjects.stream().noneMatch(subject -> sees(subject, orgId))) {
                return "";
            }
            return nameOf(orgId);
        }
    }

    /** Who the caller may act for ({@code PersonCommands.canAccessUserId}'s list); empty for nobody. */
    private static Set<Person.Id> managedBy(final Caller caller) {
        if (!caller.isAuthenticated()) {
            return Set.of();
        }
        return DAO.getInstance().getPerson(caller.personId(), Cached.YES)
                .map(person -> Set.copyOf(person.getManagedUsers()))
                .orElse(Set.of());
    }

    // ------------------------------------------------------------------ the snapshot one call works from

    /** What one call answers from: ONE partition read per subject, already cut down to what the viewer sees. */
    private final class Snapshot implements LedgerStatements.Source {
        private final Viewer viewer = new Viewer();
        /** The partitions already read, shared by the snapshots of one call so nobody's is read twice. */
        private final Map<Person.Id, List<Transaction>> partitions;
        /** One trip's books: the viewer was authorized on the TRIP, so only the site's listing rule cuts rows. */
        private final boolean siteReachOnly;
        /** The arithmetic, shared with the migration's verifier: buckets, balances, statements. */
        private final LedgerStatements ledger;
        private final List<Person.Id> subjects;
        /** The trips a subject is on whose organization the viewer sees, by trip id (read on first use). */
        private Map<String, Trip> tripsOn;

        Snapshot(final Collection<Person.Id> subjects, final Map<Person.Id, List<Transaction>> partitions,
                final boolean siteReachOnly) {
            // Both set before the ledger is built: its constructor reads the partitions through this source.
            this.partitions = partitions;
            this.siteReachOnly = siteReachOnly;
            this.ledger = new LedgerStatements(this, subjects);
            this.subjects = ledger.subjects();
        }

        @Override
        public List<Transaction> partition(final Person.Id subject) {
            return partitions.computeIfAbsent(subject, LedgerCommands.this::partitionOf);
        }

        @Override
        public boolean sees(final Person.Id subject, final String orgId) {
            return siteReachOnly ? viewer.site.reaches(orgId) : viewer.sees(subject, orgId);
        }

        @Override
        public String preferredName(final Person.Id personId) {
            return LedgerCommands.preferredName(personId);
        }

        @Override
        public boolean payableInApp(final Trip trip) {
            return orgs.effectivePaymentConfig(trip).isPayable();
        }

        @Override
        public List<String> bindings(final String rowKey, final BindingType destType) {
            return reads.bindings(rowKey, BindingType.TRANSACTION, destType);
        }

        @Override
        public Trip trip(final String tripId) {
            return storedTrip(tripId);
        }

        @Override
        public String orgName(final String orgId) {
            return nameOf(orgId);
        }

        @Override
        public String personName(final Person.Id personId) {
            return LedgerCommands.personName(personId);
        }

        @Override
        public String eventTitle(final String eventId) {
            return LedgerCommands.this.eventTitle(eventId);
        }

        Map<String, Trip> tripsOn() {
            if (tripsOn == null) {
                tripsOn = new LinkedHashMap<>();
                final List<Trip> upcoming = upcomingHere();
                final Map<String, List<Registration>> filed = new HashMap<>();
                for (final Person.Id subject : subjects) {
                    final List<Trip> mine = new ArrayList<>(trips.getTripsForUser(subject));
                    final Set<String> onRoster = new HashSet<>(mine.stream().map(Trip::getId).toList());
                    upcoming.stream()
                            .filter(trip -> !onRoster.contains(trip.getId()))
                            .filter(trip -> hasFiled(subject, filed.computeIfAbsent(trip.getId(),
                                    LedgerCommands.this::registrationsOf)))
                            .forEach(mine::add);
                    mine.stream()
                            .filter(trip -> hasOrg(trip) && viewer.sees(subject, trip.getOrgId()))
                            .forEach(trip -> tripsOn.putIfAbsent(trip.getId(), trip));
                }
            }
            return tripsOn;
        }
    }

    private Snapshot snapshot(final Collection<Person.Id> subjects) {
        return new Snapshot(subjects, new HashMap<>(), false);
    }

    /** The trips this site lists that have not started ({@code start >= today}): the registration walk's bound. */
    private List<Trip> upcomingHere() {
        final LocalDate now = today.get();
        return reads.activeTrips().stream().filter(trip -> isUpcoming(trip, now)).toList();
    }

    private static boolean isUpcoming(final Trip trip, final LocalDate now) {
        return trip.getStartDate() != null && !trip.getStartDate().toLocalDate().isBefore(now);
    }

    private static boolean hasFiled(final Person.Id subject, final List<Registration> registrations) {
        return registrations.stream().anyMatch(reg -> subject.equals(reg.getUserId())
                && reg.getStatus() != Registration.Status.NOT_REGISTERED);
    }

    private List<Registration> registrationsOf(final String tripId) {
        try {
            return reads.registrations(tripId);
        } catch (final RuntimeException ex) {
            log.error("Error reading the registrations of trip {}: ", tripId, ex);
            return List.of();
        }
    }

    /** The partition read, with this bean's read contract: a store failure logs and reads as no rows. */
    private List<Transaction> partitionOf(final Person.Id personId) {
        try {
            return reads.partition(personId);
        } catch (final RuntimeException ex) {
            log.error("Error reading the ledger of {}: ", personId.getValue(), ex);
            return List.of();
        }
    }

    // ------------------------------------------------------------------ balances

    private List<OrgBalance> balancesOf(final Snapshot snap) {
        final Map<String, OrgBalance> found = snap.ledger.balances();
        for (final Trip trip : snap.tripsOn().values()) {
            found.computeIfAbsent(trip.getOrgId(), this::noActivity);
        }
        return found.values().stream().sorted(UNASSIGNED_LAST.thenComparing(OrgBalance::orgName,
                String.CASE_INSENSITIVE_ORDER).thenComparing(OrgBalance::orgId)).toList();
    }

    private static final Comparator<OrgBalance> UNASSIGNED_LAST = Comparator.comparing(OrgBalance::unassigned);

    private OrgBalance noActivity(final String orgId) {
        return new OrgBalance(orgId, nameOf(orgId), false, 0d, 0L, 0, State.NO_ACTIVITY);
    }

    private static List<OrgBalance> othersOf(final List<OrgBalance> balances, final String orgId) {
        return balances.stream().filter(entry -> !entry.orgId().equals(orgId) && entry.nonZero()).toList();
    }

    static long cents(final double amount) {
        return LedgerStatements.cents(amount);
    }

    // ------------------------------------------------------------------ the default organization

    private String defaultOrgOf(final Snapshot snap) {
        final LocalDate now = today.get();
        final List<Trip> on = new ArrayList<>(snap.tripsOn().values());
        final Trip next = on.stream().filter(trip -> isUpcoming(trip, now))
                .min(Comparator.comparing(Trip::getStartDate).thenComparing(Trip::getId)).orElse(null);
        if (next != null) {
            return next.getOrgId();
        }
        final Trip last = on.stream().filter(trip -> trip.getStartDate() != null)
                .max(Comparator.comparing(Trip::getStartDate).thenComparing(Trip::getId)).orElse(null);
        if (last != null) {
            return last.getOrgId();
        }
        return snap.ledger.orgOfLatestRow();
    }

    // ------------------------------------------------------------------ the statement

    private Statement statementOf(final Snapshot snap, final String orgId) {
        final Statement statement = snap.ledger.statement(orgId);
        if (statement != null) {
            return statement;
        }
        final String name = snap.viewer.nameOfReachable(snap.subjects, orgId);
        return name.isEmpty() ? Statement.none() : snap.ledger.emptyStatement(orgId, name);
    }

    // ------------------------------------------------------------------ one trip's figures

    private TripOrgBalance tripAndOrg(final Person.Id personId, final Trip trip, final Set<String> onBooks) {
        double onTrip = 0d;
        double withOrg = 0d;
        for (final Transaction tx : partitionOf(personId)) {
            if (tx.getDeleted() != null) {
                continue;
            }
            final double share = LedgerStatements.shareOf(tx);
            if (onBooks.contains(LedgerStatements.keyOf(tx))) {
                onTrip += share;
            }
            if (hasOrg(trip) && trip.getOrgId().equals(tx.getOrgId())) {
                withOrg += share;
            }
        }
        return new TripOrgBalance(personId, true, onTrip, cents(onTrip), withOrg, cents(withOrg));
    }

    /** The trip's bound rows as owner to transaction ids, the trip's own ledger left out. */
    private Map<Person.Id, Set<String>> rowIdsByOwner(final String tripId) {
        final Map<Person.Id, Set<String>> byOwner = new LinkedHashMap<>();
        for (final String rowKey : reads.bindings(tripId, BindingType.TRIP, BindingType.TRANSACTION)) {
            final CompositeKey key = CompositeKey.from(rowKey);
            if (!tripId.equals(key.getPartitionKey())) {
                byOwner.computeIfAbsent(Person.Id.from(key.getPartitionKey()), owner -> new HashSet<>())
                        .add(key.getSortKey());
            }
        }
        return byOwner;
    }

    // ------------------------------------------------------------------ lookups

    /** The trip as this site serves it, or null: the money gate's own test, so the two cannot disagree. */
    private Trip knownTrip(final String tripId) {
        return TripFinanceGate.knownTrip(trips, tripId);
    }

    /** The stored trip, or null for a binding that names a trip which is gone (or cannot be read: logged). */
    private static Trip storedTrip(final String tripId) {
        try {
            return DAO.getInstance().getTrip(tripId, Cached.YES).orElse(null);
        } catch (final RuntimeException ex) {
            log.error("Error reading trip {} for a statement: ", tripId, ex);
            return null;
        }
    }

    private String eventTitle(final String eventId) {
        final TripEvent event = trips.getTripEvent(eventId);
        return event == null || event.getTitle() == null ? "" : event.getTitle();
    }

    private static String personName(final Person.Id personId) {
        return PersonNames.displayOr(DAO.getInstance().getPerson(personId, Cached.YES).orElse(null),
                personId.getValue());
    }

    private static String preferredName(final Person.Id personId) {
        return DAO.getInstance().getPerson(personId, Cached.YES)
                .map(Person::getPreferredName)
                .map(String::trim)
                .orElse("");
    }

    /**
     * An organization's stored name for a money figure's label: blank for no organization, the id itself for
     * one that is gone. Shared with the REST resource, which had an identical copy.
     */
    public static String nameOf(final String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return "";
        }
        return DAO.getInstance().getOrganization(Organization.Id.from(orgId), Cached.YES)
                .map(Organization::getName)
                .orElse(orgId);
    }

    private static boolean hasOrg(final Trip trip) {
        return trip.getOrgId() != null && !trip.getOrgId().isBlank();
    }
}
