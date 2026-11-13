package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.OptionChoice;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.RegistrationOption;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.security.TicketCodes;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * The check-in bean end to end against the in-memory store ({@code docs/checkin.md}): the gate matrix per caller
 * kind, lookup exactness, the check-in and clear outcomes, the counts arithmetic, the trip-bound balance, the ticket
 * page's view levels, batch badges and the export rows. Each test builds its own trip.
 */
public class CheckinCommandsTest {
    private static final LocalDateTime START = LocalDateTime.of(2027, 3, 12, 8, 0);
    private static final LocalDateTime NOW = LocalDateTime.of(2027, 3, 12, 9, 14, 7, 123_456_789);

    @BeforeClass
    public void seed() {
        FakeData.initFakeData();
        FakeData.addFakeData();
    }

    // ------------------------------------------------------------------ fixtures

    /** A conference-shaped trip: three days, a priced required admission question with per-day choices. */
    private static Trip newTrip() throws IOException {
        final RegistrationOption admission = new RegistrationOption(1, "Admission", "", true,
                RegistrationOption.QuestionType.CHOICE, true, List.of(
                        new OptionChoice("full", "Full weekend", 150_00, null, null, List.of(), null),
                        new OptionChoice("fri", "Friday only", 60_00, null, null, List.of("2027-03-12"), null),
                        new OptionChoice("sat", "Saturday only", 60_00, null, null, List.of("2027-03-13"), null)));
        final RegistrationOption shirt = new RegistrationOption(2, "Shirt", "", true,
                RegistrationOption.QuestionType.CHOICE, false, List.of(new OptionChoice("m", "Medium", 0, null, null,
                        null, null)));
        final RegistrationOption diet = new RegistrationOption(3, "Diet", "", true);
        final RegistrationOption hidden = new RegistrationOption(4, "Staff note", "", false);
        final Trip trip = Trip.builder().id(UUID.randomUUID().toString()).title("Check-in Conference")
                .startDate(START).endDate(START.plusDays(2).withHour(17))
                .regOptions(new ArrayList<>(List.of(admission, shirt, diet, hidden))).people(new ArrayList<>())
                .build();
        trip.setOrgId(FakeData.CFPW_ORG_ID);
        assertTrue(DAO.getInstance().saveTrip(trip));
        return trip;
    }

    private static Person newPerson(final String first, final String last, final String email) throws IOException {
        final Person person = new Person();
        person.setFirst(first);
        person.setLast(last);
        person.setEmail(email);
        assertTrue(DAO.getInstance().savePerson(person));
        return person;
    }

    private static Registration register(final Trip trip, final Person person, final Registration.Status status,
            final String admission) throws IOException {
        final Map<String, String> answers = new HashMap<>();
        if (admission != null) {
            answers.put("1", admission);
        }
        answers.put("3", "Vegetarian");
        answers.put("4", "secret staff note");
        final Registration reg = RegistrationCommands.withTicket(
                new Registration(trip.getId(), person.getId(), START.minusDays(30), status, answers));
        assertTrue(DAO.getInstance().saveRegistration(reg));
        return reg;
    }

    private static Person.Id grant(final String name, final String scope) {
        final Person.Id who = Person.Id.newInstance();
        final PrivilegeCommands priv = new PrivilegeCommands();
        assertTrue(priv.savePrivilege(priv.getOrCreate(name, scope, name).withNewPerson(who)));
        return who;
    }

    private static CheckinCommands as(final Person.Id who) {
        return new CheckinCommands(() -> TestCallers.person(who), () -> null, () -> NOW);
    }

    private static CheckinCommands admin() {
        return new CheckinCommands(TestCallers::siteAdmin, () -> null, () -> NOW);
    }

    private static CheckinCommands anonymous() {
        return new CheckinCommands(() -> new Caller(null, false, AuditActor.from(null), new PrivilegeCommands()),
                () -> null, () -> NOW);
    }

    private static Registration stored(final Trip trip, final Person person) {
        return DAO.getInstance().getRegistration(trip.getId(), person.getId(), Cached.NO).orElseThrow();
    }

    // ------------------------------------------------------------------ gates

    @Test
    public void theGatesFollowTheCallerKind() throws IOException {
        final String tripId = newTrip().getId();
        final CheckinCommands staff = as(grant(PrivilegeCommands.CHECKIN_STAFF, tripId));
        final CheckinCommands manager = as(grant(PrivilegeCommands.TRIP_MGR, tripId));
        final CheckinCommands viewer = as(grant(PrivilegeCommands.TRIP_VIEW, tripId));
        final CheckinCommands finance = as(grant(PrivilegeCommands.TRIP_FIN_VIEW, tripId));
        final CheckinCommands finAdmin = as(grant(PrivilegeCommands.TRIP_FIN_ADMIN, tripId));
        final CheckinCommands stranger = as(Person.Id.newInstance());

        assertGates(anonymous(), tripId, false, false, false, false, false);
        assertGates(stranger, tripId, false, false, false, false, false);
        assertGates(admin(), tripId, true, true, true, true, true);
        assertGates(staff, tripId, true, false, true, false, true);
        assertGates(manager, tripId, true, true, true, false, true);
        assertGates(viewer, tripId, false, false, true, false, true);
        assertGates(finance, tripId, false, false, true, true, false);
        assertGates(finAdmin, tripId, false, false, true, false, false);
        assertGates(admin(), "no-such-trip", false, false, false, false, false);
        assertGates(admin(), null, false, false, false, false, false);
    }

    private static void assertGates(final CheckinCommands bean, final String tripId, final boolean checkIn,
            final boolean clear, final boolean counts, final boolean money, final boolean badges) {
        assertEquals(bean.canCheckIn(tripId), checkIn, "canCheckIn");
        assertEquals(bean.canClearCheckIn(tripId), clear, "canClearCheckIn");
        assertEquals(bean.canViewCounts(tripId), counts, "canViewCounts");
        assertEquals(bean.canSeeMoney(tripId), money, "canSeeMoney");
        assertEquals(bean.canPrintBadges(tripId), badges, "canPrintBadges");
    }

    @Test
    public void staffTripsListsExplicitCheckinStaffOnly() throws IOException {
        final Trip trip = Trip.builder().id(UUID.randomUUID().toString()).title("Staffed")
                .startDate(LocalDateTime.now().plusDays(5)).endDate(LocalDateTime.now().plusDays(7)).build();
        assertTrue(DAO.getInstance().saveTrip(trip));
        final Person.Id staff = grant(PrivilegeCommands.CHECKIN_STAFF, trip.getId());

        final Trip other = newTrip();
        assertEquals(CheckinCommands.staffTripsOf(staff, List.of(trip, other)), List.of(trip));
        assertTrue(CheckinCommands.staffTripsOf(TestCallers.SITE_ADMIN_ID, List.of(trip, other)).isEmpty(),
                "a site admin gets no entry per trip");
        // The live read goes through the shared active-trips index, which other tests also fill: no content assert.
        assertNotNull(as(staff).staffTrips());
        assertTrue(anonymous().staffTrips().isEmpty());
    }

    // ------------------------------------------------------------------ lookup

    @Test
    public void aWholeTicketMatchesExactlyAndNeverAPrefix() throws IOException {
        final Trip trip = newTrip();
        final Registration ann = register(trip, newPerson("Ann", "Lee", "ann@example.com"),
                Registration.Status.CONFIRMED, "full");
        register(trip, newPerson("Bob", "Lee", "bob@example.com"), Registration.Status.CONFIRMED, "fri");
        final CheckinCommands staff = as(grant(PrivilegeCommands.CHECKIN_STAFF, trip.getId()));
        final String code = ann.getTicketCode();

        assertEquals(staff.lookup(trip.getId(), code).size(), 1);
        assertEquals(staff.lookup(trip.getId(), TicketCodes.grouped(code).toLowerCase()).getFirst().getName(),
                "Lee, Ann", "a grouped, lower-case code is still the whole code");
        assertTrue(staff.lookup(trip.getId(), code.substring(0, 12)).isEmpty(), "a prefix matches nothing");
        final String other = TicketCodes.mint();
        assertTrue(staff.lookup(trip.getId(), other).isEmpty(), "a well-formed code that is not theirs");
        assertEquals(staff.byTicket(trip.getId(), code).getUserId(), ann.getUserId());
        assertNull(staff.byTicket(trip.getId(), code.substring(0, 12)));
        assertNull(staff.byTicket(null, code));
    }

    @Test
    public void everyNameTokenMustMatch() throws IOException {
        final Trip trip = newTrip();
        final Person ann = newPerson("Ann", "Lee", "ann.lee@example.com");
        ann.setNickname("Annie");
        assertTrue(DAO.getInstance().savePerson(ann));
        register(trip, ann, Registration.Status.CONFIRMED, "full");
        register(trip, newPerson("Anne", "Moss", "moss@example.com"), Registration.Status.PENDING, "sat");
        register(trip, newPerson("Annabel", "Gone", "gone@example.com"), Registration.Status.NOT_REGISTERED, null);
        final CheckinCommands staff = as(grant(PrivilegeCommands.CHECKIN_STAFF, trip.getId()));

        assertEquals(names(staff.lookup(trip.getId(), "ann")), List.of("Lee, Annie", "Moss, Anne"),
                "pending people are found too, a cancelled registration is not");
        assertEquals(names(staff.lookup(trip.getId(), "  ANN   lee ")), List.of("Lee, Annie"));
        assertEquals(names(staff.lookup(trip.getId(), "annie")), List.of("Lee, Annie"), "the nickname");
        assertEquals(names(staff.lookup(trip.getId(), "moss@ex")), List.of("Moss, Anne"), "the email");
        assertTrue(staff.lookup(trip.getId(), "ann zed").isEmpty());
        assertTrue(staff.lookup(trip.getId(), " ").isEmpty());
        assertTrue(staff.lookup(trip.getId(), null).isEmpty());
        assertEquals(staff.lookup(trip.getId(), "moss").getFirst().getRegistrationType(), "Saturday only");
        assertTrue(as(Person.Id.newInstance()).lookup(trip.getId(), "ann").isEmpty(), "not staff, nothing");
        assertTrue(staff.find(trip.getId(), " , ").needsQuery(), "nothing typed asks for a search");
        assertEquals(names(staff.lookup(trip.getId(), "nnie le")), List.of("Lee, Annie"), "each word anywhere");
        assertTrue(staff.lookup(trip.getId(), "example.com").isEmpty(), "email only from its start");
    }

    @Test
    public void aSearchIsCappedAtTheListLimitAndSaysSo() throws IOException {
        final Trip trip = newTrip();
        for (int i = 0; i < CheckinCommands.LOOKUP_CAP + 3; i++) {
            register(trip, newPerson("Many" + i, "Cap", null), Registration.Status.CONFIRMED, "full");
        }
        final CheckinCommands.Lookup found = admin().find(trip.getId(), "cap");
        assertEquals(found.rows().size(), CheckinCommands.LOOKUP_CAP);
        assertEquals(CheckinCommands.LOOKUP_CAP, PersonCommands.PEOPLE_LIST_LIMIT, "the same limit as every list");
        assertTrue(found.truncated());
        assertFalse(admin().find(trip.getId(), "many1 cap").truncated());
    }

    /** Check-in Staff see what a person shares, so a hidden cell is not searchable; a trip manager's is. */
    @Test
    public void aHiddenCellIsSearchableOnlyWithTheAdminView() throws IOException {
        final Trip trip = newTrip();
        final Person shy = newPerson("Shy", "Cell", null);
        shy.setCell("555-321-9876");
        shy.getPrivacy().setCell(org.paulsens.trip.model.PrivacySettings.Visibility.PRIVATE);
        assertTrue(DAO.getInstance().savePerson(shy));
        register(trip, shy, Registration.Status.CONFIRMED, "full");
        final CheckinCommands staff = as(grant(PrivilegeCommands.CHECKIN_STAFF, trip.getId()));
        assertTrue(staff.lookup(trip.getId(), "9876").isEmpty());
        assertEquals(names(staff.lookup(trip.getId(), "shy")), List.of("Cell, Shy"));
        final CheckinCommands manager = as(grant(PrivilegeCommands.TRIP_MGR, trip.getId()));
        assertEquals(names(manager.lookup(trip.getId(), "9876")), List.of("Cell, Shy"));
    }

    private static List<String> names(final List<RegistrationCommands.RegRow> rows) {
        return rows.stream().map(RegistrationCommands.RegRow::getName).toList();
    }

    // ------------------------------------------------------------------ check-in

    @Test
    public void checkingInStampsOnceAndNeverOverwrites() throws IOException {
        final Trip trip = newTrip();
        final Person ann = newPerson("Ann", "Stamp", null);
        register(trip, ann, Registration.Status.CONFIRMED, "full");
        final Person.Id staffId = grant(PrivilegeCommands.CHECKIN_STAFF, trip.getId());
        final CheckinCommands staff = as(staffId);

        final CheckinCommands.Result first = staff.checkInOutcome(trip.getId(), ann.getId().getValue());
        assertEquals(first.outcome(), CheckinCommands.Outcome.CHECKED_IN);
        assertEquals(stored(trip, ann).getCheckedInAt(), NOW.withNano(0));
        assertEquals(stored(trip, ann).getCheckedInBy(), staffId);

        final CheckinCommands later = new CheckinCommands(() -> TestCallers.person(staffId), () -> null,
                () -> NOW.plusHours(1));
        final CheckinCommands.Result second = later.checkInOutcome(trip.getId(), ann.getId().getValue());
        assertEquals(second.outcome(), CheckinCommands.Outcome.ALREADY_CHECKED_IN);
        assertEquals(stored(trip, ann).getCheckedInAt(), NOW.withNano(0), "the first stamp is kept");
        assertTrue(staff.stampText(second.registration()).startsWith("at Mar 12 9:14 AM by "),
                staff.stampText(second.registration()));
        assertEquals(staff.stampText(null), "");
        assertFalse(later.checkIn(trip.getId(), ann.getId().getValue()), "the growling wrapper says no change");
    }

    @Test
    public void checkInRefusesWhatIsNotConfirmed() throws IOException {
        final Trip trip = newTrip();
        final Person pending = newPerson("Pat", "Pending", null);
        register(trip, pending, Registration.Status.PENDING, "full");
        final Person cancelled = newPerson("Cal", "Cancelled", null);
        register(trip, cancelled, Registration.Status.NOT_REGISTERED, "full");
        final CheckinCommands staff = as(grant(PrivilegeCommands.CHECKIN_STAFF, trip.getId()));

        assertEquals(staff.checkInOutcome(trip.getId(), pending.getId().getValue()).outcome(),
                CheckinCommands.Outcome.NOT_CONFIRMED);
        assertNull(stored(trip, pending).getCheckedInAt());
        assertEquals(staff.checkInOutcome(trip.getId(), cancelled.getId().getValue()).outcome(),
                CheckinCommands.Outcome.NOT_FOUND);
        assertEquals(staff.checkInOutcome(trip.getId(), Person.Id.newInstance().getValue()).outcome(),
                CheckinCommands.Outcome.NOT_FOUND);
        assertEquals(staff.checkInOutcome(trip.getId(), " ").outcome(), CheckinCommands.Outcome.NOT_ALLOWED);
        assertEquals(as(Person.Id.newInstance()).checkInOutcome(trip.getId(), pending.getId().getValue()).outcome(),
                CheckinCommands.Outcome.NOT_ALLOWED);
        assertFalse(staff.checkIn(trip.getId(), pending.getId().getValue()));
        assertFalse(staff.checkIn(trip.getId(), cancelled.getId().getValue()));
        assertFalse(as(Person.Id.newInstance()).checkIn(trip.getId(), pending.getId().getValue()));
    }

    @Test
    public void anOlderConfirmedRowGetsItsTicketAtCheckIn() throws IOException {
        final Trip trip = newTrip();
        final Person old = newPerson("Olga", "Older", null);
        assertTrue(DAO.getInstance().saveRegistration(new Registration(trip.getId(), old.getId(), START.minusDays(90),
                Registration.Status.CONFIRMED, new HashMap<>())));
        assertNull(stored(trip, old).getTicketCode());

        assertTrue(admin().checkIn(trip.getId(), old.getId().getValue()));
        assertNotNull(stored(trip, old).getTicketCode(), "CONFIRMED => ticket, applied by the stamp's own save");
    }

    @Test
    public void clearingIsForManagersAndIdempotent() throws IOException {
        final Trip trip = newTrip();
        final Person ann = newPerson("Ann", "Clear", null);
        register(trip, ann, Registration.Status.CONFIRMED, "full");
        final String id = ann.getId().getValue();
        final CheckinCommands staff = as(grant(PrivilegeCommands.CHECKIN_STAFF, trip.getId()));
        final CheckinCommands manager = as(grant(PrivilegeCommands.TRIP_MGR, trip.getId()));
        assertTrue(staff.checkIn(trip.getId(), id));

        assertEquals(staff.clearCheckInOutcome(trip.getId(), id).outcome(), CheckinCommands.Outcome.NOT_ALLOWED);
        assertFalse(staff.clearCheckIn(trip.getId(), id));
        assertNotNull(stored(trip, ann).getCheckedInAt());
        assertTrue(manager.clearCheckIn(trip.getId(), id));
        assertNull(stored(trip, ann).getCheckedInAt());
        assertNull(stored(trip, ann).getCheckedInBy());
        assertEquals(manager.clearCheckInOutcome(trip.getId(), id).outcome(), CheckinCommands.Outcome.NOT_CHECKED_IN);
        assertFalse(manager.clearCheckIn(trip.getId(), id));
        assertEquals(manager.clearCheckInOutcome(trip.getId(), Person.Id.newInstance().getValue()).outcome(),
                CheckinCommands.Outcome.NOT_FOUND);
        assertFalse(manager.clearCheckIn(trip.getId(), Person.Id.newInstance().getValue()));
        assertEquals(manager.clearCheckInOutcome(trip.getId(), null).outcome(), CheckinCommands.Outcome.NOT_ALLOWED);
    }

    @Test
    public void theFocusPanelReadsFreshForStaffOnly() throws IOException {
        final Trip trip = newTrip();
        final Person ann = newPerson("Ann", "Focus", null);
        register(trip, ann, Registration.Status.CONFIRMED, "full");

        assertEquals(admin().focus(trip.getId(), ann.getId().getValue()).getUserId(), ann.getId());
        assertNull(as(Person.Id.newInstance()).focus(trip.getId(), ann.getId().getValue()));
        assertNull(admin().focus(trip.getId(), null));
    }

    @Test
    public void thePageHelpersResolveForStaffOnly() throws IOException {
        final Trip trip = newTrip();
        final Person ann = newPerson("Ann", "Helper", null);
        final Registration reg = register(trip, ann, Registration.Status.CONFIRMED, "fri");
        final CheckinCommands staff = as(grant(PrivilegeCommands.CHECKIN_STAFF, trip.getId()));

        assertEquals(staff.personForTicket(trip.getId(), TicketCodes.grouped(reg.getTicketCode())),
                ann.getId().getValue());
        assertNull(staff.personForTicket(trip.getId(), TicketCodes.mint()));
        assertNull(as(Person.Id.newInstance()).personForTicket(trip.getId(), reg.getTicketCode()));
        assertEquals(staff.checkinUrl(trip.getId(), TicketCodes.grouped(reg.getTicketCode()).toLowerCase()),
                "/admin/checkin.jsf?trip=" + trip.getId() + "&t=" + reg.getTicketCode(), "the code normalized");
        assertEquals(staff.checkinUrl(trip.getId(), "<script>"), "/admin/checkin.jsf?trip=" + trip.getId(),
                "a raw parameter never reaches the redirect");
        assertEquals(staff.typeOf(trip, reg), "Friday only");
        assertEquals(staff.ticketRedirect(trip.getId(), reg.getTicketCode()),
                "/admin/checkin.jsf?trip=" + trip.getId() + "&t=" + reg.getTicketCode(), "staff go to the controls");
        assertNull(anonymous().ticketRedirect(trip.getId(), reg.getTicketCode()), "everyone else stays");
        assertTrue(staff.typedQuestions(null).isEmpty());
        assertEquals(staff.staffAnswers(trip, reg), List.of(new CheckinCommands.Answer("Admission", "Friday only")),
                "pick answers only: never the free-text diet, never a hidden question");
        assertTrue(staff.staffAnswers(null, reg).isEmpty());
        assertTrue(staff.staffAnswers(trip, null).isEmpty());
        assertEquals(staff.nameOf(ann.getId().getValue()), "Ann Helper");
        assertEquals(staff.nameOf(" "), "");
        assertEquals(staff.markPrintedFromUi(trip.getId(), List.of(ann.getId().getValue())), 1);
    }

    @Test
    public void aRequestMemoServesRepeatReadsAndForgetsThemOnAWrite() throws IOException {
        final Trip trip = newTrip();
        final Person ann = newPerson("Ann", "Memo", null);
        register(trip, ann, Registration.Status.CONFIRMED, "full");
        final Map<String, Object> request = new HashMap<>();
        final CheckinCommands memoized = new CheckinCommands(TestCallers::siteAdmin, () -> request, () -> NOW);

        assertEquals(memoized.counts(trip.getId()).checkedIn(), 0);
        assertNull(memoized.focus(trip.getId(), ann.getId().getValue()).getCheckedInAt());
        assertTrue(memoized.counts(trip.getId()) == memoized.counts(trip.getId()), "one computation per request");
        assertTrue(memoized.checkIn(trip.getId(), ann.getId().getValue()));
        assertEquals(memoized.counts(trip.getId()).checkedIn(), 1, "the write forgot the counts");
        assertNotNull(memoized.focus(trip.getId(), ann.getId().getValue()).getCheckedInAt(), "and the focus");
        assertNotNull(memoized.money(trip.getId()));
    }

    // ------------------------------------------------------------------ types, days, counts

    @Test
    public void theTypeIsTheStoredOneElseTheTypeAnswersLabel() throws IOException {
        final Trip trip = newTrip();
        final Registration answered = new Registration(trip.getId(), Person.Id.newInstance(), null,
                Registration.Status.CONFIRMED, Map.of("1", "fri"));
        assertEquals(CheckinCommands.registrationType(trip, answered), "Friday only");
        assertEquals(CheckinCommands.registrationType(trip, answered.withRegistrationType("Speaker")), "Speaker");
        assertEquals(CheckinCommands.registrationType(trip, answered.withRegistrationType(" ")), "Friday only");
        assertNull(CheckinCommands.registrationType(trip, null));
        assertNull(CheckinCommands.registrationType(null, answered));
        final Trip untyped = Trip.builder().id("untyped").regOptions(new ArrayList<>()).build();
        assertNull(CheckinCommands.registrationType(untyped, answered));

        assertEquals(CheckinCommands.daysOf(trip, answered), List.of(LocalDate.of(2027, 3, 12)));
        assertEquals(CheckinCommands.daysOf(trip, answered.withOptions(Map.of("1", "full"))).size(), 3,
                "a choice that names no days covers every day of the event");
        untyped.setStartDate(START);
        untyped.setEndDate(null);
        assertEquals(CheckinCommands.daysOf(untyped, answered), List.of(START.toLocalDate()), "a one-day event");
        untyped.setStartDate(null);
        assertEquals(CheckinCommands.daysOf(untyped, answered), List.of(), "no dates, no days");
    }

    @Test
    public void countsBucketConfirmedPeopleByTypeAndDay() throws IOException {
        final Trip trip = newTrip();
        final Person full = newPerson("Full", "One", null);
        register(trip, full, Registration.Status.CONFIRMED, "full");
        final Person fri = newPerson("Fri", "Two", null);
        register(trip, fri, Registration.Status.CONFIRMED, "fri");
        register(trip, newPerson("Fri", "Three", null), Registration.Status.CONFIRMED, "fri");
        register(trip, newPerson("No", "Type", null), Registration.Status.CONFIRMED, null);
        register(trip, newPerson("Pend", "Ing", null), Registration.Status.PENDING, "sat");
        register(trip, newPerson("Not", "Reg", null), Registration.Status.NOT_REGISTERED, "sat");
        final CheckinCommands staff = as(grant(PrivilegeCommands.CHECKIN_STAFF, trip.getId()));
        assertTrue(staff.checkIn(trip.getId(), full.getId().getValue()));
        assertTrue(staff.checkIn(trip.getId(), fri.getId().getValue()));

        final CheckinCommands.Counts counts = staff.counts(trip.getId());
        assertEquals(counts.registered(), 5);
        assertEquals(counts.confirmed(), 4);
        assertEquals(counts.pending(), 1);
        assertEquals(counts.checkedIn(), 2);
        assertEquals(counts.headline(), "2 of 4 checked in");
        assertEquals(counts.byType(), List.of(new CheckinCommands.Bucket("Full weekend", 1, 1),
                new CheckinCommands.Bucket("Friday only", 2, 1), new CheckinCommands.Bucket(CheckinCommands.NO_TYPE,
                        1, 0)), "the price list's order, empty choices left out, the unanswered last");
        assertEquals(counts.byDay(), List.of(new CheckinCommands.Bucket("Fri Mar 12", 4, 2),
                new CheckinCommands.Bucket("Sat Mar 13", 2, 1), new CheckinCommands.Bucket("Sun Mar 14", 2, 1)));
        assertNull(as(Person.Id.newInstance()).counts(trip.getId()));

        assertEquals(names(staff.notCheckedIn(trip.getId())), List.of("Three, Fri", "Type, No"));
        assertTrue(as(Person.Id.newInstance()).notCheckedIn(trip.getId()).isEmpty());
    }

    // ------------------------------------------------------------------ money

    @Test
    public void theTripBalanceIgnoresRowsNotBoundToTheTrip() throws IOException {
        final Trip trip = newTrip();
        final Trip other = newTrip();
        final Person ann = newPerson("Ann", "Money", null);
        register(trip, ann, Registration.Status.CONFIRMED, "full");
        ledger(ann, Transaction.TransactionType.Bill, -150f, trip.getId());
        ledger(ann, Transaction.TransactionType.Payment, 100f, trip.getId());
        ledger(ann, Transaction.TransactionType.Payment, 999f, other.getId());
        ledger(ann, Transaction.TransactionType.Payment, 50f, null);
        final CheckinCommands staff = as(grant(PrivilegeCommands.CHECKIN_STAFF, trip.getId()));

        final CheckinCommands.TripBalance balance = staff.tripBalance(trip.getId(), ann.getId().getValue());
        assertEquals(balance.paidCents(), 100_00);
        assertEquals(balance.billedCents(), 150_00);
        assertEquals(balance.balanceCents(), -50_00);
        assertEquals(balance.due(), "$50.00");
        assertEquals(balance.paid(), "$100.00");
        assertEquals(as(ann.getId()).tripBalance(trip.getId(), ann.getId().getValue()).dueCents(), 50_00,
                "the person sees their own");
        assertNull(as(Person.Id.newInstance()).tripBalance(trip.getId(), ann.getId().getValue()));
        assertNull(staff.tripBalance(trip.getId(), null));

        final CheckinCommands finance = as(grant(PrivilegeCommands.TRIP_FIN_VIEW, trip.getId()));
        final CheckinCommands.Money money = finance.money(trip.getId());
        assertEquals(money.paidCents(), 100_00);
        assertEquals(money.dueCents(), 50_00);
        assertEquals(money.owing(), 1);
        assertEquals(money.paidUp(), 0);
        assertEquals(money.paid(), "$100.00");
        assertEquals(money.due(), "$50.00");
        assertNull(staff.money(trip.getId()), "check-in staff see a person's due, not the trip's money");
    }

    @Test
    public void aPaidUpPersonCountsAsPaidUp() throws IOException {
        final Trip trip = newTrip();
        final Person ann = newPerson("Ann", "Paid", null);
        register(trip, ann, Registration.Status.CONFIRMED, "full");
        ledger(ann, Transaction.TransactionType.Bill, -60f, trip.getId());
        ledger(ann, Transaction.TransactionType.Payment, 60f, trip.getId());

        final CheckinCommands.Money money = admin().money(trip.getId());
        assertEquals(money.paidUp(), 1);
        assertEquals(money.owing(), 0);
    }

    private static void ledger(final Person who, final Transaction.TransactionType type, final float amount,
            final String tripId) throws IOException {
        final Transaction tx = new Transaction(null, who.getId(), null, Transaction.Type.Tx, type, START, amount,
                "Test", "test row");
        assertTrue(DAO.getInstance().saveTransaction(tx));
        if (tripId != null) {
            new BindingCommands().setBindings(new BindingCommands().key(who.getId().getValue(), tx.getTxId()),
                    BindingType.TRANSACTION, BindingType.TRIP, List.of(tripId), true);
        }
    }

    // ------------------------------------------------------------------ tickets

    @Test
    public void theTicketViewLevelsFollowWhoIsLooking() throws IOException {
        final Trip trip = newTrip();
        final Person ann = newPerson("Ann", "Ticket", "ann.ticket@example.com");
        final String code = register(trip, ann, Registration.Status.CONFIRMED, "fri").getTicketCode();
        final Person manager = newPerson("Mia", "Manager", null);
        manager.getManagedUsers().add(ann.getId());
        assertTrue(DAO.getInstance().savePerson(manager));

        final CheckinCommands.TicketView minimal = anonymous().ticketView(trip.getId(), code);
        assertEquals(minimal.level(), CheckinCommands.Level.MINIMAL);
        assertEquals(minimal.name(), "Ann Ticket");
        assertEquals(minimal.registrationType(), "Friday only");
        assertEquals(minimal.groupedCode(), TicketCodes.grouped(code));
        assertTrue(minimal.qrSvg().startsWith("<svg"), minimal.qrSvg());
        assertEquals(minimal.dates(), "Mar 12, 2027 - Mar 14, 2027");
        assertNull(minimal.status(), "status is the owner's");
        assertTrue(minimal.answers().isEmpty(), "answers are the owner's");
        assertNull(minimal.balance());

        final CheckinCommands.TicketView owner = as(ann.getId()).ticketView(trip.getId(), code);
        assertEquals(owner.level(), CheckinCommands.Level.OWNER);
        assertEquals(owner.status(), "Confirmed");
        assertEquals(owner.answers(), List.of(new CheckinCommands.Answer("Admission", "Friday only"),
                new CheckinCommands.Answer("Diet", "Vegetarian")), "shown questions only, by label");
        assertNotNull(owner.balance());
        assertEquals(as(manager.getId()).ticketView(trip.getId(), code).level(), CheckinCommands.Level.OWNER,
                "the family manager");
        assertEquals(admin().ticketView(trip.getId(), code).level(), CheckinCommands.Level.STAFF);

        assertEquals(anonymous().ticketView(trip.getId(), code.substring(0, 12)).level(),
                CheckinCommands.Level.INVALID);
        assertEquals(anonymous().ticketView("no-such-trip", code).level(), CheckinCommands.Level.INVALID);
        assertEquals(anonymous().ticketView(newTrip().getId(), code).level(), CheckinCommands.Level.INVALID,
                "a ticket belongs to its own event");
    }

    @Test
    public void aCancelledTicketIsInvalidToAnyoneButItsOwner() throws IOException {
        final Trip trip = newTrip();
        final Person ann = newPerson("Ann", "Cancelled", null);
        final Registration reg = register(trip, ann, Registration.Status.CONFIRMED, "full");
        assertTrue(DAO.getInstance().saveRegistration(reg.withStatus(Registration.Status.NOT_REGISTERED)));

        assertEquals(anonymous().ticketView(trip.getId(), reg.getTicketCode()).level(), CheckinCommands.Level.INVALID);
        assertEquals(as(ann.getId()).ticketView(trip.getId(), reg.getTicketCode()).status(), "Not Registered");
    }

    @Test
    public void ticketForMintsLazilyForThoseEntitled() throws IOException {
        final Trip trip = newTrip();
        final Person old = newPerson("Olga", "Lazy", null);
        assertTrue(DAO.getInstance().saveRegistration(new Registration(trip.getId(), old.getId(), START,
                Registration.Status.CONFIRMED, new HashMap<>())));
        final Person pending = newPerson("Pat", "Lazy", null);
        assertTrue(DAO.getInstance().saveRegistration(new Registration(trip.getId(), pending.getId(), START,
                Registration.Status.PENDING, new HashMap<>())));

        assertNull(as(Person.Id.newInstance()).ticketFor(trip.getId(), old.getId().getValue()), "a stranger");
        final String minted = as(old.getId()).ticketFor(trip.getId(), old.getId().getValue());
        assertNotNull(minted);
        assertEquals(stored(trip, old).getTicketCode(), minted, "stored, so the next read agrees");
        assertEquals(admin().ticketFor(trip.getId(), old.getId().getValue()), minted, "never re-minted");
        assertNull(admin().ticketFor(trip.getId(), pending.getId().getValue()), "no ticket until confirmed");
        assertNull(admin().ticketFor(trip.getId(), null));

        final String url = admin().ticketUrlFor(trip.getId(), old.getId().getValue());
        assertTrue(url.endsWith("/trip/ticket.jsf?trip=" + trip.getId() + "&t=" + minted), url);
        assertTrue(url.startsWith("https://"), "absolute, on the org's own site: " + url);
        assertTrue(url.startsWith(admin().ticketOrigin(trip.getId())), url);
        assertEquals(admin().ticketOrigin("no-such-trip"), "");
        assertEquals(admin().ticketUrl(trip, null), "");
        assertEquals(admin().ticketUrl(null, minted), "");
        assertTrue(admin().qrSvg(url).contains("<svg"));
    }

    // ------------------------------------------------------------------ badges and export

    @Test
    public void badgesFreezeTheirIdsAndMarkThemPrinted() throws IOException {
        final Trip trip = newTrip();
        final Person zed = newPerson("Zed", "Zulu", null);
        register(trip, zed, Registration.Status.CONFIRMED, "full");
        final Person amy = newPerson("Amy", "Alpha", null);
        assertTrue(DAO.getInstance().saveRegistration(new Registration(trip.getId(), amy.getId(), START.minusDays(1),
                Registration.Status.CONFIRMED, new HashMap<>(Map.of("1", "sat")))));
        register(trip, newPerson("Pat", "Pending", null), Registration.Status.PENDING, "full");
        final CheckinCommands viewer = as(grant(PrivilegeCommands.TRIP_VIEW, trip.getId()));

        final List<String> ids = viewer.badgeIds(trip.getId(), null, false);
        assertEquals(ids, List.of(amy.getId().getValue(), zed.getId().getValue()), "confirmed, by last name");
        assertEquals(viewer.badgeIds(trip.getId(), START.minusDays(2).toLocalDate().toString(), false),
                List.of(amy.getId().getValue()), "registered after the cutoff date");
        assertEquals(viewer.badgeIds(trip.getId(), START.minusDays(2).toString(), false).size(), 1, "a date-time");
        assertEquals(viewer.badgeIds(trip.getId(), "garbage", false).size(), 2, "an unreadable cutoff is ignored");

        final List<CheckinCommands.Badge> badges = viewer.badges(trip.getId(), ids);
        assertEquals(badges.size(), 2);
        assertEquals(badges.getFirst().preferredName(), "Amy");
        assertEquals(badges.getFirst().registrationType(), "Saturday only");
        assertTrue(badges.getFirst().qrSvg().startsWith("<svg"));
        assertNotNull(stored(trip, amy).getTicketCode(), "a badge mints the ticket it prints");
        assertEquals(viewer.unprintedBadges(trip.getId()), 2);

        assertEquals(viewer.markPrinted(trip.getId(), List.of(amy.getId().getValue(), "vanished")), 1);
        assertEquals(stored(trip, amy).getBadgePrintedAt(), NOW.withNano(0));
        assertEquals(viewer.badgeIds(trip.getId(), "", true), List.of(zed.getId().getValue()), "unprinted only");
        assertEquals(viewer.unprintedBadges(trip.getId()), 1);

        final CheckinCommands stranger = as(Person.Id.newInstance());
        assertTrue(stranger.badgeIds(trip.getId(), null, false).isEmpty());
        assertTrue(stranger.badges(trip.getId(), ids).isEmpty());
        assertTrue(viewer.badges(trip.getId(), null).isEmpty());
        assertEquals(stranger.markPrinted(trip.getId(), ids), 0);
        assertEquals(viewer.markPrinted(trip.getId(), null), 0);
        assertEquals(stranger.unprintedBadges(trip.getId()), 0);
    }

    @Test
    public void theExportRowEscapesAQuestionLabelOnScreenAndLeavesTheFileTextAlone() throws IOException {
        // The export page renders tripOpts unescaped (for the list markup), so the label must be escaped
        // here; exportOpts goes into a CSV/XLSX cell, where the label is plain text.
        final Trip trip = newTrip();
        trip.getRegOptions().get(2).setShortDesc("Diet & <img src=x onerror=alert(1)>");
        final Person ann = newPerson("Ann", "Escape", null);
        final Registration reg = register(trip, ann, Registration.Status.CONFIRMED, null);
        reg.getOptions().put("3", "Fish & <chips>");
        assertTrue(DAO.getInstance().saveRegistration(reg));
        trip.getPeople().add(ann.getId());

        final Map<String, Object> row = admin().exportRow(trip, ann.getId());

        final String html = (String) row.get("tripOpts");
        assertTrue(html.contains("<li><b>Diet &amp; &lt;img src=x onerror=alert(1)&gt;</b> "
                + "Fish &amp; &lt;chips&gt;</li>"), html);
        assertFalse(html.contains("<img"), "no label markup reaches the page");
        assertTrue(((String) row.get("exportOpts")).contains("Diet & <img src=x onerror=alert(1)> Fish & <chips>"),
                "the file cell is plain text: neither the label nor the answer carries an HTML entity");
    }

    @Test
    public void theExportRowKeepsTheOldCellsAndAddsTheNewOnes() throws IOException {
        final Trip trip = newTrip();
        final Person ann = newPerson("Ann", "Export", null);
        ann.setBirthdate(LocalDate.now().minusYears(30).minusDays(1));
        assertTrue(DAO.getInstance().savePerson(ann));
        final Registration reg = register(trip, ann, Registration.Status.CONFIRMED, "sat");
        trip.getPeople().add(ann.getId());
        final Person pat = newPerson("Pat", "Pending", null);
        register(trip, pat, Registration.Status.PENDING, "fri");
        final Person rosterOnly = newPerson("Rory", "Roster", null);
        trip.getPeople().add(rosterOnly.getId());
        final CheckinCommands viewer = as(grant(PrivilegeCommands.TRIP_VIEW, trip.getId()));
        assertFalse(viewer.checkIn(trip.getId(), ann.getId().getValue()), "a viewer does not check people in");
        assertTrue(admin().checkIn(trip.getId(), ann.getId().getValue()));

        final Map<String, Object> row = viewer.exportRow(trip, ann.getId());
        assertEquals(row.get("name"), "Export, Ann");
        assertEquals(row.get("exportOpts"), "Admission Saturday only\nDiet Vegetarian\nStaff note secret staff note",
                "every answer, labels for choices, one per line, exactly as the page built it");
        assertTrue(((String) row.get("tripOpts")).startsWith("<ul><li><b>Admission</b> Saturday only</li>"));
        assertEquals(row.get("age"), 30);
        assertEquals(row.get("regType"), "Saturday only");
        assertEquals(row.get("ticket"), TicketCodes.grouped(reg.getTicketCode()));
        assertEquals(row.get("checkedIn"), "2027-03-12 09:14");
        assertEquals(row.get("registered"), "2027-02-10");
        assertEquals(row.get("status"), "Confirmed");
        assertEquals(row.get(CheckinCommands.questionKey(trip.getRegOptions().getFirst())), "Saturday only");
        assertEquals(row.get("q2"), "", "an unanswered typed question");
        assertEquals(row.get("total"), "", "no checkout priced this registration");
        assertEquals(row.get("code"), "");
        assertEquals(row.get("attestation"), "");

        // A checkout-priced row: its stored price, the code it used and the statements ticked.
        final org.paulsens.trip.model.PriceBreakdown paid = new org.paulsens.trip.model.PriceBreakdown(
                new org.paulsens.trip.model.PriceBreakdown.Inputs(30, null, List.of("sat"), "sat", "EARLY10",
                        List.of("sat")), List.of(), 54_00, "Saturday only", null);
        assertTrue(DAO.getInstance().saveRegistration(DAO.getInstance()
                .getRegistration(trip.getId(), ann.getId(), Cached.NO).orElseThrow().withPriceBreakdown(paid)
                .withAttestations(Map.of("sat", "I am staying at the hotel", "gone", "Old statement"))));
        final Map<String, Object> priced = viewer.exportRow(trip, ann.getId());
        assertEquals(priced.get("total"), "$54.00");
        assertEquals(priced.get("code"), "EARLY10");
        assertEquals(priced.get("paymentMethod"), "", "no payment registered this row");
        assertEquals(priced.get("paymentReference"), "");
        assertEquals(priced.get("paymentReceived"), "");

        // How it was paid (docs/checkout.md): an offline receipt's method, reference and date, structured.
        final org.paulsens.trip.model.Payment check = org.paulsens.trip.model.Payment.builder()
                .tripId(trip.getId()).payerId(ann.getId()).processorType(org.paulsens.trip.model.ProcessorType.OFFLINE)
                .captureId("CHECK 1042").capturedAt(LocalDate.of(2026, 10, 30).atStartOfDay())
                .status(org.paulsens.trip.model.Payment.Status.RECORDED).build();
        check.setOffline(new org.paulsens.trip.model.Payment.OfflineReceipt("CHECK", "1042",
                LocalDate.of(2026, 10, 30)));
        DAO.getInstance().createPayment(check);
        assertTrue(DAO.getInstance().saveRegistration(DAO.getInstance()
                .getRegistration(trip.getId(), ann.getId(), Cached.NO).orElseThrow()
                .withPaymentId(check.getPaymentId())));
        final Map<String, Object> byCheck = viewer.exportRow(trip, ann.getId());
        assertEquals(byCheck.get("paymentMethod"), "Check");
        assertEquals(byCheck.get("paymentReference"), "1042");
        assertEquals(byCheck.get("paymentReceived"), "2026-10-30");
        final String attestation = (String) priced.get("attestation");
        assertTrue(attestation.contains("Saturday only: I am staying at the hotel"), attestation);
        assertTrue(attestation.contains("gone: Old statement"), "a withdrawn choice shows its id: " + attestation);

        final Map<String, Object> blank = viewer.exportRow(trip, rosterOnly.getId());
        assertEquals(blank.get("status"), "Not Registered");
        assertEquals(blank.get("registered"), "");
        assertEquals(blank.get("age"), "");
        assertEquals(blank.get("exportOpts"), "");
        assertTrue(as(Person.Id.newInstance()).exportRow(trip, ann.getId()).isEmpty(), "not a trip viewer");
        assertTrue(viewer.exportRow(null, ann.getId()).isEmpty());

        assertEquals(viewer.typedQuestions(trip).size(), 2, "the two pick-one questions");
        assertEquals(viewer.exportPeople(trip, false), List.of(ann.getId(), rosterOnly.getId()));
        assertEquals(viewer.exportPeople(trip, true), List.of(ann.getId(), rosterOnly.getId(), pat.getId()));
    }
}
