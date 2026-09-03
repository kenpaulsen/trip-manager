package org.paulsens.trip.action;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Payment;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PersonDataValue;
import org.paulsens.trip.model.Privilege;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.TodoItem;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * The destructive trip delete: authorization matrix, the not-deletable conditions, and the full cascade over
 * the real in-memory store -- the {@link OrgCommandsTest} pattern (only the caller and the badge bean are
 * stood in for).
 */
public class TripDeleteCommandsTest {
    private DAO dao;

    @BeforeClass
    public void init() {
        dao = DAO.getInstance();
    }

    // ------------------------------------------------------------------ authorization

    @Test
    public void siteAdminMayDeleteAnOrgTrip() throws IOException {
        final Trip trip = savedTrip(orgWithAdmin(savedPerson()));
        assertTrue(siteAdmin().canDelete(trip));
    }

    @Test
    public void siteAdminMayDeleteAnOrglessTrip() throws IOException {
        final Trip trip = savedTrip(null);
        assertTrue(siteAdmin().canDelete(trip));
    }

    @Test
    public void editorAdminAloneMayNotDelete() throws IOException {
        final Person mgr = savedPerson();
        final Trip trip = savedTrip(orgWithAdmin(savedPerson()));   // someone ELSE administers the org
        assertFalse(commandsFor(mgr, trip.getId(), true).canDelete(trip),
                "tripMgr without org admin must be refused");
    }

    @Test
    public void orgAdminAloneMayNotDelete() throws IOException {
        final Person orgAdmin = savedPerson();
        final Trip trip = savedTrip(orgWithAdmin(orgAdmin));
        assertFalse(commandsFor(orgAdmin, trip.getId(), false).canDelete(trip),
                "org admin without the Editor Admin privilege must be refused");
    }

    @Test
    public void editorAdminPlusOrgAdminMayDelete() throws IOException {
        final Person both = savedPerson();
        final Trip trip = savedTrip(orgWithAdmin(both));
        assertTrue(commandsFor(both, trip.getId(), true).canDelete(trip));
    }

    @Test
    public void orglessTripIsNotDeletableByAnOrgAdmin() throws IOException {
        final Person mgr = savedPerson();
        final Trip trip = savedTrip(null);
        assertFalse(commandsFor(mgr, trip.getId(), true).canDelete(trip),
                "an org-less trip falls back to site admins only");
    }

    @Test
    public void anonymousAndNullTripsAreRefused() {
        final TripDeleteCommands anon = new TripDeleteCommands(
                () -> new Caller(null, false, AuditActor.system(), grantsNothing()),
                () -> new OrgCommands(() -> new Caller(null, false, AuditActor.system(), grantsNothing())),
                TripDeleteCommandsTest::badgeMock);
        assertFalse(anon.canDelete(savedTripQuietly(null)));
        assertFalse(siteAdmin().canDelete(null));
        assertFalse(siteAdmin().canDelete(Trip.builder().id("").build()), "A blank id is never deletable");
    }

    // ------------------------------------------------------------------ blocking conditions

    @Test
    public void peopleOnTheTripBlockDeletion() throws IOException {
        final Trip trip = Trip.builder().people(List.of(savedPerson().getId())).build();
        trip.setTitle("Blocked " + unique());
        assertTrue(dao.saveTrip(trip));
        final List<String> blockers = siteAdmin().blockers(trip);
        assertEquals(blockers.size(), 1);
        assertTrue(blockers.get(0).contains("still part of this trip"), blockers.get(0));
        assertTrue(siteAdmin().isBlocked(trip));
    }

    @Test
    public void approvedRegistrationsBlockDeletionButPendingDoNot() throws IOException {
        final Trip trip = savedTrip(null);
        final Registration pending = new Registration(trip.getId(), savedPerson().getId())
                .withStatus(Registration.Status.PENDING);
        assertTrue(dao.saveRegistration(pending));
        assertFalse(siteAdmin().isBlocked(trip), "A pending registration must not block");

        final Registration approved = new Registration(trip.getId(), savedPerson().getId())
                .withStatus(Registration.Status.CONFIRMED);
        assertTrue(dao.saveRegistration(approved));
        final List<String> blockers = siteAdmin().blockers(trip);
        assertEquals(blockers.size(), 1);
        assertTrue(blockers.get(0).contains("approved"), blockers.get(0));
    }

    @Test
    public void nonTerminalPaymentsBlockDeletion() throws IOException {
        final Trip trip = savedTrip(null);
        final Payment live = Payment.builder().tripId(trip.getId())
                .payerId(savedPerson().getId()).status(Payment.Status.CREATED).build();
        assertTrue(dao.createPayment(live));
        assertTrue(siteAdmin().blockers(trip).stream().anyMatch(b -> b.contains("payment record")),
                "A CREATED payment must block");
    }

    @Test
    public void terminalPaymentsDoNotBlockDeletion() throws IOException {
        final Trip trip = savedTrip(null);
        final Payment cancelled = Payment.builder().tripId(trip.getId())
                .payerId(savedPerson().getId()).status(Payment.Status.CANCELLED).build();
        final Payment failed = Payment.builder().tripId(trip.getId())
                .payerId(savedPerson().getId()).status(Payment.Status.FAILED).build();
        assertTrue(dao.createPayment(cancelled));
        assertTrue(dao.createPayment(failed));
        assertFalse(siteAdmin().isBlocked(trip));
    }

    @Test
    public void liveTransactionsBlockDeletionAndSoftDeletedOnesDoNot() throws IOException {
        final Trip trip = savedTrip(null);
        final Person payer = savedPerson();
        final Transaction tx = new Transaction(payer.getId(), null, Transaction.Type.Tx);
        assertTrue(dao.saveTransaction(tx));
        bindTxToTrip(payer, tx, trip.getId());
        assertTrue(siteAdmin().blockers(trip).stream().anyMatch(b -> b.contains("transaction")),
                "A live bound transaction must block");

        tx.delete();
        assertTrue(dao.saveTransaction(tx));
        assertFalse(siteAdmin().isBlocked(trip), "A soft-deleted transaction must not block");
    }

    @Test
    public void danglingTransactionBindingsDoNotBlock() throws IOException {
        final Trip trip = savedTrip(null);
        assertTrue(dao.saveBinding("ghost-user,ghost-tx", BindingType.TRANSACTION,
                trip.getId(), BindingType.TRIP, true));
        assertFalse(siteAdmin().isBlocked(trip), "A binding to a nonexistent transaction must not block");
    }

    // ------------------------------------------------------------------ delete refusals

    @Test
    public void wrongChallengeRefusesTheDelete() throws IOException {
        final Trip trip = savedTrip(null);
        assertFalse(siteAdmin().deleteTrip(trip, "DELETE ME"));
        assertFalse(siteAdmin().deleteTrip(trip, null));
        assertTrue(dao.getTrip(trip.getId(), Cached.NO).isPresent(), "Trip must survive a failed challenge");
    }

    @Test
    public void unauthorizedDeleteIsRefusedEvenWithTheChallenge() throws IOException {
        final Person mgr = savedPerson();
        final Trip trip = savedTrip(orgWithAdmin(savedPerson()));
        assertFalse(commandsFor(mgr, trip.getId(), true).deleteTrip(trip, "delete"));
        assertTrue(dao.getTrip(trip.getId(), Cached.NO).isPresent());
    }

    @Test
    public void blockedDeleteIsRefusedEvenWithTheChallenge() throws IOException {
        final Trip trip = Trip.builder().people(List.of(savedPerson().getId())).build();
        trip.setTitle("Still blocked " + unique());
        assertTrue(dao.saveTrip(trip));
        assertFalse(siteAdmin().deleteTrip(trip, "delete"));
        assertTrue(dao.getTrip(trip.getId(), Cached.NO).isPresent());
    }

    @Test
    public void deletingAnAlreadyGoneTripReportsSuccess() {
        final Trip ghost = Trip.builder().build();
        ghost.setTitle("Ghost");
        assertTrue(siteAdmin().deleteTrip(ghost, "delete"), "Goal state already reached");
    }

    // ------------------------------------------------------------------ the cascade

    @Test
    public void deleteCascadesOverEveryTable() throws IOException {
        final Person exMember = savedPerson();
        final Organization org = orgWithAdmin(savedPerson());
        final TripEvent event = new TripEvent(UUID.randomUUID().toString(), null, "Flight", null,
                null, null, null, null);
        final Trip trip = Trip.builder().tripEvents(List.of(event)).build();
        trip.setTitle("Doomed " + unique());
        trip.setOrganization(org);
        assertTrue(dao.saveTrip(trip));
        final String tripId = trip.getId();

        // A pending registration, a todo with a per-person status, a room row, and a trip-scoped privilege.
        assertTrue(dao.saveRegistration(new Registration(tripId, exMember.getId())
                .withStatus(Registration.Status.PENDING)));
        final DataId todoId = DataId.newInstance();
        assertTrue(dao.saveTodo(TodoItem.builder().tripId(tripId).dataId(todoId).description("Pack").build()));
        assertTrue(dao.savePersonDataValue(PersonDataValue.builder()
                .userId(exMember.getId()).dataId(todoId).type("todo").content("done").build()));
        assertTrue(dao.savePersonDataValue(PersonDataValue.builder()
                .userId(exMember.getId()).dataId(RegistrationCommands.tripRoomDataId(tripId))
                .type("room").content("101A").build()));
        assertTrue(dao.savePrivilege(new Privilege(Privilege.idFor(PrivilegeCommands.TRIP_MGR, tripId),
                "Doomed - Editor Admin", List.of(exMember.getId()))));

        // A soft-deleted transaction, bound to the trip: allowed, and its rows must SURVIVE the delete.
        final Transaction tx = new Transaction(exMember.getId(), null, Transaction.Type.Tx);
        tx.delete();
        assertTrue(dao.saveTransaction(tx));
        bindTxToTrip(exMember, tx, tripId);

        final BadgePhotoCommands badges = badgeMock();
        final TripDeleteCommands commands = siteAdmin(badges);
        assertTrue(commands.deleteTrip(trip, "delete"));

        assertTrue(dao.getTrip(tripId, Cached.NO).isEmpty(), "trips row must be gone");
        assertTrue(dao.getRegistrations(tripId, Cached.NO).isEmpty(), "registrations must be gone");
        assertTrue(dao.getTodoItems(tripId, Cached.NO).isEmpty(), "todo_items must be gone");
        assertTrue(dao.getPersonDataValue(exMember.getId(), todoId, Cached.NO).isEmpty(),
                "todo status person_data row must be gone");
        assertTrue(dao.getPersonDataValue(exMember.getId(),
                        RegistrationCommands.tripRoomDataId(tripId), Cached.NO).isEmpty(),
                "room person_data row must be gone");
        assertTrue(dao.getTripPrivileges(tripId, Cached.NO).isEmpty(), "trip-scoped privs must be gone");
        Mockito.verify(badges).deleteAllForTrip(Mockito.argThat(t -> tripId.equals(t.getId())));

        // The financial record survives, dangling on purpose. (The tx row itself is unobservable here:
        // local mode evicts soft-deleted transactions from the cache-as-store; the binding is the witness.)
        assertEquals(dao.getBindings(tripId, BindingType.TRIP, BindingType.TRANSACTION, Cached.NO),
                List.of(exMember.getId().getValue() + "," + tx.getTxId()),
                "trip->transaction bindings are left as the record of money");
    }

    /**
     * The user-specified lifecycle sequence (2026-08-22), proving the delete path never destroys unintended
     * data: X is accepted on {@code delMe} AND {@code other}, with a financial transaction on each. The
     * delete of {@code delMe} must refuse while X is accepted, refuse again over the live transaction after
     * X is removed, succeed once that transaction is soft-deleted -- and at every stage {@code other}'s
     * registration, roster, and transaction must come through untouched.
     */
    @Test
    public void theLifecycleSequenceNeverTouchesTheOtherTripsData() throws IOException {
        final Person userX = savedPerson();
        // "Accepted" is what the approval flow writes: a CONFIRMED registration AND roster membership.
        final Trip delMe = acceptedTripFor(userX, "delMe " + unique());
        final Transaction delMeTx = liveBoundTransaction(userX, delMe.getId());
        final Trip other = acceptedTripFor(userX, "other " + unique());
        final Transaction otherTx = liveBoundTransaction(userX, other.getId());
        final TripDeleteCommands admin = siteAdmin();

        // 1) Refused: X is accepted (both the roster and the approved registration say so).
        assertFalse(admin.deleteTrip(delMe, "delete"), "an accepted pilgrim must block the delete");
        assertTrue(dao.getTrip(delMe.getId(), Cached.NO).isPresent());
        final List<String> acceptedBlockers = admin.blockers(freshTrip(delMe.getId()));
        assertTrue(acceptedBlockers.stream().anyMatch(b -> b.contains("still part of this trip")),
                "roster blocker expected: " + acceptedBlockers);
        assertTrue(acceptedBlockers.stream().anyMatch(b -> b.contains("approved")),
                "approved-registration blocker expected: " + acceptedBlockers);
        assertOtherTripIntact(other, userX, otherTx);

        // 2) Remove X from delMe, the way the registrations page does: off the roster, registration
        //    back to Not Registered.
        final Trip roster = freshTrip(delMe.getId());
        roster.setPeople(new java.util.ArrayList<>());
        assertTrue(dao.saveTrip(roster));
        assertTrue(dao.saveRegistration(dao.getRegistration(delMe.getId(), userX.getId(), Cached.NO)
                .orElseThrow().withStatus(Registration.Status.NOT_REGISTERED)));

        // 3) Refused again: the live transaction is still bound to delMe.
        assertFalse(admin.deleteTrip(delMe, "delete"), "a live transaction must still block the delete");
        assertTrue(dao.getTrip(delMe.getId(), Cached.NO).isPresent());
        final List<String> txBlockers = admin.blockers(freshTrip(delMe.getId()));
        assertEquals(txBlockers.size(), 1, "only the transaction should block now: " + txBlockers);
        assertTrue(txBlockers.get(0).contains("transaction"), txBlockers.get(0));
        assertOtherTripIntact(other, userX, otherTx);

        // 4) Soft-delete delMe's transaction; other's stays live.
        delMeTx.delete();
        assertTrue(dao.saveTransaction(delMeTx));
        assertTrue(hasLiveTransaction(userX, otherTx.getTxId()),
                "soft-deleting delMe's transaction must not touch other's");

        // 5) Now the delete succeeds, and delMe is gone everywhere a user could find it.
        assertTrue(admin.deleteTrip(delMe, "delete"));
        assertTrue(dao.getTrip(delMe.getId(), Cached.NO).isEmpty(), "delMe's row must be gone");
        assertTrue(dao.getRegistrations(delMe.getId(), Cached.NO).isEmpty(),
                "delMe's registrations must be gone");
        assertFalse(dao.getRecentTrips(0, Cached.NO).stream()
                .anyMatch(t -> t.getId().equals(delMe.getId())), "delMe must leave the listings");
        assertFalse(dao.getTripsForUser(userX.getId(), Cached.NO).stream()
                .anyMatch(t -> t.getId().equals(delMe.getId())), "delMe must leave X's trip list");

        // 6) X's world is otherwise untouched: the other trip, its registration, and its LIVE transaction
        //    survive; no live transaction remains for delMe; X's person row is intact.
        assertOtherTripIntact(other, userX, otherTx);
        assertTrue(dao.getTripsForUser(userX.getId(), Cached.NO).stream()
                .anyMatch(t -> t.getId().equals(other.getId())), "X must still be on 'other'");
        assertFalse(hasLiveTransaction(userX, delMeTx.getTxId()),
                "no live transaction may remain for delMe");
        assertEquals(dao.getPerson(userX.getId(), Cached.NO).orElseThrow().getEmail(), userX.getEmail(),
                "X's person row must be untouched");
        // delMe's trip->tx binding survives as the dangling record of money (locked decision).
        assertEquals(dao.getBindings(delMe.getId(), BindingType.TRIP, BindingType.TRANSACTION, Cached.NO),
                List.of(userX.getId().getValue() + "," + delMeTx.getTxId()));
    }

    /** A saved trip where {@code who} is accepted: on the roster with a CONFIRMED registration. */
    private Trip acceptedTripFor(final Person who, final String title) throws IOException {
        final Trip trip = Trip.builder().people(List.of(who.getId())).build();
        trip.setTitle(title);
        assertTrue(dao.saveTrip(trip));
        assertTrue(dao.saveRegistration(new Registration(trip.getId(), who.getId())
                .withStatus(Registration.Status.CONFIRMED)));
        return trip;
    }

    /** A saved live transaction for {@code who}, bound to the trip both directions (the persistTx shape). */
    private Transaction liveBoundTransaction(final Person who, final String tripId) throws IOException {
        final Transaction tx = new Transaction(who.getId(), null, Transaction.Type.Tx);
        assertTrue(dao.saveTransaction(tx));
        bindTxToTrip(who, tx, tripId);
        return tx;
    }

    private Trip freshTrip(final String tripId) {
        return dao.getTrip(tripId, Cached.NO).orElseThrow();
    }

    private boolean hasLiveTransaction(final Person who, final String txId) {
        return dao.getTransaction(who.getId(), txId, Cached.NO)
                .filter(tx -> tx.getDeleted() == null)
                .isPresent();
    }

    /** Everything the sequence must NOT damage: other's row, roster, approved registration, live tx. */
    private void assertOtherTripIntact(final Trip other, final Person userX, final Transaction otherTx) {
        final Trip fresh = freshTrip(other.getId());
        assertEquals(fresh.getTitle(), other.getTitle(), "other's title must be untouched");
        assertTrue(fresh.getPeople().contains(userX.getId()), "X must still be on other's roster");
        assertEquals(dao.getRegistration(other.getId(), userX.getId(), Cached.NO).orElseThrow().getStatus(),
                Registration.Status.CONFIRMED, "other's registration must stay approved");
        assertTrue(hasLiveTransaction(userX, otherTx.getTxId()), "other's transaction must stay live");
        assertEquals(dao.getBindings(other.getId(), BindingType.TRIP, BindingType.TRANSACTION, Cached.NO),
                List.of(userX.getId().getValue() + "," + otherTx.getTxId()),
                "other's trip->transaction binding must be untouched");
    }

    @Test
    public void deletedTripLeavesTheTripListings() throws IOException {
        final Trip trip = savedTrip(null);
        assertTrue(dao.getRecentTrips(0, Cached.NO).stream().anyMatch(t -> t.getId().equals(trip.getId())));
        assertTrue(siteAdmin().deleteTrip(trip, "delete"));
        assertFalse(dao.getRecentTrips(0, Cached.NO).stream().anyMatch(t -> t.getId().equals(trip.getId())),
                "the index must drop the deleted trip");
    }

    // ------------------------------------------------------------------ dialog helpers

    @Test
    public void registrationCountCountsRows() throws IOException {
        final Trip trip = savedTrip(null);
        assertEquals(siteAdmin().registrationCount(trip), 0);
        assertTrue(dao.saveRegistration(new Registration(trip.getId(), savedPerson().getId())));
        assertEquals(siteAdmin().registrationCount(trip), 1);
        assertEquals(siteAdmin().registrationCount(null), 0);
    }

    @Test
    public void chatContentSummaryIsEmptyForAnUnusedChat() throws IOException {
        assertEquals(siteAdmin().chatContentSummary(savedTrip(null)), "");
        assertEquals(siteAdmin().chatContentSummary(null), "");
    }

    @Test
    public void chatContentSummaryCountsMessagesAndPhotos() throws Exception {
        final Person.Id member = savedPerson().getId();
        final Trip trip = Trip.builder().people(List.of(member)).build();
        trip.setTitle("Chatty " + unique());
        assertTrue(dao.saveTrip(trip));
        final org.paulsens.trip.audit.AuditActor actor =
                new org.paulsens.trip.audit.AuditActor("summary@test", member.getValue());
        final ChatCommands chat = new ChatCommands(
                new org.paulsens.trip.chat.ChatRateLimiter(new org.paulsens.trip.cache.InMemoryCacheClient()));
        chat.ensureChannel(trip.getId(), actor);
        assertTrue(chat.send(trip.getId(), member, "one", null, null, actor).isOk());
        assertEquals(siteAdmin().chatContentSummary(trip), "1 chat message");
        assertTrue(chat.send(trip.getId(), member, "two", null, null, actor).isOk());
        assertTrue(dao.saveMedia(new org.paulsens.trip.model.MediaItem("summary-" + unique(),
                "chat/" + trip.getId() + "/x.jpg", "Photo", null, "image/jpeg", 10,
                ChatPhotos.slotFor(trip.getId()), 0, java.time.LocalDateTime.now(), "test")));
        assertEquals(siteAdmin().chatContentSummary(trip), "2 chat messages and 1 photo");
    }

    @Test
    public void aCascadeFailureIsReportedAndLeavesTheTripFindable() throws IOException {
        final Trip trip = savedTrip(null);
        final BadgePhotoCommands failing = Mockito.mock(BadgePhotoCommands.class);
        Mockito.when(failing.deleteAllForTrip(Mockito.any()))
                .thenThrow(new IllegalStateException("store down"));
        assertFalse(siteAdmin(failing).deleteTrip(trip, "delete"));
        assertTrue(dao.getTrip(trip.getId(), Cached.NO).isPresent(),
                "the trip row goes LAST, so a mid-cascade failure leaves a findable trip");
        assertTrue(siteAdmin().deleteTrip(trip, "delete"), "and a re-run finishes the job");
    }

    @Test
    public void explainBlockersToleratesEveryState() throws IOException {
        final TripDeleteCommands commands = siteAdmin();
        commands.explainBlockers(null);
        commands.explainBlockers(savedTrip(null));                  // nothing blocking -> "can delete" info
        final Trip blocked = Trip.builder().people(List.of(savedPerson().getId())).build();
        blocked.setTitle("Explain " + unique());
        assertTrue(dao.saveTrip(blocked));
        commands.explainBlockers(blocked);                          // raises one warn per blocker
    }

    // ------------------------------------------------------------------ helpers

    private Trip savedTrip(final Organization org) throws IOException {
        final Trip trip = Trip.builder().build();
        trip.setTitle("Trip " + unique());
        if (org != null) {
            trip.setOrganization(org);
        }
        assertTrue(dao.saveTrip(trip));
        return trip;
    }

    private Trip savedTripQuietly(final Organization org) {
        try {
            return savedTrip(org);
        } catch (final IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Organization orgWithAdmin(final Person orgAdmin) {
        final OrgCommands admin = new OrgCommands(TripDeleteCommandsTest::siteAdminCaller);
        final Organization org = admin.createOrganization("Del " + unique(), null, null);
        assertNotNull(org);
        assertTrue(admin.setOrgAdmin(org.getId().getValue(), orgAdmin.getId(), true));
        return dao.getOrganization(org.getId(), Cached.NO).orElseThrow();
    }

    private Person savedPerson() throws IOException {
        final Person person = Person.builder()
                .first(RandomData.genAlpha(6)).last(RandomData.genAlpha(8))
                .email("del." + unique() + "@example.com")
                .build();
        assertTrue(dao.savePerson(person));
        return person;
    }

    private void bindTxToTrip(final Person payer, final Transaction tx, final String tripId) {
        assertTrue(dao.saveBinding(payer.getId().getValue() + "," + tx.getTxId(),
                BindingType.TRANSACTION, tripId, BindingType.TRIP, true));
    }

    /** Site admin: {@code Caller.has} and {@code canManageOrg} both short-circuit. */
    private TripDeleteCommands siteAdmin() {
        return siteAdmin(badgeMock());
    }

    private TripDeleteCommands siteAdmin(final BadgePhotoCommands badges) {
        return new TripDeleteCommands(TripDeleteCommandsTest::siteAdminCaller,
                () -> new OrgCommands(TripDeleteCommandsTest::siteAdminCaller), () -> badges);
    }

    /** A non-admin caller, optionally holding the trip's Editor Admin ({@code tripMgr}) privilege. */
    private TripDeleteCommands commandsFor(final Person person, final String tripId, final boolean tripMgr) {
        final PrivilegeCommands privileges = grantsNothing();
        Mockito.when(privileges.check(PrivilegeCommands.TRIP_MGR, tripId, person.getId())).thenReturn(tripMgr);
        final Caller caller = new Caller(person.getId(), false,
                new AuditActor(person.getEmail(), person.getId().getValue()), privileges);
        return new TripDeleteCommands(() -> caller, () -> new OrgCommands(() -> caller),
                TripDeleteCommandsTest::badgeMock);
    }

    private static Caller siteAdminCaller() {
        return new Caller(Person.Id.from("site-admin"), true,
                new AuditActor("admin@test", "admin"), grantsNothing());
    }

    private static BadgePhotoCommands badgeMock() {
        final BadgePhotoCommands badges = Mockito.mock(BadgePhotoCommands.class);
        Mockito.when(badges.deleteAllForTrip(Mockito.any())).thenReturn(0);
        return badges;
    }

    private static PrivilegeCommands grantsNothing() {
        final PrivilegeCommands none = Mockito.mock(PrivilegeCommands.class);
        Mockito.when(none.check(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(false);
        return none;
    }

    private static String unique() {
        return RandomData.genAlpha(10);
    }
}
