package org.paulsens.trip.action;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.Family;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Passport;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripReminder;
import org.paulsens.trip.model.TripReminderPlan;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * A trip's messages end to end against the in-memory store ({@code docs/reminders.md}): the gate, the drafts,
 * the approval rule, who a message goes to, and the send with its once-only ledger. Each test works on its own
 * trip. The clock is fixed at 9 AM Pacific on 2027-03-03, the default {@code reminders.zone}.
 */
public class ReminderCommandsTest {
    private static final Instant NOW = Instant.parse("2027-03-03T17:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2027, 3, 3);
    private static final LocalDateTime START = LocalDateTime.of(2027, 6, 1, 8, 0);

    /** One mail the fake mailer was handed. */
    private record Mail(String from, String to, String replyTo, String subject, String body) {
    }

    private final List<Mail> outbox = new CopyOnWriteArrayList<>();
    private final List<String> pushes = new CopyOnWriteArrayList<>();
    private volatile boolean mailWorks = true;
    private String orgId;

    @BeforeClass
    public void seed() throws IOException {
        FakeData.initFakeData();
        FakeData.addFakeData();
        final Organization org = new OrgCommands(TestCallers::siteAdmin)
                .createOrganization("Reminders " + RandomData.genAlpha(8), "RMD", null);
        orgId = org.getId().getValue();
    }

    // ------------------------------------------------------------------ fixtures

    private ReminderSender sender() {
        return new ReminderSender(new ReminderRecipients(),
                this::fakeMail,
                (trip, reminder, to, title, text) -> pushes.add(to.getValue() + "|" + title + "|" + text),
                (trip, plan) -> "Trip Team <trips@example.com>", (trip, plan) -> "lead@example.com", () -> NOW);
    }

    private boolean fakeMail(final String from, final String to, final String replyTo, final String subject,
            final String body, final AuditActor who) {
        return mailWorks && outbox.add(new Mail(from, to, replyTo, subject, body));
    }

    private ReminderCommands bean(final Caller caller) {
        return new ReminderCommands(() -> caller, AuditCommands::new, this::sender, new ReminderRecipients(),
                () -> NOW);
    }

    private ReminderCommands admin() {
        return bean(TestCallers.siteAdmin());
    }

    private Trip newTrip() throws IOException {
        final Trip trip = Trip.builder().id(UUID.randomUUID().toString()).title("Rome & Assisi")
                .startDate(START).endDate(START.plusDays(9)).people(new ArrayList<>()).build();
        trip.setOrgId(orgId);
        assertTrue(DAO.getInstance().saveTrip(trip));
        return trip;
    }

    private static Person newPerson(final String first, final String email) throws IOException {
        final Person person = new Person();
        person.setFirst(first);
        person.setLast("Test");
        person.setEmail(email);
        assertTrue(DAO.getInstance().savePerson(person));
        return person;
    }

    private static void register(final Trip trip, final Person person, final Registration.Status status)
            throws IOException {
        assertTrue(DAO.getInstance().saveRegistration(
                new Registration(trip.getId(), person.getId(), START.minusDays(90), status, new HashMap<>())));
    }

    private static void ledger(final Person who, final Transaction.TransactionType type, final float amount,
            final String tripId) throws IOException {
        final Transaction tx = new Transaction(null, who.getId(), null, Transaction.Type.Tx, type, START, amount,
                "Test", "test row");
        assertTrue(DAO.getInstance().saveTransaction(tx));
        new BindingCommands().setBindings(new BindingCommands().key(who.getId().getValue(), tx.getTxId()),
                BindingType.TRANSACTION, BindingType.TRIP, List.of(tripId), true);
    }

    /** A custom message to everyone confirmed, dated {@code date}. */
    private static ReminderViews.Form form(final String name, final LocalDate date) {
        final ReminderViews.Form form = new ReminderViews.Form();
        form.setName(name);
        form.setAnchor("FIXED_DATE");
        form.setFixedDate(date);
        form.setSubject("About {{tripTitle}}");
        form.setBody("<p>Hi {{firstName}}, see <a href=\"{{tripLink}}\">the trip</a>.</p>");
        return form;
    }

    private String saved(final Trip trip, final ReminderViews.Form form) {
        assertTrue(admin().save(trip.getId(), form), form.getProblem());
        return form.getId();
    }

    private static TripReminder stored(final Trip trip, final String id) {
        return DAO.getInstance().getReminderPlan(trip.getId()).orElseThrow().reminder(id);
    }

    private static Person.Id grant(final String name, final String scope) {
        final Person.Id who = Person.Id.newInstance();
        final PrivilegeCommands priv = new PrivilegeCommands();
        assertTrue(priv.savePrivilege(priv.getOrCreate(name, scope, name).withNewPerson(who)));
        return who;
    }

    private List<Mail> mailTo(final String address) {
        return outbox.stream().filter(m -> m.to().equals(address)).toList();
    }

    // ------------------------------------------------------------------ the gate

    @Test
    public void onlyFacilitatorsManagersAndOrgAdminsGetIn() throws IOException {
        final Trip trip = newTrip();
        final Person facilitator = newPerson("Fay", "fay." + RandomData.genAlpha(6) + "@example.com");
        trip.addFacilitatorId(facilitator.getId());
        assertTrue(DAO.getInstance().saveTrip(trip));
        final Person.Id manager = grant(PrivilegeCommands.TRIP_MGR, trip.getId());
        final Person.Id finance = grant(PrivilegeCommands.TRIP_FIN_ADMIN, trip.getId());
        final Person.Id stranger = Person.Id.newInstance();

        assertTrue(admin().canManage(trip.getId()));
        assertTrue(bean(TestCallers.person(facilitator.getId())).canManage(trip.getId()));
        assertTrue(bean(TestCallers.person(manager)).canManage(trip.getId()));
        assertFalse(bean(TestCallers.person(finance)).canManage(trip.getId()), "Money roles do not write to travelers");
        assertFalse(bean(TestCallers.person(stranger)).canManage(trip.getId()));
        assertFalse(bean(Caller.forActor(AuditActor.from(null))).canManage(trip.getId()), "Nobody signed in");
        assertFalse(admin().canManage(null));
        assertFalse(admin().canManage(" "));
        assertFalse(admin().canManage(UUID.randomUUID().toString()), "Unknown trip");
    }

    @Test
    public void aStrangerReadsNothingAndWritesNothing() throws IOException {
        final Trip trip = newTrip();
        final String id = saved(trip, form("Hello", TODAY));
        final ReminderCommands stranger = bean(TestCallers.person(Person.Id.newInstance()));
        final String tripId = trip.getId();

        assertTrue(stranger.rows(tripId).isEmpty());
        assertTrue(stranger.history(tripId).isEmpty());
        assertTrue(stranger.automaticMails(tripId).isEmpty());
        assertTrue(stranger.recipientsOf(tripId, id).isEmpty());
        assertEquals(stranger.headline(tripId), "");
        assertEquals(stranger.subline(tripId), "");
        assertEquals(stranger.previewSubject(tripId, id), "");
        assertEquals(stranger.previewBody(tripId, id), "");
        assertEquals(stranger.previewPush(tripId, id), "");
        assertEquals(stranger.fromSeed(tripId), "");
        assertEquals(stranger.replyToSeed(tripId), "");
        assertNull(stranger.formFor(tripId, id).getId());
        assertFalse(stranger.save(tripId, form("Mine", TODAY)));
        assertFalse(stranger.approve(tripId, id));
        assertFalse(stranger.withdraw(tripId, id));
        assertFalse(stranger.sendNow(tripId, id));
        assertFalse(stranger.delete(tripId, id));
        assertFalse(stranger.addSuggested(tripId));
        assertFalse(stranger.sendTest(tripId, id, "x@example.com"));
        assertFalse(stranger.saveSender(tripId, "X", "x", "example.com", ""));
        assertFalse(stranger.clearSend(tripId, "SEND#x"));
        assertEquals(stored(trip, id).getStatus(), TripReminder.Status.DRAFT);
    }

    // ------------------------------------------------------------------ drafts

    @Test
    public void aNewTripHasNothingScheduled() throws IOException {
        final Trip trip = newTrip();
        assertTrue(admin().rows(trip.getId()).isEmpty());
        assertEquals(admin().headline(trip.getId()), "No messages yet");
        assertTrue(admin().subline(trip.getId()).startsWith("Nothing is scheduled"));
        assertTrue(DAO.getInstance().getReminderPlan(trip.getId()).isEmpty(), "Looking creates no row");
    }

    @Test
    public void suggestedMessagesArriveAsDraftsAndOnlyOnce() throws IOException {
        final Trip trip = newTrip();
        assertTrue(admin().addSuggested(trip.getId()));
        final TripReminderPlan plan = DAO.getInstance().getReminderPlan(trip.getId()).orElseThrow();
        assertEquals(plan.getReminders().size(), 6);
        for (final TripReminder reminder : plan.getReminders()) {
            assertEquals(reminder.getStatus(), TripReminder.Status.DRAFT, reminder.getName());
            assertFalse(reminder.isApprovedFor(trip));
            assertTrue(ReminderText.unknownTokens(reminder.getSubject() + reminder.getBody()).isEmpty());
        }
        assertEquals(admin().headline(trip.getId()), "6 need approval");
        assertTrue(admin().addSuggested(trip.getId()));
        assertEquals(DAO.getInstance().getReminderPlan(trip.getId()).orElseThrow().getReminders().size(), 6);

        // With no balance due date the three balance reminders have no date and cannot be approved.
        final List<ReminderViews.Row> rows = admin().rows(trip.getId());
        final List<ReminderViews.Row> undated = rows.stream().filter(r -> "nodate".equals(r.getStatusClass())).toList();
        assertEquals(undated.size(), 3);
        assertFalse(undated.get(0).isApprovable());
        assertFalse(admin().approve(trip.getId(), undated.get(0).getId()));
        assertEquals(rows.get(rows.size() - 1).getSendDateText(), "No date", "Undated rows sort last");
        assertEquals(rows.get(0).getName(), "Passport check", "Earliest send date first");
    }

    @Test
    public void saveRefusesWhatCouldNotBeSentAsWritten() throws IOException {
        final Trip trip = newTrip();
        final String tripId = trip.getId();
        final ReminderViews.Form noName = form(" ", TODAY);
        assertFalse(admin().save(tripId, noName));
        assertEquals(noName.getProblem(), "Give the message a name.");

        final ReminderViews.Form longName = form("x".repeat(81), TODAY);
        assertFalse(admin().save(tripId, longName));

        final ReminderViews.Form noDate = form("No date", null);
        assertFalse(admin().save(tripId, noDate));
        assertEquals(noDate.getProblem(), "Choose the date it is sent.");

        final ReminderViews.Form badAnchor = form("Bad", TODAY);
        badAnchor.setAnchor("WHENEVER");
        assertFalse(admin().save(tripId, badAnchor));

        final ReminderViews.Form badTiming = form("Bad", TODAY);
        badTiming.setAnchor("TRIP_START");
        badTiming.setTiming("SOMETIME");
        assertFalse(admin().save(tripId, badTiming));

        final ReminderViews.Form badDays = form("Bad", TODAY);
        badDays.setAnchor("TRIP_START");
        badDays.setDays(366);
        assertFalse(admin().save(tripId, badDays));

        final ReminderViews.Form badAudience = form("Bad", TODAY);
        badAudience.setAudience("EVERYONE_EVER");
        assertFalse(admin().save(tripId, badAudience));

        final ReminderViews.Form noSubject = form("Bad", TODAY);
        noSubject.setSubject("");
        assertFalse(admin().save(tripId, noSubject));

        final ReminderViews.Form longSubject = form("Bad", TODAY);
        longSubject.setSubject("x".repeat(201));
        assertFalse(admin().save(tripId, longSubject));

        final ReminderViews.Form emptyBody = form("Bad", TODAY);
        emptyBody.setBody("<p><br></p>");
        assertFalse(admin().save(tripId, emptyBody));
        assertEquals(emptyBody.getProblem(), "Write the message.");

        final ReminderViews.Form hugeBody = form("Bad", TODAY);
        hugeBody.setBody("x".repeat(50_001));
        assertFalse(admin().save(tripId, hugeBody));

        final ReminderViews.Form longPush = form("Bad", TODAY);
        longPush.setPushText("x".repeat(181));
        assertFalse(admin().save(tripId, longPush));

        final ReminderViews.Form typo = form("Typo", TODAY);
        typo.setBody("<p>Hi {{fristName}}</p>");
        assertFalse(admin().save(tripId, typo));
        assertEquals(typo.getProblem(), "Unknown placeholder {{fristName}}: use one from the list.");

        assertFalse(admin().save(tripId, null));
        assertTrue(DAO.getInstance().getReminderPlan(tripId).isEmpty(), "Nothing refused was stored");
    }

    @Test
    public void theBodyIsCleanedAndEncodedBracesAreRestored() throws IOException {
        final Trip trip = newTrip();
        final ReminderViews.Form form = form("Clean", TODAY);
        form.setBody("<p onclick=\"x()\">Pay <a href=\"%7B%7BpaymentLink%7D%7D\">here</a></p><script>bad()</script>");
        final String id = saved(trip, form);
        assertEquals(stored(trip, id).getBody(), "<p>Pay <a href=\"{{paymentLink}}\">here</a></p>");
    }

    @Test
    public void editingRoundTripsThroughTheFormAndKeepsTheKind() throws IOException {
        final Trip trip = newTrip();
        assertTrue(admin().addSuggested(trip.getId()));
        final TripReminder passport = DAO.getInstance().getReminderPlan(trip.getId()).orElseThrow().getReminders()
                .stream().filter(r -> r.getKind() == TripReminder.Kind.PASSPORT).findFirst().orElseThrow();
        final ReminderViews.Form form = admin().formFor(trip.getId(), passport.getId());
        assertEquals(form.getTiming(), "BEFORE");
        assertEquals(form.getDays(), 90);
        assertFalse(form.isCustom());
        form.setTiming("AFTER");
        form.setDays(2);
        form.setKind("CUSTOM");
        form.setAudience("PENDING");
        assertTrue(admin().save(trip.getId(), form));
        final TripReminder edited = stored(trip, passport.getId());
        assertEquals(edited.getOffsetDays(), 2);
        assertEquals(edited.getKind(), TripReminder.Kind.PASSPORT, "A posted kind cannot change what it is");
        assertEquals(edited.getAudience(), TripReminder.Audience.PASSPORT_ISSUE);

        form.setTiming("ON");
        assertTrue(admin().save(trip.getId(), form));
        assertEquals(admin().formFor(trip.getId(), passport.getId()).getTiming(), "ON");

        final ReminderViews.Form gone = admin().newForm();
        gone.setId("no-such-id");
        gone.setName("Gone");
        gone.setSubject("s");
        gone.setBody("<p>b</p>");
        assertFalse(admin().save(trip.getId(), gone));
        assertEquals(gone.getProblem(), "This message no longer exists: reload the page.");
        assertNull(admin().formFor(trip.getId(), "no-such-id").getId());
        assertEquals(admin().getTokens(), ReminderText.TOKENS);
    }

    @Test
    public void theDialogsMenusAndTheSingleRowLookup() throws IOException {
        final Trip trip = newTrip();
        assertEquals(admin().anchorChoices().size(), TripReminder.Anchor.values().length);
        assertEquals(admin().anchorChoices().get(0).getId(), "TRIP_START");
        assertEquals(admin().audienceChoices().size(), TripReminder.Audience.values().length);
        assertEquals(admin().audienceChoices().get(0).getLabel(), "Everyone confirmed on the trip");

        final ReminderViews.Form custom = admin().newForm();
        assertTrue(custom.isCustom());
        assertEquals(admin().audienceLabel(custom), "", "A custom message chooses its audience");
        assertEquals(admin().audienceLabel(null), "");
        custom.setKind("PASSPORT");
        assertEquals(admin().audienceLabel(custom), "Confirmed travelers with a missing or expiring passport");

        final String id = saved(trip, form("Looked up", TODAY));
        assertEquals(admin().row(trip.getId(), id).getName(), "Looked up");
        assertNull(admin().row(trip.getId(), "no-such-id"));
        assertNotNull(new ReminderCommands(TestCallers::siteAdmin), "The page's own wiring builds");
        assertNotNull(new ReminderCommands());
    }

    @Test
    public void aTripHoldsAtMostFortyMessages() throws IOException {
        final Trip trip = newTrip();
        final List<TripReminder> many = new ArrayList<>();
        for (int i = 0; i < ReminderCommands.MAX_REMINDERS; i++) {
            many.add(TripReminder.builder().id("r" + i).name("M" + i).subject("s").body("b").build());
        }
        assertTrue(DAO.getInstance().saveReminderPlan(
                TripReminderPlan.builder().tripId(trip.getId()).orgId(orgId).reminders(many).build()));
        final ReminderViews.Form oneMore = form("One more", TODAY);
        assertFalse(admin().save(trip.getId(), oneMore));
        assertTrue(oneMore.getProblem().startsWith("A trip holds at most"));
        assertTrue(admin().addSuggested(trip.getId()), "Nothing fits, nothing added, no error");
    }

    // ------------------------------------------------------------------ the approval rule

    @Test
    public void nothingUnapprovedIsEverSent() throws IOException {
        final Trip trip = newTrip();
        final Person ann = newPerson("Ann", "ann." + RandomData.genAlpha(6) + "@example.com");
        register(trip, ann, Registration.Status.CONFIRMED);
        final String id = saved(trip, form("Draft", TODAY));

        assertFalse(admin().sendNow(trip.getId(), id), "A draft cannot be sent by hand");
        final TripReminderPlan plan = DAO.getInstance().getReminderPlan(trip.getId()).orElseThrow();
        final ReminderSender.Summary summary =
                sender().sendAll(trip, plan, plan.reminder(id), TODAY, TODAY, false, AuditActor.system());
        assertTrue(summary.isRefused(), "The sender itself refuses a draft");
        assertTrue(mailTo(ann.getEmail()).isEmpty());
        assertTrue(DAO.getInstance().getReminderSends(trip.getId()).isEmpty());
    }

    @Test
    public void anEditAfterApprovalReturnsItToDraft() throws IOException {
        final Trip trip = newTrip();
        final Person ann = newPerson("Ann", "ann." + RandomData.genAlpha(6) + "@example.com");
        register(trip, ann, Registration.Status.CONFIRMED);
        final String id = saved(trip, form("Edited", TODAY));
        assertTrue(admin().approve(trip.getId(), id));
        assertTrue(stored(trip, id).isApprovedFor(trip));
        assertEquals(stored(trip, id).getApprovedBy(), TestCallers.SITE_ADMIN_ID);
        assertEquals(admin().rows(trip.getId()).get(0).getStatusLabel(), "Approved");
        assertTrue(admin().rows(trip.getId()).get(0).isSendable());

        final ReminderViews.Form edit = admin().formFor(trip.getId(), id);
        edit.setBody("<p>Different words for {{firstName}}.</p>");
        assertTrue(admin().save(trip.getId(), edit));
        assertEquals(stored(trip, id).getStatus(), TripReminder.Status.DRAFT);
        assertEquals(admin().rows(trip.getId()).get(0).getStatusLabel(), "Draft");
        assertFalse(admin().sendNow(trip.getId(), id));
        assertTrue(mailTo(ann.getEmail()).isEmpty());
    }

    @Test
    public void aMovedTripDateStopsTheSendUntilApprovedAgain() throws IOException {
        final Trip trip = newTrip();
        final Person ann = newPerson("Ann", "ann." + RandomData.genAlpha(6) + "@example.com");
        register(trip, ann, Registration.Status.CONFIRMED);
        final ReminderViews.Form form = form("Ninety days", null);
        form.setAnchor("TRIP_START");
        form.setTiming("BEFORE");
        form.setDays(90);
        final String id = saved(trip, form);
        assertEquals(stored(trip, id).sendDate(trip), TODAY);
        assertTrue(admin().approve(trip.getId(), id));

        final Trip moved = DAO.getInstance().getTrip(trip.getId(), org.paulsens.trip.cache.Cached.NO).orElseThrow();
        moved.setStartDate(START.minusDays(1));
        assertTrue(DAO.getInstance().saveTrip(moved));
        final ReminderViews.Row row = admin().rows(trip.getId()).get(0);
        assertEquals(row.getStatusLabel(), "Changed, approve again");
        assertFalse(row.isApproved());
        assertTrue(row.isApprovable());
        assertEquals(admin().headline(trip.getId()), "1 needs approval");
        assertFalse(admin().sendNow(trip.getId(), id));
        assertTrue(mailTo(ann.getEmail()).isEmpty());

        assertTrue(admin().approve(trip.getId(), id));
        assertTrue(admin().sendNow(trip.getId(), id), "Yesterday's date, approved: sendable by hand");
        assertEquals(mailTo(ann.getEmail()).size(), 1);
    }

    @Test
    public void withdrawAndDeleteStopTheMessage() throws IOException {
        final Trip trip = newTrip();
        final String id = saved(trip, form("Stop", TODAY.plusDays(5)));
        assertTrue(admin().approve(trip.getId(), id));
        assertTrue(admin().headline(trip.getId()).startsWith("Next: "));
        assertTrue(admin().subline(trip.getId()).startsWith("1 of 1 scheduled messages approved"));
        assertFalse(admin().sendNow(trip.getId(), id), "Not before its date");

        assertTrue(admin().withdraw(trip.getId(), id));
        assertEquals(stored(trip, id).getStatus(), TripReminder.Status.DRAFT);
        assertTrue(admin().delete(trip.getId(), id));
        assertNull(stored(trip, id));
        assertFalse(admin().delete(trip.getId(), id));
        assertFalse(admin().withdraw(trip.getId(), id));
        assertFalse(admin().approve(trip.getId(), id));
        assertFalse(admin().sendTest(trip.getId(), id, "x@example.com"));
    }

    // ------------------------------------------------------------------ who it goes to

    @Test
    public void aBalanceReminderGoesOnlyToConfirmedTravelersWhoOwe() throws IOException {
        final Trip trip = newTrip();
        final String tag = RandomData.genAlpha(6);
        final Person owes = newPerson("Owen", "owen." + tag + "@example.com");
        final Person paid = newPerson("Paige", "paige." + tag + "@example.com");
        final Person pending = newPerson("Penny", "penny." + tag + "@example.com");
        register(trip, owes, Registration.Status.CONFIRMED);
        register(trip, paid, Registration.Status.CONFIRMED);
        register(trip, pending, Registration.Status.PENDING);
        ledger(owes, Transaction.TransactionType.Bill, -500f, trip.getId());
        ledger(owes, Transaction.TransactionType.Payment, 250f, trip.getId());
        ledger(paid, Transaction.TransactionType.Bill, -500f, trip.getId());
        ledger(paid, Transaction.TransactionType.Payment, 500f, trip.getId());
        ledger(pending, Transaction.TransactionType.Bill, -500f, trip.getId());

        final TripReminder balance = TripReminder.builder().id("bal").kind(TripReminder.Kind.BALANCE_DUE)
                .anchor(TripReminder.Anchor.FIXED_DATE).fixedDate(TODAY).subject("s").body("b").build();
        final List<ReminderRecipients.Target> targets = new ReminderRecipients().targets(trip, balance, TODAY);
        assertEquals(targets.size(), 1);
        assertEquals(targets.get(0).getPersonId(), owes.getId().getValue());
        assertEquals(targets.get(0).getReason(), "Owes $250.00");
        assertEquals(targets.get(0).facts().get(ReminderText.BALANCE_DUE), "$250.00");
        assertEquals(targets.get(0).getAddresses(), owes.getEmail());

        final TripReminder toPending = TripReminder.builder().id("p").audience(TripReminder.Audience.PENDING).build();
        final List<ReminderRecipients.Target> waiting = new ReminderRecipients().targets(trip, toPending, TODAY);
        assertEquals(waiting.size(), 1);
        assertEquals(waiting.get(0).getReason(), "Awaiting approval");
        assertTrue(new ReminderRecipients().targets(null, balance, TODAY).isEmpty());
        assertTrue(new ReminderRecipients().targets(trip, null, TODAY).isEmpty());
    }

    @Test
    public void documentAndPassportAudiencesFollowTheirConditions() throws IOException {
        final Trip trip = newTrip();
        final String tag = RandomData.genAlpha(6);
        final Person signed = newPerson("Sig", "sig." + tag + "@example.com");
        final Person unsigned = newPerson("Una", "una." + tag + "@example.com");
        unsigned.setPassport(new Passport("P123", "US", START.toLocalDate().plusYears(5), null, null));
        assertTrue(DAO.getInstance().savePerson(unsigned));
        register(trip, signed, Registration.Status.CONFIRMED);
        register(trip, unsigned, Registration.Status.CONFIRMED);
        final ReminderRecipients recipients = new ReminderRecipients((tripId, id) -> 0L,
                (t, id) -> id.equals(unsigned.getId()) ? List.of("Release", "Medical") : List.of(),
                person -> List.of(), t -> "https://org.example");

        final TripReminder docs = TripReminder.builder().id("d").kind(TripReminder.Kind.WAIVER_UNSIGNED).build();
        final List<ReminderRecipients.Target> needSign = recipients.targets(trip, docs, TODAY);
        assertEquals(needSign.size(), 1);
        assertEquals(needSign.get(0).getReason(), "Not signed: Release, Medical");
        assertEquals(needSign.get(0).facts().get(ReminderText.DOCUMENT_LIST), "Release, Medical");
        assertEquals(needSign.get(0).facts().get(ReminderText.DOCUMENTS_LINK),
                "https://org.example/trip/joinTrip.jsf?trip=" + trip.getId());
        assertEquals(needSign.get(0).facts().get(ReminderText.DAYS_UNTIL), "90");
        assertEquals(needSign.get(0).facts().get(ReminderText.TRIP_DATES), "Jun 1, 2027 to Jun 10, 2027");

        final TripReminder passport = TripReminder.builder().id("p").kind(TripReminder.Kind.PASSPORT).build();
        final List<ReminderRecipients.Target> noPassport = recipients.targets(trip, passport, TODAY);
        assertEquals(noPassport.size(), 1, "Una's passport outlasts the trip; Sig has none");
        assertEquals(noPassport.get(0).getPersonId(), signed.getId().getValue());
        assertEquals(noPassport.get(0).getReason(), "We do not have a passport on file for you.");

        final TripReminder all = TripReminder.builder().id("a").build();
        assertEquals(recipients.targets(trip, all, TODAY).size(), 2);
        assertEquals(recipients.targets(trip, all, TODAY).get(0).getReason(), "Confirmed");
    }

    @Test
    public void passportIssuesAreMissingUndatedOrExpiringTooSoon() {
        final Trip trip = Trip.builder().id("t").startDate(START).endDate(START.plusDays(9)).build();
        final LocalDate back = START.plusDays(9).toLocalDate();
        assertNotNull(ReminderRecipients.passportIssue(null, trip));
        assertNotNull(ReminderRecipients.passportIssue(new Passport(" ", "US", back.plusYears(1), null, null), trip));
        assertEquals(ReminderRecipients.passportIssue(new Passport("P1", "US", null, null, null), trip),
                "Your passport on file has no expiration date.");
        assertEquals(ReminderRecipients.passportIssue(new Passport("P1", "US", back.plusMonths(6).minusDays(1), null,
                null), trip), "Your passport expires Dec 9, 2027, less than 6 months after the trip ends.");
        assertNull(ReminderRecipients.passportIssue(new Passport("P1", "US", back.plusMonths(6), null, null), trip));
        final Trip openEnded = Trip.builder().id("t").startDate(START).build();
        openEnded.setEndDate(null);
        assertNull(ReminderRecipients.passportIssue(new Passport("P1", "US", back, null, null), openEnded));
    }

    @Test
    public void aTravelerWithoutAMailboxIsReachedThroughTheirFamilyManager() throws IOException {
        final Trip trip = newTrip();
        final String tag = RandomData.genAlpha(6);
        final Person manager = newPerson("Mona", "mona." + tag + "@example.com");
        final Person child = newPerson("Kit", null);
        final Person alone = newPerson("Sol", null);
        final Family family = Family.builder().id(Family.Id.from("fam-" + RandomData.genAlpha(8)))
                .createdBy(manager.getId())
                .managerIds(new ArrayList<>(List.of(manager.getId())))
                .memberIds(new ArrayList<>(List.of(manager.getId(), child.getId()))).build();
        assertTrue(DAO.getInstance().saveFamily(family));
        child.setFamilyId(family.getId());
        assertTrue(DAO.getInstance().savePerson(child));
        register(trip, child, Registration.Status.CONFIRMED);
        register(trip, alone, Registration.Status.CONFIRMED);

        final String id = saved(trip, form("Family", TODAY));
        final List<ReminderRecipients.Target> targets = admin().recipientsOf(trip.getId(), id);
        assertEquals(targets.size(), 2);
        assertEquals(targets.get(0).getName(), "Kit Test");
        assertEquals(targets.get(0).getAddresses(), manager.getEmail() + " (family manager)");
        assertEquals(targets.get(1).getAddresses(), "(no email address)");
        assertEquals(admin().previewNote(trip.getId(), id), "Shown as Kit Test would receive it today (1 of 2).");
        final List<ReminderViews.Recipient> shown = admin().recipientRows(trip.getId(), id);
        assertEquals(shown.size(), 2);
        assertEquals(shown.get(0).getName(), "Kit Test");
        assertEquals(shown.get(0).getReason(), "Confirmed");
        assertEquals(shown.get(1).getAddresses(), "(no email address)");

        assertTrue(admin().approve(trip.getId(), id));
        assertTrue(admin().sendNow(trip.getId(), id));
        final List<Mail> toManager = mailTo(manager.getEmail());
        assertEquals(toManager.size(), 1);
        assertTrue(toManager.get(0).body().contains("Hi Mona,"), "The manager is greeted, about the member");
        final List<ReminderViews.HistoryRow> history = admin().history(trip.getId());
        assertEquals(history.size(), 2);
        assertEquals(history.stream().filter(h -> h.getOutcomeLabel().equals("No email address")).count(), 1);
        assertEquals(history.stream().filter(h -> h.getOutcomeLabel().equals("Sent")).count(), 1);
    }

    // ------------------------------------------------------------------ sending

    @Test
    public void sendNowMailsEachTravelerOnceAndRecordsIt() throws IOException {
        final Trip trip = newTrip();
        final String tag = RandomData.genAlpha(6);
        final Person ann = newPerson("Ann", "ann." + tag + "@example.com");
        final Person bob = newPerson("Bob", "bob." + tag + "@example.com");
        register(trip, ann, Registration.Status.CONFIRMED);
        register(trip, bob, Registration.Status.CONFIRMED);
        final ReminderViews.Form form = form("Hello", TODAY);
        form.setPushText("{{tripTitle}} starts in {{daysUntilDeparture}} days");
        final String id = saved(trip, form);
        assertEquals(admin().rows(trip.getId()).get(0).getChannel(), "Email and app notification");
        assertTrue(admin().approve(trip.getId(), id));
        assertTrue(admin().sendNow(trip.getId(), id));

        final Mail toAnn = mailTo(ann.getEmail()).get(0);
        assertEquals(toAnn.subject(), "About Rome & Assisi", "The subject is plain text");
        assertTrue(toAnn.body().contains("Hi Ann,"));
        assertTrue(toAnn.body().contains("/trip/tripDetails.jsf?trip=" + trip.getId()));
        assertEquals(toAnn.from(), "Trip Team <trips@example.com>");
        assertEquals(toAnn.replyTo(), "lead@example.com");
        assertEquals(mailTo(bob.getEmail()).size(), 1);
        assertTrue(pushes.contains(ann.getId().getValue() + "|About Rome & Assisi|Rome & Assisi starts in 90 days"));

        // A second Send now, a second server, a scheduler run for the same date: nobody is mailed again.
        final TripReminderPlan plan = DAO.getInstance().getReminderPlan(trip.getId()).orElseThrow();
        final ReminderSender.Summary again =
                sender().sendAll(trip, plan, plan.reminder(id), TODAY, TODAY, false, AuditActor.system());
        assertEquals(again.sent(), 0);
        assertEquals(again.alreadySent(), 2);
        assertEquals(again.text(), "Sent to 0. 2 already sent earlier.");
        assertEquals(mailTo(ann.getEmail()).size(), 1);

        final ReminderViews.Row row = admin().rows(trip.getId()).get(0);
        assertEquals(row.getStatusLabel(), "Sent");
        assertEquals(row.getStatusNote(), "Sent to 2 people.");
        assertFalse(row.isSendable());
        final ReminderViews.HistoryRow first = admin().history(trip.getId()).get(0);
        assertEquals(first.getHow(), "Sent by hand");
        assertEquals(first.getReminderName(), "Hello");
        assertTrue(first.isPushed());
        assertFalse(first.isClearable());
        assertFalse(admin().clearSend(trip.getId(), first.getKey()), "A sent row can never be cleared");
        assertEquals(admin().headline(trip.getId()), "All sent");
    }

    @Test
    public void aFailedSendIsRecordedNotRetriedUntilTheFacilitatorClearsIt() throws IOException {
        final Trip trip = newTrip();
        final Person ann = newPerson("Ann", "ann." + RandomData.genAlpha(6) + "@example.com");
        register(trip, ann, Registration.Status.CONFIRMED);
        final String id = saved(trip, form("Flaky", TODAY));
        assertTrue(admin().approve(trip.getId(), id));
        final TripReminderPlan plan = DAO.getInstance().getReminderPlan(trip.getId()).orElseThrow();

        mailWorks = false;
        final ReminderSender.Summary failed;
        try {
            failed = sender().sendAll(trip, plan, plan.reminder(id), TODAY, TODAY, false, AuditActor.system());
        } finally {
            mailWorks = true;
        }
        assertEquals(failed.failed(), 1);
        assertEquals(failed.text(), "Sent to 0. 1 failed.");
        assertEquals(sender().sendAll(trip, plan, plan.reminder(id), TODAY, TODAY, false, AuditActor.system())
                .alreadySent(), 1, "Not retried by itself");
        assertTrue(mailTo(ann.getEmail()).isEmpty());

        final ReminderViews.HistoryRow row = admin().history(trip.getId()).get(0);
        assertEquals(row.getOutcomeLabel(), "Failed");
        assertTrue(row.isClearable());
        assertFalse(admin().clearSend(trip.getId(), "SEND#nope"));
        assertTrue(admin().clearSend(trip.getId(), row.getKey()));
        assertTrue(admin().sendNow(trip.getId(), id));
        assertEquals(mailTo(ann.getEmail()).size(), 1);
    }

    @Test
    public void theLiveSenderRunsEndToEndInLocalMode() throws IOException {
        // The real wiring: local mode's mail bean sends nothing and answers success, push is switched off, and
        // the From is the one the trip inherits. What is asserted is the ledger, the only observable here.
        final Trip trip = newTrip();
        final Person ann = newPerson("Ann", "ann." + RandomData.genAlpha(6) + "@example.com");
        register(trip, ann, Registration.Status.CONFIRMED);
        final ReminderViews.Form form = form("Live", TODAY);
        form.setPushText("Ping from {{tripTitle}}");
        final String id = saved(trip, form);
        assertTrue(admin().approve(trip.getId(), id));
        final TripReminderPlan plan = DAO.getInstance().getReminderPlan(trip.getId()).orElseThrow();
        assertNotNull(ReminderSender.effectiveFrom(trip, plan), "Inherited from the organization or the site");
        assertNull(ReminderSender.effectiveReplyTo(trip, plan), "No facilitators, no Reply-To");

        final ReminderSender.Summary summary =
                new ReminderSender().sendAll(trip, plan, plan.reminder(id), TODAY, TODAY, false, AuditActor.system());
        assertEquals(summary.sent(), 1);
        assertFalse(summary.isRefused());
        assertEquals(DAO.getInstance().getReminderSends(trip.getId()).size(), 1);
        assertTrue(DAO.getInstance().getReminderSends(trip.getId()).get(0).isPushed());

        final ReminderSender forTest = ReminderSender.forTest((f, to, r, s, b, who) -> true, () -> NOW);
        assertEquals(forTest.sendAll(trip, plan, plan.reminder(id), TODAY, TODAY, false, AuditActor.system())
                .alreadySent(), 1);
    }

    @Test
    public void aMailServiceThatThrowsIsAFailedSendNotACrash() throws IOException {
        final Trip trip = newTrip();
        final Person ann = newPerson("Ann", "ann." + RandomData.genAlpha(6) + "@example.com");
        register(trip, ann, Registration.Status.CONFIRMED);
        final String id = saved(trip, form("Boom", TODAY));
        assertTrue(admin().approve(trip.getId(), id));
        final TripReminderPlan plan = DAO.getInstance().getReminderPlan(trip.getId()).orElseThrow();
        final ReminderSender throwing = ReminderSender.forTest((f, to, r, s, b, who) -> {
            throw new IllegalStateException("mail service down");
        }, () -> NOW);
        final ReminderSender.Summary summary =
                throwing.sendAll(trip, plan, plan.reminder(id), TODAY, TODAY, false, AuditActor.system());
        assertEquals(summary.failed(), 1);
        // The claim was written before the send, so the traveler is not mailed by a later run either.
        assertEquals(sender().sendAll(trip, plan, plan.reminder(id), TODAY, TODAY, false, AuditActor.system())
                .alreadySent(), 1);
        final ReminderSender.Summary refused = sender().sendAll(trip, plan, plan.reminder(id).asDraft(), TODAY,
                TODAY, false, AuditActor.system());
        assertEquals(refused.text(), "This message is not approved as it stands, so it was not sent.");
    }

    @Test
    public void aRunThatMatchesNobodyIsRecordedAsRun() throws IOException {
        final Trip trip = newTrip();
        final String id = saved(trip, form("Nobody", TODAY));
        assertTrue(admin().approve(trip.getId(), id));
        assertEquals(admin().previewNote(trip.getId(), id),
                "Nobody would receive this today, so the preview shows sample values.");
        assertEquals(admin().previewSubject(trip.getId(), id), "About Sample Trip");
        assertTrue(admin().previewBody(trip.getId(), id).contains("Hi Maria,"));
        assertEquals(admin().previewPush(trip.getId(), id), "");
        assertTrue(admin().sendNow(trip.getId(), id));
        final List<ReminderViews.HistoryRow> history = admin().history(trip.getId());
        assertEquals(history.size(), 1);
        assertEquals(history.get(0).getOutcomeLabel(), "Nobody needed it");
        assertEquals(admin().rows(trip.getId()).get(0).getStatusNote(), "Sent to 0 people.");
    }

    @Test
    public void anApprovedMessageWhoseDatePassedUnsentIsMissed() throws IOException {
        final Trip trip = newTrip();
        final String id = saved(trip, form("Late", TODAY.minusDays(3)));
        assertTrue(admin().rows(trip.getId()).get(0).getStatusNote().contains("its date has passed"),
                "A draft dated in the past says so before anyone approves it");
        assertTrue(admin().approve(trip.getId(), id));
        final ReminderViews.Row row = admin().rows(trip.getId()).get(0);
        assertEquals(row.getStatusLabel(), "Missed");
        assertTrue(row.isSendable(), "Only the facilitator can still send it");
        assertTrue(row.getStatusNote().contains("Approved by "));
    }

    @Test
    public void theSenderRefusesWithoutAFromOrAMatchingDate() throws IOException {
        final Trip trip = newTrip();
        final String id = saved(trip, form("Guard", TODAY));
        assertTrue(admin().approve(trip.getId(), id));
        final TripReminderPlan plan = DAO.getInstance().getReminderPlan(trip.getId()).orElseThrow();
        final ReminderSender noFrom = new ReminderSender(new ReminderRecipients(),
                (from, to, replyTo, subject, body, who) -> true, (t, r, to, title, text) -> { },
                (t, p) -> " ", (t, p) -> null, () -> NOW);
        assertTrue(noFrom.sendAll(trip, plan, plan.reminder(id), TODAY, TODAY, true, AuditActor.system()).isRefused());
        assertTrue(sender().sendAll(trip, plan, plan.reminder(id), TODAY.plusDays(1), TODAY, true,
                AuditActor.system()).isRefused(), "Only its own send date");
        assertTrue(sender().sendAll(null, plan, plan.reminder(id), TODAY, TODAY, true, AuditActor.system())
                .isRefused());
        assertEquals(new ReminderSender.Summary(1, 0, 1, 0, null).text(), "Sent to 1. 1 has no email address.");
        assertEquals(new ReminderSender.Summary(1, 0, 2, 0, null).text(), "Sent to 1. 2 have no email address.");
    }

    // ------------------------------------------------------------------ sender, automatic mail

    @Test
    public void theSenderIsCheckedAgainstTheOrganizationsDomains() throws IOException {
        final Trip trip = newTrip();
        final String tripId = trip.getId();
        assertFalse(admin().saveSender(tripId, "Team", "trips", "not-a-verified-domain.example", ""));
        final List<String> domains = new OrgCommands(TestCallers::siteAdmin).mailDomainsForTrip(trip);
        assertFalse(domains.isEmpty(), "Local mode offers its fake sending domains");
        assertFalse(admin().saveSender(tripId, "Team", "trips", domains.get(0), "not an address"));
        assertTrue(admin().saveSender(tripId, "Team", "trips", domains.get(0), "lead@example.com"));
        assertEquals(admin().fromSeed(tripId), "Team <trips@" + domains.get(0) + ">");
        assertEquals(admin().replyToSeed(tripId), "lead@example.com");
        assertTrue(admin().saveSender(tripId, "Team", "trips", domains.get(0), " "));
        assertEquals(admin().replyToSeed(tripId), "", "No facilitators and no Reply-To: replies go to the From");
        assertEquals(admin().myEmail(), "");
        assertFalse(admin().isSendingOn(), "Ships dark");
    }

    @Test
    public void theAutomaticMailListNamesWhatTheTripAlreadySends() throws IOException {
        final Trip trip = newTrip();
        final List<ReminderViews.AutoMail> mails = admin().automaticMails(trip.getId());
        final List<String> names = mails.stream().map(ReminderViews.AutoMail::getName).toList();
        assertTrue(names.contains("Registration received"), names.toString());
        assertTrue(names.contains("Registration approved"));
        assertFalse(names.contains("Checkout confirmation"), "Priced checkout is off on this trip");
        for (final ReminderViews.AutoMail mail : mails) {
            assertFalse(mail.getTrigger().isBlank());
            assertFalse(mail.getSubject().isBlank());
            assertEquals(mail.getWording(), "Standard wording");
        }
    }

    @Test
    public void aTestSendNeedsAnAddress() throws IOException {
        final Trip trip = newTrip();
        final String id = saved(trip, form("Test me", TODAY));
        assertFalse(admin().sendTest(trip.getId(), id, "not-an-address"));
        // Local mode sends nothing and answers success, so the page path is exercised without SES.
        assertTrue(admin().sendTest(trip.getId(), id, "me@example.com"));
        assertTrue(DAO.getInstance().getReminderSends(trip.getId()).isEmpty(), "A test is not part of the history");
        assertEquals(stored(trip, id).getStatus(), TripReminder.Status.DRAFT, "Testing approves nothing");
    }
}
