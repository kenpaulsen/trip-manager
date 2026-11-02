package org.paulsens.trip.action;

import java.time.LocalDateTime;
import java.util.List;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** The website's door onto shared itineraries: which page a link opens, and rows that carry no one's own data. */
public class SharedItineraryCommandsTest {

    private Person viewer;
    private Person subject;
    private Trip trip;
    private PersonCommands people;
    private OrgCommands orgs;
    private SharedItineraryCommands commands;

    @BeforeMethod
    public void setUp() {
        viewer = new Person();
        subject = new Person();
        trip = Trip.builder().id("11111111-2222-3333-4444-555555555555")
                .people(List.of(viewer.getId(), subject.getId())).build();
        trip.setItinerarySharing(true);
        final TripEvent late = new TripEvent("evt-late", TripEvent.Type.EVENT, "Mass", "St. James",
                LocalDateTime.of(2027, 5, 4, 10, 0), null, null, null);
        final TripEvent early = new TripEvent("evt-early", null, "Flight", "notes",
                LocalDateTime.of(2027, 5, 3, 18, 0), null, null, null);
        late.getParticipants().add(subject.getId());
        late.getPrivNotes().put(subject.getId(), "aisle seat");
        early.getParticipants().add(subject.getId());
        trip.getTripEvents().add(late);
        trip.getTripEvents().add(early);
        trip.getTripEvents().add(new TripEvent("evt-not-his", TripEvent.Type.EVENT, "Other", null,
                LocalDateTime.of(2027, 5, 2, 9, 0), null, null, null));
        people = Mockito.mock(PersonCommands.class);
        Mockito.when(people.getPerson(viewer.getId())).thenReturn(viewer);
        Mockito.when(people.getPerson(subject.getId())).thenReturn(subject);
        orgs = Mockito.mock(OrgCommands.class);
        final Caller caller = new Caller(viewer.getId(), false, AuditActor.system(), new PrivilegeCommands());
        commands = new SharedItineraryCommands(() -> caller, () -> people, () -> orgs);
    }

    @Test
    public void aFellowTravelerIsLinkedToTheSharedPageAndGetsTheTripsRowsInDateOrder() {
        Assert.assertEquals(commands.access(trip, subject.getId()), "SHARED");
        Assert.assertEquals(commands.itineraryUrl(trip, subject.getId()),
                "/trip/sharedItinerary.jsf?trip=" + trip.getId() + "&id=" + subject.getId().getValue());
        final List<SharedItineraryCommands.SharedItineraryRow> rows = commands.rows(trip, subject.getId());
        Assert.assertEquals(rows.stream().map(SharedItineraryCommands.SharedItineraryRow::getId).toList(),
                List.of("evt-early", "evt-late"), "only the person's events, earliest first");
        Assert.assertEquals(rows.get(0).getType(), "EVENT", "an untyped event reads as an EVENT");
        Assert.assertEquals(rows.get(1).getNotes(), "St. James", "the EVENT's notes, never the private one");
    }

    @Test
    public void yourOwnItineraryLinksToTheFullPage() {
        Assert.assertEquals(commands.itineraryUrl(trip, viewer.getId()),
                "/trip/itinerary.jsf?trip=" + trip.getId() + "&id=" + viewer.getId().getValue());
    }

    @Test
    public void anOptedOutPersonHasNoLinkAndNoRows() {
        subject.getPrivacy().setItineraryShared(false);
        Assert.assertNull(commands.itineraryUrl(trip, subject.getId()));
        Assert.assertTrue(commands.rows(trip, subject.getId()).isEmpty());
    }

    @Test
    public void anOrgAdminOffTheRosterIsAFellowTraveler() {
        final Trip notMine = Trip.builder().id(trip.getId()).orgId("org-1").people(List.of(subject.getId()))
                .build();
        notMine.setItinerarySharing(true);
        Mockito.when(orgs.canManageOrg("org-1")).thenReturn(true);
        Assert.assertEquals(commands.access(notMine, subject.getId()), "SHARED");
    }

    @Test
    public void anUnknownPersonOrTripIsNone() {
        final Person.Id ghost = Person.Id.from("ghost");
        Mockito.when(people.getPerson(ghost)).thenReturn(new Person());
        Assert.assertEquals(commands.access(trip, ghost), "NONE");
        Assert.assertEquals(commands.access(null, subject.getId()), "NONE");
        Assert.assertEquals(commands.access(trip, null), "NONE");
    }

    @Test
    public void anAnonymousCallerIsNone() {
        final Caller anonymous = new Caller(null, false, AuditActor.system(), new PrivilegeCommands());
        final SharedItineraryCommands asAnonymous = new SharedItineraryCommands(() -> anonymous, () -> people,
                () -> orgs);
        Assert.assertEquals(asAnonymous.access(trip, subject.getId()), "NONE");
    }
}
