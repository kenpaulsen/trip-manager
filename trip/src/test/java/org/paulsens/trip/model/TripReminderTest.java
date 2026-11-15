package org.paulsens.trip.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/** The approval rule: an approval covers exactly one wording, audience, schedule and resolved date. */
public class TripReminderTest {
    private static final LocalDateTime START = LocalDateTime.of(2027, 6, 1, 8, 0);
    private static final Person.Id ADA = Person.Id.from("ada");
    private static final Instant AT = Instant.parse("2027-03-01T10:00:00Z");

    private static Trip trip() {
        final Trip trip = Trip.builder().id("t1").title("Rome").startDate(START).endDate(START.plusDays(9)).build();
        trip.getCheckout().setBalanceDueOn(LocalDate.of(2027, 5, 1));
        return trip;
    }

    private static TripReminder draft() {
        return TripReminder.builder().id("r1").kind(TripReminder.Kind.COUNTDOWN).name("One week")
                .anchor(TripReminder.Anchor.TRIP_START).offsetDays(-7).subject("Soon").body("<p>Hi</p>").build();
    }

    @Test
    public void defaultsAreADraftCustomMessage() {
        final TripReminder blank = TripReminder.builder().build();
        assertEquals(blank.getKind(), TripReminder.Kind.CUSTOM);
        assertEquals(blank.getStatus(), TripReminder.Status.DRAFT);
        assertEquals(blank.getAudience(), TripReminder.Audience.ALL_CONFIRMED);
        assertEquals(blank.getSubject(), "");
        assertFalse(blank.isPush());
        assertFalse(blank.isApprovedFor(trip()));
    }

    @Test
    public void aBuiltInKindKeepsItsOwnAudience() {
        final TripReminder balance = TripReminder.builder().kind(TripReminder.Kind.BALANCE_DUE)
                .audience(TripReminder.Audience.PENDING).build();
        assertEquals(balance.getAudience(), TripReminder.Audience.WITH_BALANCE);
        final TripReminder custom = TripReminder.builder().audience(TripReminder.Audience.PENDING).build();
        assertEquals(custom.getAudience(), TripReminder.Audience.PENDING);
    }

    @Test
    public void sendDateCountsFromItsAnchor() {
        final Trip trip = trip();
        assertEquals(draft().sendDate(trip), LocalDate.of(2027, 5, 25));
        assertEquals(draft().withAnchor(TripReminder.Anchor.TRIP_END).withOffsetDays(2).sendDate(trip),
                LocalDate.of(2027, 6, 12));
        assertEquals(draft().withAnchor(TripReminder.Anchor.BALANCE_DUE_DATE).withOffsetDays(1).sendDate(trip),
                LocalDate.of(2027, 5, 2));
        final LocalDate fixed = LocalDate.of(2027, 4, 4);
        assertEquals(draft().withAnchor(TripReminder.Anchor.FIXED_DATE).withFixedDate(fixed).sendDate(null), fixed);
    }

    @Test
    public void aMissingAnchorDateMeansNoSendDateAndNoApproval() {
        // The builder defaults both dates, so they are cleared after the build.
        final Trip noDates = Trip.builder().id("t2").build();
        noDates.setStartDate(null);
        noDates.setEndDate(null);
        assertNull(draft().sendDate(noDates));
        assertNull(draft().sendDate(null));
        assertNull(draft().withAnchor(TripReminder.Anchor.TRIP_END).sendDate(noDates));
        assertNull(draft().withAnchor(TripReminder.Anchor.BALANCE_DUE_DATE).sendDate(noDates));
        assertNull(draft().withAnchor(TripReminder.Anchor.FIXED_DATE).sendDate(noDates));
        assertNull(draft().withAnchor(TripReminder.Anchor.TRIP_END).sendDate(null));
        final TripReminder tried = draft().approved(noDates, ADA, AT);
        assertEquals(tried.getStatus(), TripReminder.Status.DRAFT, "No date, nothing to approve");
        assertFalse(tried.isApprovedFor(noDates));
    }

    @Test
    public void approvalHoldsOnlyForExactlyWhatWasApproved() {
        final Trip trip = trip();
        final TripReminder approved = draft().approved(trip, ADA, AT);
        assertTrue(approved.isApprovedFor(trip));
        assertFalse(approved.isApprovalStale(trip));
        assertEquals(approved.getApprovedBy(), ADA);
        assertEquals(approved.getApprovedAt(), AT);

        // Any change that reaches a traveler breaks it, with the status field untouched.
        assertFalse(approved.withBody("<p>Hello</p>").isApprovedFor(trip));
        assertFalse(approved.withSubject("Sooner").isApprovedFor(trip));
        assertFalse(approved.withPushText("Ping").isApprovedFor(trip));
        assertFalse(approved.withOffsetDays(-6).isApprovedFor(trip));
        assertFalse(approved.withAnchor(TripReminder.Anchor.TRIP_END).isApprovedFor(trip));
        assertTrue(approved.withBody("<p>Hello</p>").isApprovalStale(trip));
        // The name is the facilitator's label for the row, not something a traveler reads.
        assertTrue(approved.withName("Seven days").isApprovedFor(trip));
    }

    @Test
    public void movingTheTripsDateBreaksTheApproval() {
        final Trip trip = trip();
        final TripReminder approved = draft().approved(trip, ADA, AT);
        trip.setStartDate(START.plusDays(3));
        assertFalse(approved.isApprovedFor(trip), "It would now go out on a day nobody approved");
        assertTrue(approved.isApprovalStale(trip));
        trip.setStartDate(START);
        assertTrue(approved.isApprovedFor(trip));
    }

    @Test
    public void aCustomAudienceIsPartOfTheApproval() {
        final Trip trip = trip();
        final TripReminder approved = TripReminder.builder().id("c").subject("s").body("b").build()
                .approved(trip, ADA, AT);
        assertTrue(approved.isApprovedFor(trip));
        assertFalse(approved.withAudience(TripReminder.Audience.PENDING).isApprovedFor(trip));
    }

    @Test
    public void withdrawingClearsEveryTraceOfTheApproval() {
        final TripReminder back = draft().approved(trip(), ADA, AT).asDraft();
        assertEquals(back.getStatus(), TripReminder.Status.DRAFT);
        assertNull(back.getApprovedBy());
        assertNull(back.getApprovedAt());
        assertNull(back.getApprovedHash());
    }

    @Test
    public void aForgedStatusWithoutTheHashIsNotAnApproval() {
        final Trip trip = trip();
        assertFalse(draft().withStatus(TripReminder.Status.APPROVED).isApprovedFor(trip));
        assertFalse(draft().withStatus(TripReminder.Status.APPROVED).withApprovedHash("nope").isApprovedFor(trip));
    }

    @Test
    public void scheduleTextReadsAsASentence() {
        assertEquals(draft().getScheduleText(), "7 days before the trip start");
        assertEquals(draft().withOffsetDays(1).getScheduleText(), "1 day after the trip start");
        assertEquals(draft().withOffsetDays(0).getScheduleText(), "On the trip start");
        assertEquals(draft().withAnchor(TripReminder.Anchor.FIXED_DATE).getScheduleText(), "No date chosen");
        assertEquals(draft().withAnchor(TripReminder.Anchor.FIXED_DATE).withFixedDate(LocalDate.of(2027, 4, 4))
                .getScheduleText(), "On 2027-04-04");
        assertEquals(TripReminder.Kind.PASSPORT.getLabel(), "Passport");
        assertEquals(TripReminder.Audience.PENDING.getLabel(), "People whose registration is awaiting approval");
        assertEquals(TripReminder.Anchor.TRIP_END.getLabel(), "the trip end");
    }

    @Test
    public void thePlanFindsReplacesAndRemovesById() {
        assertEquals(TripReminderPlan.builder().build(), new TripReminderPlan());
        final TripReminderPlan plan = new TripReminderPlan();
        plan.put(draft());
        plan.put(draft().withId("r2"));
        plan.put(draft().withName("Renamed"));
        assertEquals(plan.getReminders().size(), 2);
        assertEquals(plan.reminder("r1").getName(), "Renamed");
        assertNull(plan.reminder("nope"));
        assertTrue(plan.remove("r1"));
        assertFalse(plan.remove("r1"));
    }

    @Test
    public void aSendRowIsKeyedByReminderDateAndTraveler() {
        final ReminderSend send = ReminderSend.builder().tripId("t1").reminderId("r1")
                .sendDate(LocalDate.of(2027, 5, 25)).personId(ADA).build();
        assertEquals(send.getSortKey(), "SEND#r1#2027-05-25#ada");
        assertEquals(send.getOutcome(), ReminderSend.Outcome.CLAIMED);
        assertEquals(send.withPersonId(null).getSortKey(), "SEND#r1#2027-05-25#");
        assertEquals(ReminderSend.Outcome.NO_ADDRESS.getLabel(), "No email address");
    }
}
