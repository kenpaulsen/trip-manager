package org.paulsens.trip.reminder;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.mockito.Mockito;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.action.ReminderSender;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.cache.NoopCacheClient;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.ReminderSend;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripReminder;
import org.paulsens.trip.model.TripReminderPlan;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

/**
 * The daily run with a fixed clock ({@code docs/reminders.md}): each kind of reminder fires once on its date,
 * a draft never fires, and several servers (or a server with no usable cache) still send once. The clock is
 * 9 AM Pacific on 2027-03-03; every test's trip is its own, and the store is shared with other test classes,
 * so assertions look only at mail to this test's own addresses.
 */
public class ReminderSchedulerTest {
    private static final Instant NOW = Instant.parse("2027-03-03T17:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2027, 3, 3);
    private static final LocalDateTime START = LocalDateTime.of(2027, 6, 1, 8, 0);
    private static final Person.Id APPROVER = Person.Id.from("approver");

    private final List<String> mailedTo = new CopyOnWriteArrayList<>();
    private ConfigCommands enabled;

    @BeforeClass
    public void seed() {
        FakeData.initFakeData();
        FakeData.addFakeData();
    }

    @BeforeMethod
    public void setUp() {
        mailedTo.clear();
        enabled = new EnabledConfig();
    }

    private static class EnabledConfig extends ConfigCommands {
        @Override
        public boolean getBoolean(final String name, final boolean defaultValue) {
            return "reminders.enabled".equals(name) || defaultValue;
        }
    }

    private ReminderSender sender() {
        return ReminderSender.forTest((from, to, replyTo, subject, body, who) -> mailedTo.add(to), () -> NOW);
    }

    private ReminderScheduler scheduler(final ConfigCommands config) {
        return ReminderScheduler.forTest(new InMemoryCacheClient(), config, sender(), () -> NOW);
    }

    private static Trip newTrip() throws IOException {
        final Trip trip = Trip.builder().id(UUID.randomUUID().toString()).title("Scheduled trip")
                .startDate(START).endDate(START.plusDays(9)).people(new ArrayList<>()).build();
        trip.setOrgId(FakeData.CFPW_ORG_ID);
        assertTrue(DAO.getInstance().saveTrip(trip));
        return trip;
    }

    /** A confirmed traveler with their own address, which is returned. */
    private static String traveler(final Trip trip) throws IOException {
        final Person person = new Person();
        person.setFirst("Tess");
        person.setLast("Scheduler");
        person.setEmail("tess." + RandomData.genAlpha(10) + "@example.com");
        assertTrue(DAO.getInstance().savePerson(person));
        assertTrue(DAO.getInstance().saveRegistration(new Registration(trip.getId(), person.getId(),
                START.minusDays(90), Registration.Status.CONFIRMED, new HashMap<>())));
        return person.getEmail();
    }

    /** A message to everyone confirmed, dated {@code date}; approved for the trip unless {@code draft}. */
    private static TripReminder reminder(final Trip trip, final TripReminder.Kind kind, final LocalDate date,
            final boolean draft) {
        final TripReminder reminder = TripReminder.builder().id(UUID.randomUUID().toString()).kind(kind)
                .name(kind.getLabel()).anchor(TripReminder.Anchor.FIXED_DATE).fixedDate(date)
                .subject("About {{tripTitle}}").body("<p>Hi {{firstName}}</p>").build();
        return draft ? reminder : reminder.approved(trip, APPROVER, NOW);
    }

    private static void plan(final Trip trip, final TripReminder... reminders) throws IOException {
        assertTrue(DAO.getInstance().saveReminderPlan(TripReminderPlan.builder().tripId(trip.getId())
                .orgId(trip.getOrgId()).reminders(List.of(reminders)).build()));
    }

    private static List<Trip> tripIndexDown(final LocalDateTime endedAfter) {
        throw new IllegalStateException("trip index down");
    }

    private long mails(final String address) {
        return mailedTo.stream().filter(address::equals).count();
    }

    // ------------------------------------------------------------------ what fires

    @Test
    public void anApprovedMessageFiresOnceOnItsDate() throws IOException {
        final Trip trip = newTrip();
        final String tess = traveler(trip);
        plan(trip, reminder(trip, TripReminder.Kind.COUNTDOWN, TODAY, false));
        final ReminderScheduler scheduler = scheduler(enabled);

        scheduler.runFor(TODAY.minusDays(1));
        assertEquals(mails(tess), 0, "Not before its date");
        scheduler.runFor(TODAY);
        assertEquals(mails(tess), 1);
        scheduler.runFor(TODAY);
        scheduler.runFor(TODAY.plusDays(1));
        scheduler.runFor(TODAY.plusDays(2));
        assertEquals(mails(tess), 1, "Once, however often the run repeats");
        final List<ReminderSend> history = DAO.getInstance().getReminderSends(trip.getId());
        assertEquals(history.size(), 1);
        assertEquals(history.get(0).getOutcome(), ReminderSend.Outcome.SENT);
        assertFalse(history.get(0).isManual());
    }

    @Test
    public void eachKindFiresOnItsOwnDate() throws IOException {
        final Trip trip = newTrip();
        trip.getCheckout().setDepositCents(100_00L);
        trip.getCheckout().setBalanceDueOn(TODAY.plusDays(14));
        assertTrue(DAO.getInstance().saveTrip(trip));
        final String tess = traveler(trip);
        // The countdown reaches every confirmed traveler; the balance reminder needs a balance, which Tess
        // does not have, so its run records "nobody" and mails no one.
        final TripReminder countdown = TripReminder.builder().id("count").kind(TripReminder.Kind.COUNTDOWN)
                .anchor(TripReminder.Anchor.TRIP_START).offsetDays(-90).subject("s").body("b").build()
                .approved(trip, APPROVER, NOW);
        final TripReminder balance = TripReminder.builder().id("bal").kind(TripReminder.Kind.BALANCE_DUE)
                .anchor(TripReminder.Anchor.BALANCE_DUE_DATE).offsetDays(-14).subject("s").body("b").build()
                .approved(trip, APPROVER, NOW);
        final TripReminder later = TripReminder.builder().id("later").kind(TripReminder.Kind.PASSPORT)
                .anchor(TripReminder.Anchor.TRIP_START).offsetDays(-30).subject("s").body("b").build()
                .approved(trip, APPROVER, NOW);
        assertEquals(countdown.sendDate(trip), TODAY);
        assertEquals(balance.sendDate(trip), TODAY);
        plan(trip, countdown, balance, later);

        scheduler(enabled).runFor(TODAY);

        assertEquals(mails(tess), 1, "The countdown only");
        final List<ReminderSend> history = DAO.getInstance().getReminderSends(trip.getId());
        assertEquals(history.stream().filter(s -> s.getReminderId().equals("count")).count(), 1);
        assertEquals(history.stream().filter(s -> s.getReminderId().equals("bal")
                && s.getOutcome() == ReminderSend.Outcome.NOBODY).count(), 1);
        assertEquals(history.stream().filter(s -> s.getReminderId().equals("later")).count(), 0, "Not due yet");
    }

    @Test
    public void aDraftNeverFires() throws IOException {
        final Trip trip = newTrip();
        final String tess = traveler(trip);
        plan(trip, reminder(trip, TripReminder.Kind.CUSTOM, TODAY, true));
        scheduler(enabled).runFor(TODAY);
        assertEquals(mails(tess), 0);
        assertTrue(DAO.getInstance().getReminderSends(trip.getId()).isEmpty());
    }

    @Test
    public void anEditedOrRedatedMessageDoesNotFire() throws IOException {
        final Trip trip = newTrip();
        final String tess = traveler(trip);
        final TripReminder approved = reminder(trip, TripReminder.Kind.CUSTOM, TODAY, false);
        // The stored status still says APPROVED: only the hash knows the words changed.
        plan(trip, approved.withBody("<p>Words nobody approved</p>"));
        scheduler(enabled).runFor(TODAY);
        assertEquals(mails(tess), 0);

        final Trip moving = newTrip();
        final String tom = traveler(moving);
        final TripReminder relative = TripReminder.builder().id("rel").anchor(TripReminder.Anchor.TRIP_START)
                .offsetDays(-90).subject("s").body("b").build().approved(moving, APPROVER, NOW);
        plan(moving, relative);
        moving.setStartDate(START.plusDays(1));
        assertTrue(DAO.getInstance().saveTrip(moving));
        final ReminderScheduler scheduler = scheduler(enabled);
        scheduler.runFor(TODAY);
        scheduler.runFor(TODAY.plusDays(1));
        assertEquals(mails(tom), 0, "Neither on the old date nor on the new, unapproved one");
    }

    // ------------------------------------------------------------------ once, across servers and outages

    @Test
    public void twoServersSendOnce() throws IOException {
        final Trip trip = newTrip();
        final String tess = traveler(trip);
        plan(trip, reminder(trip, TripReminder.Kind.COUNTDOWN, TODAY, false));
        // Separate caches, so neither sees the other's run lock: the ledger alone has to hold.
        scheduler(enabled).runFor(TODAY);
        scheduler(enabled).runFor(TODAY);
        assertEquals(mails(tess), 1);
    }

    @Test
    public void aCacheThatGrantsEveryLockStillSendsOnce() throws IOException {
        final Trip trip = newTrip();
        final String tess = traveler(trip);
        plan(trip, reminder(trip, TripReminder.Kind.COUNTDOWN, TODAY, false));
        final ReminderScheduler first = ReminderScheduler.forTest(new NoopCacheClient(), enabled, sender(), () -> NOW);
        final ReminderScheduler second = ReminderScheduler.forTest(new NoopCacheClient(), enabled, sender(), () -> NOW);
        first.attemptOnce(TODAY, NOW);
        second.attemptOnce(TODAY, NOW);
        assertEquals(mails(tess), 1);
    }

    @Test
    public void theRunLockKeepsASecondInstanceFromRepeatingTheWork() throws IOException {
        final Trip trip = newTrip();
        final String tess = traveler(trip);
        plan(trip, reminder(trip, TripReminder.Kind.COUNTDOWN, TODAY, false));
        final InMemoryCacheClient shared = new InMemoryCacheClient();
        ReminderScheduler.forTest(shared, enabled, sender(), () -> NOW).attemptOnce(TODAY, NOW);
        assertEquals(mails(tess), 1);
        mailedTo.clear();
        ReminderScheduler.forTest(shared, enabled, sender(), () -> NOW).attemptOnce(TODAY, NOW);
        assertTrue(mailedTo.isEmpty(), "The second instance stood down at the lock");
    }

    /** The retry after a failed run must not be turned away by the lock the failed run took. */
    @Test
    public void aRunThatFailedIsRetriedAndThenSends() throws IOException {
        final Trip trip = newTrip();
        final String tess = traveler(trip);
        plan(trip, reminder(trip, TripReminder.Kind.COUNTDOWN, TODAY, false));
        final InMemoryCacheClient shared = new InMemoryCacheClient();
        final ReminderScheduler failing = ReminderScheduler.forTest(shared, enabled, sender(), () -> NOW,
                ReminderSchedulerTest::tripIndexDown);
        assertThrows(IllegalStateException.class, () -> failing.attemptOnce(TODAY, NOW));
        assertEquals(mails(tess), 0);
        // The retry, minutes later, on the same cache: the lock was released, so it runs.
        ReminderScheduler.forTest(shared, enabled, sender(), () -> NOW).attemptOnce(TODAY, NOW);
        assertEquals(mails(tess), 1);
        // And the whole loop survives the failure without throwing.
        failing.attempt(TODAY, NOW);
    }

    /** A cache that refuses every lock because it is down must not end the day unsent. */
    @Test
    public void aCacheThatCannotHoldALockDoesNotStopTheRun() throws IOException {
        final Trip trip = newTrip();
        final String tess = traveler(trip);
        plan(trip, reminder(trip, TripReminder.Kind.COUNTDOWN, TODAY, false));
        // A mock answers false to every lock and every write: a cache that is not there.
        final CacheClient down = Mockito.mock(CacheClient.class);
        ReminderScheduler.forTest(down, enabled, sender(), () -> NOW).attemptOnce(TODAY, NOW);
        assertEquals(mails(tess), 1);
    }

    @Test
    public void aMessageDatedMonthsAfterTheTripEndedIsStillReached() throws IOException {
        final LocalDateTime longAgo = LocalDateTime.of(2026, 9, 1, 8, 0);
        final Trip trip = Trip.builder().id(UUID.randomUUID().toString()).title("Ended trip")
                .startDate(longAgo).endDate(longAgo.plusDays(9)).people(new ArrayList<>()).build();
        trip.setOrgId(FakeData.CFPW_ORG_ID);
        assertTrue(DAO.getInstance().saveTrip(trip));
        final String tess = traveler(trip);
        // March 3 is about 175 days after this trip ended: past the old 60-day window, inside the year allowed.
        plan(trip, reminder(trip, TripReminder.Kind.CUSTOM, TODAY, false));
        scheduler(enabled).runFor(TODAY);
        assertEquals(mails(tess), 1);
        assertTrue(ReminderScheduler.ENDED_WITHIN_DAYS > TripReminder.MAX_DAYS_AFTER_END);
    }

    @Test
    public void aMissedDayIsCaughtUpTheNextDayOnly() throws IOException {
        final Trip trip = newTrip();
        final String tess = traveler(trip);
        plan(trip, reminder(trip, TripReminder.Kind.COUNTDOWN, TODAY.minusDays(1), false),
                reminder(trip, TripReminder.Kind.CUSTOM, TODAY.minusDays(2), false));
        scheduler(enabled).runFor(TODAY);
        assertEquals(mails(tess), 1, "Yesterday's is sent; the one from two days ago waits for the facilitator");
        assertTrue(ReminderScheduler.isDue(TODAY, TODAY));
        assertTrue(ReminderScheduler.isDue(TODAY.minusDays(1), TODAY));
        assertFalse(ReminderScheduler.isDue(TODAY.minusDays(2), TODAY));
        assertFalse(ReminderScheduler.isDue(TODAY.plusDays(1), TODAY));
    }

    @Test
    public void aCatchUpDayThatAlreadyRanIsNotRunAgainForNewcomers() throws IOException {
        final Trip trip = newTrip();
        final String tess = traveler(trip);
        plan(trip, reminder(trip, TripReminder.Kind.COUNTDOWN, TODAY.minusDays(1), false));
        final ReminderScheduler scheduler = scheduler(enabled);
        scheduler.runFor(TODAY.minusDays(1));
        assertEquals(mails(tess), 1);
        // Someone confirmed after the run: mailing them a day late, unannounced, is what catch-up must not do.
        final String late = traveler(trip);
        scheduler.runFor(TODAY);
        assertEquals(mails(late), 0);
        assertEquals(mails(tess), 1);
    }

    // ------------------------------------------------------------------ the loop

    @Test
    public void nothingIsSentWhileTheSiteSwitchIsOff() throws IOException {
        final Trip trip = newTrip();
        final String tess = traveler(trip);
        plan(trip, reminder(trip, TripReminder.Kind.COUNTDOWN, TODAY, false));
        scheduler(new ConfigCommands()).attemptOnce(TODAY, NOW);
        assertEquals(mails(tess), 0);
    }

    @Test
    public void aRunThatKeepsFailingIsAbandonedAfterTwoHours() throws IOException {
        final Trip trip = newTrip();
        final String tess = traveler(trip);
        plan(trip, reminder(trip, TripReminder.Kind.COUNTDOWN, TODAY, false));
        scheduler(enabled).attemptOnce(TODAY, NOW.minus(Duration.ofHours(3)));
        assertEquals(mails(tess), 0);
    }

    @Test
    public void aFullAttemptSendsAndNeverThrows() throws IOException {
        final Trip trip = newTrip();
        final String tess = traveler(trip);
        plan(trip, reminder(trip, TripReminder.Kind.COUNTDOWN, TODAY, false));
        final ReminderScheduler scheduler = scheduler(enabled);
        assertEquals(scheduler.today(), TODAY, "9 AM Pacific is still March 3");
        scheduler.attempt(TODAY, NOW);
        assertEquals(mails(tess), 1);
        final ConfigCommands throwing = new ConfigCommands() {
            @Override
            public boolean getBoolean(final String name, final boolean defaultValue) {
                throw new IllegalStateException("config store down");
            }
        };
        scheduler(throwing).attempt(TODAY, NOW);
    }

    @Test
    public void theNextRunIsAlwaysInTheFuture() {
        final ReminderScheduler scheduler = scheduler(enabled);
        final Duration justAfter = scheduler.untilNextRun(ZonedDateTime.parse("2027-03-03T09:00:30-08:00"));
        assertTrue(justAfter.toMillis() > 0 && justAfter.toHours() <= 24);
        assertEquals(scheduler.untilNextRun(ZonedDateTime.parse("2027-03-03T08:00:00-08:00")), Duration.ofHours(1));
        assertEquals(scheduler.untilNextRun(ZonedDateTime.parse("2027-03-03T09:00:00-08:00")), Duration.ofHours(24));
    }

    @Test
    public void aBadZoneFallsBackToUtcAndTheThreadIsVirtual() {
        final ConfigCommands badZone = new EnabledConfig() {
            @Override
            public String getString(final String name, final String defaultValue) {
                return "reminders.zone".equals(name) ? "Not/AZone" : defaultValue;
            }
        };
        assertEquals(scheduler(badZone).today(), LocalDate.of(2027, 3, 3));
        final Thread thread = scheduler(enabled).newThread(() -> { });
        assertTrue(thread.isVirtual());
        assertEquals(thread.getName(), "trip-reminders");
    }

    @Test
    public void startAndStopAreIdempotent() {
        ReminderScheduler.start();
        ReminderScheduler.start();
        ReminderScheduler.stop();
        ReminderScheduler.stop();
    }
}
