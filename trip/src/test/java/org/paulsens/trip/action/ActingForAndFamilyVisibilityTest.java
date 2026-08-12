package org.paulsens.trip.action;

import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * Phase-3 behaviors: the sticky acting-for subject resolution (which must never leak past what
 * {@code canAccessUserId} allows), the family-aware trip/chat visibility that closed the old FIXME, and the
 * Java-side balance math behind the transactions page's family views.
 */
public class ActingForAndFamilyVisibilityTest {
    private DAO dao;

    @BeforeClass
    public void init() {
        dao = DAO.getInstance();
    }

    // ------------------------------------------------------------------ acting-for

    @Test
    public void subjectResolutionLadderAndValidation() throws IOException {
        final Person parent = savedPerson();
        final Person kid = savedPerson();
        final Person stranger = savedPerson();
        parent.getManagedUsers().add(kid.getId());
        assertTrue(dao.savePerson(parent));

        final PersonCommands people = new PersonCommands();
        final Map<String, Object> session = new HashMap<>();
        session.put(PersonCommands.ACTIVE_USER_ID, parent.getId());
        try (MockedStatic<FacesContext> ignored = facesWithSession(session)) {
            assertEquals(people.getSubject(null).getId(), parent.getId(), "Default subject is yourself");

            people.actFor(kid.getId().getValue());
            assertEquals(people.getActingFor(), kid.getId(), "A managed member sticks");
            assertEquals(people.getSubject(null).getId(), kid.getId(), "...and becomes the default subject");
            assertEquals(people.getSubject(stranger.getId().getValue()).getId(), stranger.getId(),
                    "An explicit ?id= always wins (page auth still gates it)");

            people.actFor(stranger.getId().getValue());
            assertNull(people.getActingFor(), "Someone outside your reach clears the selection");

            people.actFor(kid.getId().getValue());
            people.actFor(parent.getId().getValue());
            assertNull(people.getActingFor(), "Selecting yourself clears it");

            session.put(PersonCommands.ACTING_FOR, stranger.getId());
            assertNull(people.getActingFor(), "A stale/forged session value is refused...");
            assertFalse(session.containsKey(PersonCommands.ACTING_FOR), "...and removed from the session");

            session.put(PersonCommands.ACTING_FOR, kid.getId().getValue());
            assertEquals(people.getActingFor(), kid.getId(),
                    "A String-typed session value (pages store both) still resolves");

            people.actFor(null);
            assertNull(people.getActingFor(), "actFor(null) clears");
            people.actFor("  ");
            assertNull(people.getActingFor(), "actFor(blank) clears");
            assertEquals(people.getSubject("  ").getId(), parent.getId(),
                    "A blank ?id= falls through the ladder like an absent one");
        }
    }

    // ------------------------------------------------------------------ family trip + chat visibility

    @Test
    public void aParentSeesAndJoinsTheChatOfTheirKidsTrip() throws IOException {
        final Person parent = savedPerson();
        final Person kid = savedPerson();
        final Person stranger = savedPerson();
        parent.getManagedUsers().add(kid.getId());
        assertTrue(dao.savePerson(parent));

        // ALREADY STARTED: getTripForUser's last-resort ladder offers any joinable (future) trip to anyone,
        // which would mask the canSeeTrip answer this test is about. A started trip cannot be joined.
        final Trip trip = Trip.builder().id("fam-vis-" + RandomData.genAlpha(8)).title("Kid Trip")
                .startDate(LocalDateTime.now().minusDays(2)).endDate(LocalDateTime.now().plusDays(8))
                .people(List.of(kid.getId())).build();
        assertTrue(dao.saveTrip(trip));

        final ChatCommands chat = new ChatCommands();
        assertTrue(chat.isTripMember(trip.getId(), kid.getId()), "The kid is a plain roster member");
        assertTrue(chat.isTripMember(trip.getId(), parent.getId()),
                "The parent gets FULL chat membership of the kid's trip");
        assertFalse(chat.isTripMember(trip.getId(), stranger.getId()), "A stranger stays out");

        final TripCommands trips = new TripCommands();
        final Trip found = trips.getTripForUser(null, parent.getId(), false, trip.getId());
        assertNotNull(found, "canSeeTrip honors the family relationship (the old double-FIXME)");
        assertEquals(found.getId(), trip.getId());
        // The ladder never answers null while ANY joinable trip exists -- it falls back to one of those. The
        // property that matters: a stranger asking for THIS trip is not given it.
        final Trip strangerTrip = trips.getTripForUser(null, stranger.getId(), false, trip.getId());
        assertTrue(strangerTrip == null || !trip.getId().equals(strangerTrip.getId()),
                "A stranger still cannot see the kid's trip");

        // Channels are created lazily; My Chats only lists trips whose channel exists.
        assertNotNull(chat.ensureChannel(trip.getId(), org.paulsens.trip.audit.AuditActor.system()));
        assertTrue(chat.myChats(parent.getId()).stream()
                        .anyMatch(c -> trip.getId().equals(c.channel().getTripId())),
                "My Chats lists the kid's trip for the parent");
    }

    // ------------------------------------------------------------------ balances

    @Test
    public void balancesSumPerUserSharesAndFamilyViewsAgreeWithIndividualViews() throws IOException {
        final Person a = savedPerson();
        final Person b = savedPerson();
        final String groupId = "grp-" + RandomData.genAlpha(8);

        final TransactionsCommands txCmds = new TransactionsCommands();
        assertTrue(dao.saveTransaction(plainTx(a.getId(), -300f)));
        assertTrue(dao.saveTransaction(plainTx(a.getId(), 100f)));
        assertTrue(dao.saveTransaction(plainTx(b.getId(), -50f)));
        // A shared bill of -80 across {a, b}: each member's own row carries the full amount and the group,
        // so each is responsible for -40.
        assertTrue(dao.saveTransaction(sharedTx(a.getId(), groupId, List.of(a.getId(), b.getId()), -80f)));
        assertTrue(dao.saveTransaction(sharedTx(b.getId(), groupId, List.of(a.getId(), b.getId()), -80f)));

        assertEquals(txCmds.getBalance(a.getId()), -240d, 0.001, "a: -300 + 100 - 40");
        assertEquals(txCmds.getBalance(b.getId()), -90d, 0.001, "b: -50 - 40");
        assertEquals(txCmds.getFamilyBalance(List.of(a.getId(), b.getId())), -330d, 0.001,
                "The family total is exactly the sum of the individual views -- a shared row counts once "
                        + "per member's share, never double");

        final List<Transaction> merged = txCmds.getFamilyTransactions(List.of(a.getId(), b.getId()));
        assertEquals(merged.size(), 5, "Every member row appears once");
        for (int i = 1; i < merged.size(); i++) {
            assertFalse(merged.get(i).getTxDate().isBefore(merged.get(i - 1).getTxDate()),
                    "Merged rows are date-sorted");
        }
        assertEquals(txCmds.getFamilyBalance(null), 0d, 0.001);
        assertTrue(txCmds.getFamilyTransactions(null).isEmpty());
    }

    // ------------------------------------------------------------------ helpers

    private Person savedPerson() {
        final Person person = Person.builder()
                .first(RandomData.genAlpha(6)).last(RandomData.genAlpha(8))
                .email("vis." + RandomData.genAlpha(10) + "@example.com")
                .build();
        try {
            assertTrue(dao.savePerson(person));
        } catch (final IOException ex) {
            throw new IllegalStateException(ex);
        }
        return person;
    }

    private static Transaction plainTx(final Person.Id userId, final float amount) {
        return new Transaction("tx-" + RandomData.genAlpha(9), userId, null, Transaction.Type.Tx,
                Transaction.TransactionType.Payment, LocalDateTime.now().minusDays(RandomData.randomLong(30)),
                amount, "Test", "test row");
    }

    private static Transaction sharedTx(final Person.Id userId, final String groupId,
            final List<Person.Id> groupPeople, final float amount) {
        final Transaction tx = new Transaction("tx-" + RandomData.genAlpha(9), userId, groupId,
                Transaction.Type.Shared, Transaction.TransactionType.Bill,
                LocalDateTime.now().minusDays(3), amount, "Test", "shared row");
        tx.setGroupPeople(groupPeople);
        return tx;
    }

    private static MockedStatic<FacesContext> facesWithSession(final Map<String, Object> session) {
        final MockedStatic<FacesContext> faces = Mockito.mockStatic(FacesContext.class);
        final FacesContext ctx = Mockito.mock(FacesContext.class);
        final ExternalContext ext = Mockito.mock(ExternalContext.class);
        Mockito.when(ctx.getExternalContext()).thenReturn(ext);
        Mockito.when(ext.getSessionMap()).thenReturn(session);
        faces.when(FacesContext::getCurrentInstance).thenReturn(ctx);
        return faces;
    }
}
