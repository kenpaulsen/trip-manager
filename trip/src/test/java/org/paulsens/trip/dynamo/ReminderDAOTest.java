package org.paulsens.trip.dynamo;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.ReminderSend;
import org.paulsens.trip.model.TripReminder;
import org.paulsens.trip.model.TripReminderPlan;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

/** The {@code trip_reminders} table through the DAO facade against the in-memory fake. */
public class ReminderDAOTest {
    private static final LocalDate DAY = LocalDate.of(2027, 3, 3);

    private static TripReminderPlan plan() {
        return TripReminderPlan.builder().tripId("trip-" + RandomData.genAlpha(10)).orgId("org")
                .reminders(List.of(TripReminder.builder().id("r1").name("One").subject("s").body("b").build()))
                .build();
    }

    private static ReminderSend send(final String tripId, final String personId) {
        return ReminderSend.builder().tripId(tripId).reminderId("r1").sendDate(DAY).personId(Person.Id.from(personId))
                .reminderName("One").personName(personId).toEmail(personId + "@example.com").subject("s")
                .at(Instant.parse("2027-03-03T17:00:00Z")).build();
    }

    @Test
    public void planSaveGetAndVersioning() throws IOException {
        final TripReminderPlan plan = plan();
        assertTrue(DAO.getInstance().saveReminderPlan(plan));
        assertEquals(plan.getVersion(), 1L);
        final TripReminderPlan read = DAO.getInstance().getReminderPlan(plan.getTripId()).orElseThrow();
        assertEquals(read, plan);

        read.setMailFrom("a@example.com");
        assertTrue(DAO.getInstance().saveReminderPlan(read));
        plan.setMailFrom("b@example.com");
        assertThrows(ConditionalCheckFailedException.class, () -> DAO.getInstance().saveReminderPlan(plan));
        assertEquals(plan.getVersion(), 1L, "A rejected save restores the caller's version");
        assertEquals(DAO.getInstance().getReminderPlan(plan.getTripId()).orElseThrow().getMailFrom(),
                "a@example.com");
    }

    @Test
    public void aSecondCreateForTheSameTripIsRejected() throws IOException {
        final TripReminderPlan first = plan();
        assertTrue(DAO.getInstance().saveReminderPlan(first));
        final TripReminderPlan again = TripReminderPlan.builder().tripId(first.getTripId()).build();
        assertThrows(ConditionalCheckFailedException.class, () -> DAO.getInstance().saveReminderPlan(again));
        assertEquals(again.getVersion(), 0L);
    }

    @Test
    public void anOversizedPlanIsRefusedBeforeTheStore() {
        final TripReminderPlan huge = plan();
        huge.getReminders().add(TripReminder.builder().id("big").body("x".repeat(400_000)).build());
        assertThrows(IllegalArgumentException.class, () -> DAO.getInstance().saveReminderPlan(huge));
        assertEquals(huge.getVersion(), 0L);
        assertTrue(DAO.getInstance().getReminderPlan(huge.getTripId()).isEmpty());
    }

    @Test
    public void blankTripIdsReadNothing() {
        assertTrue(DAO.getInstance().getReminderPlan(null).isEmpty());
        assertTrue(DAO.getInstance().getReminderPlan(" ").isEmpty());
        assertTrue(DAO.getInstance().getReminderSends(null).isEmpty());
        assertTrue(DAO.getInstance().getReminderSends("no-such-trip").isEmpty());
    }

    @Test
    public void aSendIsClaimedOnceThenRecordedAndListedBesideThePlan() throws IOException {
        final TripReminderPlan plan = plan();
        assertTrue(DAO.getInstance().saveReminderPlan(plan));
        final ReminderSend ann = send(plan.getTripId(), "ann");
        assertTrue(DAO.getInstance().claimReminderSend(ann));
        assertFalse(DAO.getInstance().claimReminderSend(ann), "The same traveler, reminder and date: once");
        assertTrue(DAO.getInstance().claimReminderSend(send(plan.getTripId(), "bob")));
        assertTrue(DAO.getInstance().claimReminderSend(ann.withSendDate(DAY.plusDays(1))),
                "Another day is another send");

        assertTrue(DAO.getInstance().saveReminderSend(ann.withOutcome(ReminderSend.Outcome.SENT)));
        final List<ReminderSend> sends = DAO.getInstance().getReminderSends(plan.getTripId());
        assertEquals(sends.size(), 3, "The plan row is not a send");
        assertEquals(sends.stream().filter(s -> s.getOutcome() == ReminderSend.Outcome.SENT).count(), 1);
        assertTrue(DAO.getInstance().getReminderPlan(plan.getTripId()).isPresent());
    }

    @Test
    public void aClearedSendCanBeClaimedAgain() throws IOException {
        final ReminderSend ann = send("trip-" + RandomData.genAlpha(10), "ann");
        assertTrue(DAO.getInstance().claimReminderSend(ann));
        assertTrue(DAO.getInstance().deleteReminderSend(ann));
        assertTrue(DAO.getInstance().claimReminderSend(ann));
    }

    @Test
    public void aDeletedAccountLeavesTheHistoryWithoutItsNameOrAddress() throws IOException {
        final String tripId = "trip-" + RandomData.genAlpha(10);
        final ReminderSend ann = send(tripId, "ann");
        // Bob is Ann's dependent: his row mailed HER address and his other manager's.
        final ReminderSend bob = send(tripId, "bob").withToEmail("ann@example.com, dad@example.com");
        final ReminderSend cat = send(tripId, "cat");
        // Joann's address merely ENDS with Ann's.
        final ReminderSend joann = send(tripId, "joann");
        for (final ReminderSend send : List.of(ann, bob, cat, joann)) {
            assertTrue(DAO.getInstance().claimReminderSend(send));
        }
        assertEquals(DAO.getInstance().scrubReminderSends(tripId, Person.Id.from("ann"), "Ann@Example.com",
                "Deleted Account"), 2);
        final ReminderSend hers = row(tripId, "ann");
        assertEquals(hers.getPersonName(), "Deleted Account");
        assertEquals(hers.getToEmail(), "");
        final ReminderSend his = row(tripId, "bob");
        assertEquals(his.getPersonName(), "bob", "Bob is still Bob");
        assertEquals(his.getToEmail(), "dad@example.com", "Her address is gone; his other manager's stays");
        assertEquals(row(tripId, "cat").getToEmail(), "cat@example.com");
        assertEquals(row(tripId, "joann").getToEmail(), "joann@example.com", "A whole address, never a substring");
        assertFalse(DAO.getInstance().claimReminderSend(ann), "The row still stands: the reminder is not resent");
        assertEquals(DAO.getInstance().scrubReminderSends(tripId, null, null, "Deleted Account"), 0);
        assertEquals(DAO.getInstance().scrubReminderSends(tripId, null, " ", "Deleted Account"), 0);
    }

    private static ReminderSend row(final String tripId, final String personId) {
        return DAO.getInstance().getReminderSends(tripId).stream()
                .filter(s -> s.getPersonId().getValue().equals(personId)).findFirst().orElseThrow();
    }

    @Test
    public void deletingATripRemovesItsPlanAndItsSends() throws IOException {
        final TripReminderPlan plan = plan();
        assertTrue(DAO.getInstance().saveReminderPlan(plan));
        assertTrue(DAO.getInstance().claimReminderSend(send(plan.getTripId(), "ann")));
        assertTrue(DAO.getInstance().deleteRemindersForTrip(plan.getTripId()));
        assertTrue(DAO.getInstance().getReminderPlan(plan.getTripId()).isEmpty());
        assertTrue(DAO.getInstance().getReminderSends(plan.getTripId()).isEmpty());
        assertTrue(DAO.getInstance().deleteRemindersForTrip(plan.getTripId()), "Deleting twice is idempotent");
    }
}
