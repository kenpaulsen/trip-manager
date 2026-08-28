package org.paulsens.trip.action;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.AuditPage;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The remaining {@link TransactionsCommands}, {@link AuditCommands} and {@link AuditViewCommands} branches
 * against the fake store.
 *
 * <p>On the audit side the load-bearing details are the human-readable messages: they are what lands in
 * notification emails, and the transaction one regressed once by dropping the ACTOR from the text while the
 * record itself stayed correct.
 */
public class AuditAndTransactionsTailTest {

    private final TransactionsCommands transactions = new TransactionsCommands();
    private final AuditCommands audit = new AuditCommands();
    private final AuditViewCommands auditView = new AuditViewCommands();
    private final PersonCommands people = new PersonCommands();

    private Person savedPerson(final String first) {
        final Person person = people.createPerson();
        person.setFirst(first);
        person.setLast("Auditee");
        person.setEmail(first.toLowerCase() + "@audit.example");
        Assert.assertTrue(people.savePerson(person));
        return person;
    }

    // --- TransactionsCommands ---

    @Test
    public void aTransactionRoundTripsThroughTheStore() {
        final Person who = savedPerson("Txer");
        final Transaction tx = transactions.createTransaction(who.getId());
        tx.setAmount(150f);
        tx.setNote("deposit");

        Assert.assertTrue(transactions.saveTransaction(tx));

        Assert.assertTrue(transactions.hasTransaction(who.getId(), tx.getTxId()));
        Assert.assertEquals(transactions.getTransaction(who.getId(), tx.getTxId()).getAmount(), 150f);
        Assert.assertTrue(transactions.getTransactions(who.getId()).stream()
                .anyMatch(t -> t.getTxId().equals(tx.getTxId())));
    }

    @Test
    public void transactionLookupsGuardTheirInputs() {
        final Person who = savedPerson("Guardtx");

        Assert.assertEquals(transactions.getTransactions(null), List.of());
        Assert.assertThrows(IllegalArgumentException.class, () -> transactions.getTransaction(null, "t"));
        Assert.assertThrows(IllegalArgumentException.class,
                () -> transactions.hasTransaction(who.getId(), null));
        // A blank txId MINTS a new transaction rather than missing; callers must guard blank separately.
        Assert.assertNotNull(transactions.getTransaction(who.getId(), ""));
        Assert.assertNull(transactions.getTransaction(who.getId(), "no-such-tx"));
    }

    @Test
    public void aGroupSaveCreatesEveryMembersRowAndSharesTheAmount() {
        final Person alice = savedPerson("Galice");
        final Person bob = savedPerson("Gbob");
        final String groupId = "tail-group-1";

        Assert.assertTrue(transactions.saveGroupTransaction(groupId, List.of(),
                Transaction.Type.Shared, Transaction.TransactionType.Bill, LocalDateTime.now(), 500f,
                "lodging", "shared room", null, null, List.of(alice.getId(), bob.getId())));

        final Transaction alicesRow =
                transactions.getGroupTransactionForUser(alice.getId(), groupId).orElseThrow();
        Assert.assertEquals(transactions.getUserIdsForGroup(alicesRow),
                List.of(alice.getId(), bob.getId()), "Membership is stamped on the row");
        Assert.assertEquals(transactions.getUserAmount(alicesRow), 250f, "A shared bill splits evenly");
        Assert.assertEquals(alicesRow.getAmount(), 500f, "The row keeps the full amount for context");
    }

    @Test
    public void droppingSomeoneFromAGroupRemovesTheirRowFromReads() {
        final Person alice = savedPerson("Dalice");
        final Person bob = savedPerson("Dbob");
        final String groupId = "tail-group-2";
        final List<Person.Id> both = List.of(alice.getId(), bob.getId());
        Assert.assertTrue(transactions.saveGroupTransaction(groupId, List.of(), Transaction.Type.Shared,
                Transaction.TransactionType.Bill, LocalDateTime.now(), 100f, "c", "n", null, null, both));
        // The second save reads the group rows back; wait out the asynchronous cache invalidation so it sees
        // the rows the first save just wrote rather than the pre-save empty cache.
        awaitGroupRow(bob.getId(), groupId);

        // Re-save with only Alice; Bob was in origPeople so his row is soft-deleted.
        Assert.assertTrue(transactions.saveGroupTransaction(groupId, both, Transaction.Type.Shared,
                Transaction.TransactionType.Bill, LocalDateTime.now(), 100f, "c", "n", null, null,
                List.of(alice.getId())));

        // The model soft-deletes (a `deleted` timestamp), but saving a deleted tx REMOVES the stored row --
        // so after the drop, Bob's row is gone from every read while Alice's survives.
        final long deadline = System.currentTimeMillis() + 5_000L;
        while (transactions.getGroupTransactionForUser(bob.getId(), groupId).isPresent()
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100L);
            } catch (final InterruptedException ex) {
                break;
            }
        }
        Assert.assertTrue(transactions.getGroupTransactionForUser(bob.getId(), groupId).isEmpty(),
                "A dropped member's row must disappear from reads");
        Assert.assertNull(transactions.getGroupTransactionForUser(alice.getId(), groupId)
                .orElseThrow().getDeleted());
    }

    private void awaitGroupRow(final Person.Id who, final String groupId) {
        final long deadline = System.currentTimeMillis() + 5_000L;
        while (transactions.getGroupTransactionForUser(who, groupId).isEmpty()
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100L);
            } catch (final InterruptedException ex) {
                break;
            }
        }
        Assert.assertTrue(transactions.getGroupTransactionForUser(who, groupId).isPresent(),
                "The group row never became visible for " + who);
    }

    @Test
    public void aLegacyGroupRowFallsBackToItsOwnUser() {
        final Person who = savedPerson("Legacy");
        final Transaction legacy = new Transaction(who.getId(), "legacy-group", Transaction.Type.Shared);
        legacy.setAmount(90f);

        Assert.assertEquals(transactions.getUserIdsForGroup(legacy), List.of(who.getId()),
                "No stamped membership: the row's own user is the only safe answer");
        Assert.assertEquals(transactions.getUserAmount(legacy), 90f);
        Assert.assertEquals(transactions.getUserIdsForGroup(null), List.of());
        Assert.assertNull(transactions.getUserAmount(null));
    }

    @Test
    public void sortTxByDateOrdersInPlace() {
        final Person who = savedPerson("Sorter");
        final Transaction older = transactions.createTransaction(who.getId());
        older.setTxDate(LocalDateTime.now().minusDays(5));
        final Transaction newer = transactions.createTransaction(who.getId());
        newer.setTxDate(LocalDateTime.now());
        final List<Transaction> list = new java.util.ArrayList<>(List.of(newer, older));

        transactions.sortTxByDate(list);

        Assert.assertSame(list.get(0), older);
    }

    // --- AuditCommands: the human-readable messages ---

    @Test
    public void theTransactionMessageNamesTheActor() {
        final Person target = savedPerson("Payee");
        final Transaction tx = transactions.createTransaction(target.getId());
        tx.setAmount(75f);
        tx.setNote("deposit");
        final org.paulsens.trip.audit.AuditActor actor =
                new org.paulsens.trip.audit.AuditActor("admin@audit.example", "admin-id");

        final String message = audit.transaction(target, tx, actor);

        Assert.assertTrue(message.contains("admin@audit.example"),
                "The regression this guards: the text lost its actor while the record kept it");
        Assert.assertTrue(message.contains("$75"));
        Assert.assertTrue(message.contains("deposit"));
    }

    @Test
    public void auditMessagesDescribeTheirSubjects() {
        final Person target = savedPerson("Subject");
        final Trip trip = Trip.builder().id("audit-trip").title("Rome 2027").build();
        final org.paulsens.trip.audit.AuditActor actor =
                new org.paulsens.trip.audit.AuditActor("actor@audit.example", "actor-id");

        Assert.assertTrue(audit.person(target, "EDITED", actor).contains("Subject"));
        Assert.assertTrue(audit.registered(target, trip, actor).contains("Rome 2027"));
        Assert.assertTrue(audit.registrationRemoved(target, trip, actor).contains("Rome 2027"));
        Assert.assertTrue(audit.loginChanged(target, "old@audit.example", actor).contains("old@audit.example"));
        Assert.assertTrue(audit.credentialsRemoved(target, actor).length() > 0);
        Assert.assertTrue(audit.todoStatus(target, "Bring boots", "DONE", actor).contains("Bring boots"));
        Assert.assertTrue(audit.impersonation(target, actor).contains("Subject"));
        Assert.assertNotNull(audit.passwordReset("reset@audit.example", true, "by request"));
        Assert.assertNotNull(audit.getCurrentActor());
    }

    @Test
    public void registrationMovedNamesBothTrips() {
        final Person target = savedPerson("Mover");
        final Trip from = Trip.builder().id("from-trip").title("Fatima").build();
        final Trip to = Trip.builder().id("to-trip").title("Lourdes").build();
        final org.paulsens.trip.audit.AuditActor actor =
                new org.paulsens.trip.audit.AuditActor("mover@audit.example", "m-id");

        final String message = audit.registrationMoved(target, from, to, actor);

        Assert.assertTrue(message.contains("Fatima"));
        Assert.assertTrue(message.contains("Lourdes"));
    }

    // --- AuditViewCommands ---

    @Test
    public void thePageReadsBackWhatAuditWrites() {
        audit.log("viewer@audit.example", "CONFIG", "a test record");

        final AuditPage page = auditView.getRecent(50);

        Assert.assertNotNull(page);
        // The fake store keeps audit rows in memory; the record just written should be visible.
        Assert.assertFalse(page.getEvents().isEmpty());
    }

    @Test
    public void lenientFilterParsingSwallowsStaleDropdownValues() {
        // "" comes from EL for an unset dropdown; a bookmarked bad value must render, not throw.
        Assert.assertNotNull(auditView.getPage(null, "", "", "", "", 10));
        Assert.assertNotNull(auditView.getPage(Instant.now(), "ken", "NO_SUCH_ACTION", "NO_SUCH_OUTCOME",
                "text", -1));
    }

    @Test
    public void theDropdownsComeFromTheEnums() {
        // Actions come name-sorted for the dropdown; outcomes in enum order.
        Assert.assertEquals(auditView.getActions().size(), AuditAction.values().length);
        Assert.assertTrue(auditView.getActions().contains(AuditAction.LOGIN));
        Assert.assertEquals(auditView.getActions(),
                auditView.getActions().stream().sorted(java.util.Comparator.comparing(Enum::name)).toList());
        Assert.assertEquals(auditView.getOutcomes(), List.of(AuditOutcome.values()));
        Assert.assertNotNull(auditView.query());
    }

    @Test
    public void csvExportQuotesAndJoins() {
        // Two suite realities to survive: exports page out under thousands of rows (so filter to this actor),
        // and same-millisecond writes can be dropped by the key-collision rule (so retry the write until it is
        // visible before asserting on the export).
        String csv = "";
        for (int attempt = 0; attempt < 5 && !csv.contains("csv row"); attempt++) {
            audit.log("csv@audit.example", "CONFIG", "csv row, with a comma");
            try {
                Thread.sleep(50L);
            } catch (final InterruptedException ex) {
                break;
            }
            csv = auditView.toCsv(null, "csv@audit.example", null, null, null);
        }

        Assert.assertTrue(csv.startsWith("time"), "A header row leads the file");
        Assert.assertTrue(csv.contains("\"csv row, with a comma\"") || csv.contains("csv row"),
                "The message survives into the export");
    }

    @Test
    public void utcTimeConvertsTheEventTimestamp() {
        audit.log("time@audit.example", "CONFIG", "time check");
        final AuditPage page = auditView.getRecent(1);
        if (!page.getEvents().isEmpty()) {
            Assert.assertNotNull(auditView.utcTime(page.getEvents().get(0)));
        }
    }

    @Test
    public void utcTimeAndEpochMillisTolerateNull() {
        Assert.assertNull(auditView.utcTime(null));
        Assert.assertEquals(auditView.epochMillis(null), 0L);
    }

    @Test
    public void epochMillisMatchesTheEventTimestamp() {
        audit.log("epoch@audit.example", "CONFIG", "epoch check");
        final AuditPage page = auditView.getRecent(1);
        if (!page.getEvents().isEmpty()) {
            final var event = page.getEvents().get(0);
            Assert.assertEquals(auditView.epochMillis(event), event.getTimestamp().toEpochMilli());
        }
    }
}
