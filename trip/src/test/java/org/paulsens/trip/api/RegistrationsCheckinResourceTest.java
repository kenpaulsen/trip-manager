package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.paulsens.trip.action.AuditCommands;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.action.PrivilegeCommands;
import org.paulsens.trip.action.RegistrationCommands;
import org.paulsens.trip.action.TripCommands;
import org.paulsens.trip.api.dto.CheckinCountsDto;
import org.paulsens.trip.api.dto.RegistrationDto;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.OptionChoice;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.RegistrationOption;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.security.TicketCodes;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The check-in endpoints on {@link RegistrationsResource} ({@code docs/checkin.md}), against the in-memory store with
 * the real trip, person and privilege beans: the app's scan flow (ticket, then check in), the 409s, the clear, the
 * counts, and the bearer secret staying out of every listing.
 */
public class RegistrationsCheckinResourceTest extends ResourceTestSupport {
    private Trip trip;
    private Person ann;
    private Person pat;
    private Person stranger;
    private String annCode;
    private Person.Id staffId;
    private Person.Id managerId;
    private Person.Id financeId;

    @BeforeClass
    public void seed() {
        FakeData.initFakeData();
        FakeData.addFakeData();
    }

    @BeforeMethod
    public void ownTheTrip() throws IOException {
        bind(TripCommands.class, new TripCommands());
        bind(PersonCommands.class, new PersonCommands());
        bind(AuditCommands.class, new AuditCommands());
        final RegistrationOption admission = new RegistrationOption(1, "Admission", "", true,
                RegistrationOption.QuestionType.CHOICE, true, List.of(
                        new OptionChoice("full", "Full weekend", 150_00, null, null, List.of(), null)));
        trip = Trip.builder().id(UUID.randomUUID().toString()).title("API Conference")
                .startDate(LocalDateTime.now().plusDays(3)).endDate(LocalDateTime.now().plusDays(5))
                .regOptions(new ArrayList<>(List.of(admission))).people(new ArrayList<>()).build();
        trip.setOrgId(FakeData.CFPW_ORG_ID);
        Assert.assertTrue(DAO.getInstance().saveTrip(trip));
        ann = person("Ann", "Api");
        pat = person("Pat", "Api");
        stranger = person("Sam", "Stranger");
        final Registration confirmed = RegistrationCommands.withTicket(new Registration(trip.getId(), ann.getId(),
                null, Registration.Status.CONFIRMED, new HashMap<>(Map.of("1", "full"))));
        annCode = confirmed.getTicketCode();
        Assert.assertTrue(DAO.getInstance().saveRegistration(confirmed));
        Assert.assertTrue(DAO.getInstance().saveRegistration(new Registration(trip.getId(), pat.getId(), null,
                Registration.Status.PENDING, new HashMap<>())));
        staffId = grant(PrivilegeCommands.CHECKIN_STAFF);
        managerId = grant(PrivilegeCommands.TRIP_MGR);
        financeId = grant(PrivilegeCommands.TRIP_FIN_VIEW);
    }

    private static Person person(final String first, final String last) throws IOException {
        final Person person = new Person();
        person.setFirst(first);
        person.setLast(last);
        Assert.assertTrue(DAO.getInstance().savePerson(person));
        return person;
    }

    private Person.Id grant(final String base) {
        final Person.Id who = Person.Id.newInstance();
        final PrivilegeCommands priv = new PrivilegeCommands();
        Assert.assertTrue(priv.savePrivilege(priv.getOrCreate(base, trip.getId(), base).withNewPerson(who), null));
        return who;
    }

    /** One resource per caller: a resource memoizes its caller on first use, as JAX-RS builds one per request. */
    private RegistrationsResource as(final Person.Id who) {
        signedInAs(who);
        return resource(new RegistrationsResource());
    }

    private Registration stored(final Person who) {
        return DAO.getInstance().getRegistration(trip.getId(), who.getId(), Cached.NO).orElseThrow();
    }

    @Test
    public void theScanFlowFindsTheTicketThenChecksIn() {
        final Response found = as(staffId).ticket(trip.getId(), TicketCodes.grouped(annCode).toLowerCase());
        assertOk(found);
        final RegistrationDto dto = (RegistrationDto) found.getEntity();
        Assert.assertEquals(dto.userId(), ann.getId().getValue());
        Assert.assertEquals(dto.ticketCode(), annCode);
        Assert.assertTrue(dto.ticketUrl().startsWith("https://") && dto.ticketUrl().endsWith("&t=" + annCode),
                dto.ticketUrl());
        Assert.assertEquals(dto.registrationType(), "Full weekend");
        Assert.assertEquals(dto.name(), "Ann Api");
        Assert.assertNull(dto.options(), "check-in staff are not trip staff: no answers");

        final Response checked = as(staffId).checkIn(trip.getId(), ann.getId().getValue(), CSRF_OK);
        assertOk(checked);
        Assert.assertNotNull(((RegistrationDto) checked.getEntity()).checkedInAt());
        Assert.assertEquals(((RegistrationDto) checked.getEntity()).checkedInBy(), staffId.getValue());
        assertError(as(staffId).checkIn(trip.getId(), ann.getId().getValue(), CSRF_OK), 409,
                ApiErrors.ALREADY_CHECKED_IN);
    }

    @Test
    public void aManagerReadsTheAnswersOnAScan() {
        final RegistrationDto dto = (RegistrationDto) as(managerId).ticket(trip.getId(), annCode).getEntity();
        Assert.assertEquals(dto.options().get("1"), "full");
    }

    @Test
    public void scanningRefusesStrangersAndUnknownTickets() {
        assertError(as(stranger.getId()).ticket(trip.getId(), annCode), 403, ApiErrors.FORBIDDEN);
        assertError(as(staffId).ticket(trip.getId(), TicketCodes.mint()), 404, ApiErrors.NOT_FOUND);
        assertError(as(staffId).ticket(trip.getId(), annCode.substring(0, 10)), 404, ApiErrors.NOT_FOUND);
        assertThrown(() -> as(staffId).ticket("no-such-trip", annCode), 404, ApiErrors.NOT_FOUND);
    }

    @Test
    public void checkInAnswersItsRefusals() {
        assertError(as(staffId).checkIn(trip.getId(), pat.getId().getValue(), CSRF_OK), 409, ApiErrors.NOT_CONFIRMED);
        assertError(as(staffId).checkIn(trip.getId(), stranger.getId().getValue(), CSRF_OK), 404,
                ApiErrors.NOT_FOUND);
        assertError(as(stranger.getId()).checkIn(trip.getId(), ann.getId().getValue(), CSRF_OK), 403,
                ApiErrors.FORBIDDEN);
        assertError(as(staffId).checkIn(trip.getId(), ann.getId().getValue(), null), 403, ApiErrors.CSRF);
        Assert.assertNull(stored(ann).getCheckedInAt(), "nothing refused wrote a stamp");
        assertThrown(() -> as(staffId).checkIn(trip.getId(), Person.Id.newInstance().getValue(), CSRF_OK), 404,
                ApiErrors.NOT_FOUND);
    }

    @Test
    public void onlyAManagerClearsAndClearingIsIdempotent() {
        assertOk(as(staffId).checkIn(trip.getId(), ann.getId().getValue(), CSRF_OK));

        assertError(as(staffId).clearCheckIn(trip.getId(), ann.getId().getValue(), CSRF_OK), 403,
                ApiErrors.FORBIDDEN);
        assertError(as(managerId).clearCheckIn(trip.getId(), ann.getId().getValue(), null), 403, ApiErrors.CSRF);
        final Response cleared = as(managerId).clearCheckIn(trip.getId(), ann.getId().getValue(), CSRF_OK);
        assertOk(cleared);
        Assert.assertNull(((RegistrationDto) cleared.getEntity()).checkedInAt());
        Assert.assertNull(stored(ann).getCheckedInAt());
        assertOk(as(managerId).clearCheckIn(trip.getId(), ann.getId().getValue(), CSRF_OK));
        assertError(as(managerId).clearCheckIn(trip.getId(), stranger.getId().getValue(), CSRF_OK), 404,
                ApiErrors.NOT_FOUND);
    }

    @Test
    public void lookupListsWithoutTheTicket() {
        final Response response = as(staffId).lookup(trip.getId(), "api");
        assertOk(response);
        final List<?> rows = (List<?>) response.getEntity();
        Assert.assertEquals(rows.size(), 2, "the confirmed and the pending");
        final RegistrationDto first = (RegistrationDto) rows.getFirst();
        Assert.assertEquals(first.name(), "Api, Ann");
        Assert.assertEquals(first.status(), "CONFIRMED");
        Assert.assertEquals(first.registrationType(), "Full weekend");
        Assert.assertNull(first.ticketCode(), "a listing never carries the bearer secret");
        Assert.assertNull(first.options());
        Assert.assertEquals(((List<?>) as(staffId).lookup(trip.getId(), annCode).getEntity()).size(), 1);
        assertError(as(stranger.getId()).lookup(trip.getId(), "api"), 403, ApiErrors.FORBIDDEN);
    }

    @Test
    public void countsShowMoneyOnlyToFinanceViewers() {
        assertOk(as(staffId).checkIn(trip.getId(), ann.getId().getValue(), CSRF_OK));

        final CheckinCountsDto staffView = (CheckinCountsDto) as(staffId).counts(trip.getId()).getEntity();
        Assert.assertEquals(staffView.registered(), 2);
        Assert.assertEquals(staffView.confirmed(), 1);
        Assert.assertEquals(staffView.checkedIn(), 1);
        Assert.assertEquals(staffView.byType(), List.of(new CheckinCountsDto.Bucket("Full weekend", 1, 1)));
        Assert.assertEquals(staffView.byDay().size(), 3);
        Assert.assertNull(staffView.money());
        final CheckinCountsDto financeView = (CheckinCountsDto) as(financeId).counts(trip.getId()).getEntity();
        Assert.assertEquals(financeView.money(), new CheckinCountsDto.Money(0, 0, 1, 0));
        assertError(as(stranger.getId()).counts(trip.getId()), 403, ApiErrors.FORBIDDEN);
    }

    @Test
    public void theRosterAndTheOwnersReadTreatTheTicketDifferently() {
        final RegistrationCommands registrations = bind(RegistrationCommands.class, new RegistrationCommands());
        signedInAsSiteAdmin(managerId);
        final List<?> roster = (List<?>) resource(new RegistrationsResource()).list(trip.getId()).getEntity();
        Assert.assertFalse(roster.isEmpty());
        roster.forEach(row -> Assert.assertNull(((RegistrationDto) row).ticketCode(), "the roster strips it"));

        Assert.assertNotNull(registrations);
        final RegistrationDto own = (RegistrationDto) as(ann.getId()).get(trip.getId(), ann.getId().getValue())
                .getEntity();
        Assert.assertEquals(own.ticketCode(), annCode, "the owner's app shows their own QR");
        Assert.assertTrue(own.ticketUrl().endsWith("&t=" + annCode), own.ticketUrl());
        Assert.assertEquals(own.registrationType(), "Full weekend");
    }
}
