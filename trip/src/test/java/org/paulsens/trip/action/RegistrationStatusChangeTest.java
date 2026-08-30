package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * The admin status-change state machine behind the trip-registrations page's status menu
 * ({@code RegistrationCommands.applyStatusChange}): which transitions the menu offers, and that each
 * applied transition runs the same checks and side effects the old per-status buttons ran -- canJoin
 * gating approval, roster membership following CONFIRMED, audit on every change, the internal notice on
 * cancellation, and the registrant-facing approval email only when the admin asked for it. The
 * Pending -> Not Registered path is the transition the buttons never allowed (removal used to require
 * approving first).
 */
public class RegistrationStatusChangeTest {

    private static final String PENDING = Registration.Status.PENDING.getDescription();
    private static final String CONFIRMED = Registration.Status.CONFIRMED.getDescription();
    private static final String NOT_REGISTERED = Registration.Status.NOT_REGISTERED.getDescription();

    private MailCommands mail;
    private AuditCommands audit;
    private MailAddressCommands addresses;
    private RegistrationCommands reg;

    /** Fresh mocks per method: TestNG shares the instance, and interactions must not leak across tests. */
    @BeforeMethod
    public void freshCollaborators() {
        mail = Mockito.mock(MailCommands.class);
        audit = Mockito.mock(AuditCommands.class);
        addresses = Mockito.mock(MailAddressCommands.class);
        reg = new RegistrationCommands(() -> null, TripCommands::new, () -> audit, () -> mail,
                () -> addresses);
    }

    @Test
    public void menuOffersTheReachableStatusesWithTheCurrentOneFirst() {
        assertEquals(reg.allowedStatuses(PENDING), List.of(PENDING, CONFIRMED, NOT_REGISTERED));
        assertEquals(reg.allowedStatuses(CONFIRMED), List.of(CONFIRMED, PENDING, NOT_REGISTERED));
        assertEquals(reg.allowedStatuses(NOT_REGISTERED), List.of(NOT_REGISTERED, PENDING));
        assertEquals(reg.allowedStatuses("Bogus"), List.of("Bogus"));
        assertEquals(reg.allowedStatuses(null), List.of(""));
    }

    @Test
    public void approvingAPendingRowConfirmsJoinsTheRosterAndEmails() throws IOException {
        final Trip trip = newTrip(10);
        final Person traveler = newPerson(true);
        seedRegistration(trip, traveler, PENDING);
        Mockito.when(addresses.fromFor("reg.mail.from")).thenReturn("from@example.com");
        Mockito.when(addresses.replyToFor(ArgumentMatchers.eq("reg.mail.replyTo"), ArgumentMatchers.any()))
                .thenReturn("reply@example.com");

        assertTrue(reg.applyStatusChange(trip.getId(), traveler.getId(), CONFIRMED, true));

        assertEquals(storedStatus(trip, traveler), Registration.Status.CONFIRMED);
        assertTrue(rosterHas(trip, traveler), "Approval must add the traveler to the trip roster");
        Mockito.verify(audit).registrationApproved(
                ArgumentMatchers.argThat(p -> traveler.getId().equals(p.getId())), ArgumentMatchers.any());
        Mockito.verify(mail).sendManagedTemplate(
                ArgumentMatchers.eq("registration-approved"), ArgumentMatchers.anyMap(),
                ArgumentMatchers.eq(traveler.getEmail()), ArgumentMatchers.eq("from@example.com"),
                ArgumentMatchers.eq("reply@example.com"), ArgumentMatchers.any());
    }

    @Test
    public void approvingWithTheEmailBoxUncheckedSendsNothing() throws IOException {
        final Trip trip = newTrip(10);
        final Person traveler = newPerson(true);
        seedRegistration(trip, traveler, PENDING);

        assertTrue(reg.applyStatusChange(trip.getId(), traveler.getId(), CONFIRMED, false));

        assertEquals(storedStatus(trip, traveler), Registration.Status.CONFIRMED);
        Mockito.verifyNoInteractions(mail);
    }

    @Test
    public void approvalIsRefusedWhenTheTravelerCannotJoin() throws IOException {
        final Trip trip = newTrip(-10);      // already started: canJoin is false for everyone
        final Person traveler = newPerson(true);
        seedRegistration(trip, traveler, PENDING);

        assertFalse(reg.applyStatusChange(trip.getId(), traveler.getId(), CONFIRMED, true));

        assertEquals(storedStatus(trip, traveler), Registration.Status.PENDING);
        assertFalse(rosterHas(trip, traveler), "A refused approval must not touch the roster");
        Mockito.verifyNoInteractions(audit, mail);
    }

    @Test
    public void aPendingRowCancelsWithoutApprovingFirst() throws IOException {
        final Trip trip = newTrip(10);
        final Person traveler = newPerson(true);
        seedRegistration(trip, traveler, PENDING);
        Mockito.when(audit.registrationRemoved(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn("audit-msg");
        Mockito.when(addresses.fromFor("reg.notify.from")).thenReturn("notify-from@example.com");
        Mockito.when(addresses.recipientFor(ArgumentMatchers.eq("reg.notify.email"), ArgumentMatchers.any()))
                .thenReturn("notify@example.com");

        assertTrue(reg.applyStatusChange(trip.getId(), traveler.getId(), NOT_REGISTERED, false));

        assertEquals(storedStatus(trip, traveler), Registration.Status.NOT_REGISTERED);
        assertFalse(rosterHas(trip, traveler), "A Pending traveler was never on the roster");
        Mockito.verify(mail).send(
                ArgumentMatchers.eq("notify-from@example.com"), ArgumentMatchers.eq("notify@example.com"),
                ArgumentMatchers.isNull(), ArgumentMatchers.isNull(),
                ArgumentMatchers.eq("Registration Cancelled - " + trip.getTitle()),
                ArgumentMatchers.eq("audit-msg"), ArgumentMatchers.any());
    }

    @Test
    public void cancellingAConfirmedRowLeavesTheRoster() throws IOException {
        final Trip trip = newTrip(10);
        final Person traveler = newPerson(true);
        seedRegistration(trip, traveler, CONFIRMED);
        addToRoster(trip, traveler);

        assertTrue(reg.applyStatusChange(trip.getId(), traveler.getId(), NOT_REGISTERED, false));

        assertEquals(storedStatus(trip, traveler), Registration.Status.NOT_REGISTERED);
        assertFalse(rosterHas(trip, traveler), "Cancelling a Confirmed row must leave the roster");
        Mockito.verify(audit).registrationRemoved(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    public void unApprovingReturnsToPendingAndLeavesTheRoster() throws IOException {
        final Trip trip = newTrip(10);
        final Person traveler = newPerson(true);
        seedRegistration(trip, traveler, CONFIRMED);
        addToRoster(trip, traveler);

        assertTrue(reg.applyStatusChange(trip.getId(), traveler.getId(), PENDING, false));

        assertEquals(storedStatus(trip, traveler), Registration.Status.PENDING);
        assertFalse(rosterHas(trip, traveler), "Un-approving must leave the roster");
        Mockito.verify(audit).registrationStatusChanged(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.eq(CONFIRMED), ArgumentMatchers.eq(PENDING));
        Mockito.verifyNoInteractions(mail);
    }

    @Test
    public void aRemovedRowCanBeRevivedToPending() throws IOException {
        final Trip trip = newTrip(10);
        final Person traveler = newPerson(true);
        seedRegistration(trip, traveler, NOT_REGISTERED);

        assertTrue(reg.applyStatusChange(trip.getId(), traveler.getId(), PENDING, false));

        assertEquals(storedStatus(trip, traveler), Registration.Status.PENDING);
        assertFalse(rosterHas(trip, traveler), "Reviving to Pending must not touch the roster");
        Mockito.verify(audit).registrationStatusChanged(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.eq(NOT_REGISTERED), ArgumentMatchers.eq(PENDING));
    }

    @Test
    public void aRemovedRowCannotBeConfirmedDirectly() throws IOException {
        final Trip trip = newTrip(10);
        final Person traveler = newPerson(true);
        seedRegistration(trip, traveler, NOT_REGISTERED);

        assertFalse(reg.applyStatusChange(trip.getId(), traveler.getId(), CONFIRMED, true));

        assertEquals(storedStatus(trip, traveler), Registration.Status.NOT_REGISTERED);
        assertFalse(rosterHas(trip, traveler));
        Mockito.verifyNoInteractions(audit, mail);
    }

    @Test
    public void aRowAlreadyAtTheTargetIsANoOp() throws IOException {
        final Trip trip = newTrip(10);
        final Person traveler = newPerson(true);
        seedRegistration(trip, traveler, CONFIRMED);

        assertFalse(reg.applyStatusChange(trip.getId(), traveler.getId(), CONFIRMED, true));

        Mockito.verifyNoInteractions(audit, mail);
    }

    private Trip newTrip(final int startsInDays) throws IOException {
        final Trip trip = Trip.builder()
                .id("status-" + RandomData.genAlpha(8))
                .title("Status Trip " + RandomData.genAlpha(4))
                .startDate(LocalDateTime.now().plusDays(startsInDays))
                .endDate(LocalDateTime.now().plusDays(startsInDays + 10))
                .build();
        assertTrue(DAO.getInstance().saveTrip(trip));
        return trip;
    }

    private Person newPerson(final boolean withEmail) throws IOException {
        final Person person = Person.builder()
                .first("Stat").last(RandomData.genAlpha(8))
                .email(withEmail ? "status." + RandomData.genAlpha(10) + "@example.com" : null)
                .build();
        assertTrue(DAO.getInstance().savePerson(person));
        return person;
    }

    private void seedRegistration(final Trip trip, final Person traveler, final String status)
            throws IOException {
        assertTrue(DAO.getInstance().saveRegistration(
                new Registration(trip.getId(), traveler.getId()).withStatusString(status)));
    }

    private void addToRoster(final Trip trip, final Person traveler) throws IOException {
        final Trip stored = DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow();
        stored.getPeople().add(traveler.getId());
        assertTrue(DAO.getInstance().saveTrip(stored));
    }

    private Registration.Status storedStatus(final Trip trip, final Person traveler) {
        return DAO.getInstance().getRegistration(trip.getId(), traveler.getId(), Cached.NO)
                .orElseThrow().getStatus();
    }

    private boolean rosterHas(final Trip trip, final Person traveler) {
        return DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow()
                .getPeople().contains(traveler.getId());
    }
}
