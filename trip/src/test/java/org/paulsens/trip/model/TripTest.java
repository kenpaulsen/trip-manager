package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jsft.util.Util;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class TripTest {
    private LocalDateTime now;
    private String monthStr;

    @BeforeClass
    void initStuff() {
        DAO.getInstance().clearAllCaches();
        FakeData.initFakeData();
        FakeData.addFakeData();
        now = LocalDateTime.now();
        monthStr = getMonthString(now);
    }

    /**
     * Chat is opt-out, and this is the assertion that keeps it that way.
     *
     * <p>Every trip that existed before the setting has no {@code chatEnabled} in its stored JSON, and several of
     * them have live chats with real messages. If the accessor ever reads a missing value as false — which is what
     * a primitive field, or a Lombok-generated {@code Boolean} getter handed to EL, would do — the Chat tab
     * silently disappears from all of them on deploy and the only symptom is an absence.
     */
    @Test
    void chatIsOnUnlessSomeoneTurnedItOff() throws Exception {
        assertTrue(Trip.builder().build().getChatEnabled(), "A new trip has chat");

        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final Trip legacy = mapper.readValue("{\"id\":\"t1\",\"title\":\"Older trip\"}", Trip.class);
        assertTrue(legacy.getChatEnabled(), "A trip stored before the setting existed must keep its chat");

        final Trip off = mapper.readValue("{\"id\":\"t2\",\"chatEnabled\":false}", Trip.class);
        assertFalse(off.getChatEnabled(), "An explicit off is honoured");

        // And it survives a round trip, so saving a trip from the edit page does not quietly re-enable it.
        final Trip reread = mapper.readValue(mapper.writeValueAsString(off), Trip.class);
        assertFalse(reread.getChatEnabled(), "Off must survive being written and read back");
    }

    /** Badge images: lazy list, copy setter, key lookup, and survival of the JSON round trip. */
    @Test
    void badgeImagesBehaveLikeTheOtherTripLists() throws Exception {
        final Trip trip = Trip.builder().build();
        assertTrue(trip.getBadgeImages().isEmpty(), "A new trip has an empty (not null) list");
        trip.getBadgeImages().add(new BadgeImage("badgeImages/t/1.jpg", "One"));
        assertEquals(trip.getBadgeImage("badgeImages/t/1.jpg").getLabel(), "One");
        assertNull(trip.getBadgeImage("badgeImages/t/2.jpg"));
        assertNull(trip.getBadgeImage(null));

        final List<BadgeImage> mine = new ArrayList<>(List.of(new BadgeImage("k", "l")));
        trip.setBadgeImages(mine);
        mine.clear();
        assertEquals(trip.getBadgeImages().size(), 1, "The setter copies, callers cannot mutate through");

        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final Trip reread = mapper.readValue(mapper.writeValueAsString(trip), Trip.class);
        assertEquals(reread.getBadgeImage("k").getLabel(), "l", "Badge images survive the stored JSON");

        final Trip legacy = mapper.readValue("{\"id\":\"t1\",\"title\":\"Older trip\"}", Trip.class);
        assertTrue(legacy.getBadgeImages().isEmpty(), "A pre-feature trip reads back with an empty list");

        assertEquals(Trip.builder().badgeImages(null).build().getBadgeImages().size(), 0,
                "The builder treats null as empty, like every other list here");
    }

    @Test
    void newTripHasId() {
        final Trip trip = Trip.builder().build();
        assertNotNull(trip.getId());
        assertTrue(trip.getId().length() > 10);
    }

    @Test
    void newIsNotOpenToPublic() {
        // FALSE since 2026-08-24: "Show on homepage?" is opt-in; the old TRUE default put half-written
        // trips on the public landing page.
        final Trip trip = Trip.builder().build();
        assertNotNull(trip.getOpenToPublic());
        assertFalse(trip.getOpenToPublic());
    }

    @Test
    void newTripHasStartAndEnd() {
        final Trip trip = Trip.builder().build();
        assertNotNull(trip.getStartDate());
        assertNotNull(trip.getEndDate());
        assertTrue(trip.getEndDate().toInstant(ZoneOffset.UTC).toEpochMilli() >
                trip.getStartDate().toInstant(ZoneOffset.UTC).toEpochMilli());
    }

    @Test
    void getTripDateRangeStartEndDatesInSameMonthShowMonthOnce() {
        final Trip trip = Trip.builder()
                .startDate(now.withDayOfMonth(2))
                .endDate(now.withDayOfMonth(13))
                .build();
        assertEquals(trip.getTripDateRange(), monthStr + " 2 - 13, " + trip.getEndDate().getYear());
    }

    @Test
    void getTripDateRangeStartEndDatesInDifferentMonthsShowBothMonths() {
        final Trip trip = Trip.builder()
                .startDate(now.withDayOfMonth(28))
                .endDate(now.plusMonths(1).withDayOfMonth(10))
                .build();
        assertEquals(trip.getTripDateRange(),
                monthStr + " 28 - " + getMonthString(now.plusMonths(1)) + " 10, " + trip.getEndDate().getYear());
    }

    @Test
    void defaultValuesWork() {
        final Trip newBlankTrip = Trip.builder().build();
        assertNotNull(newBlankTrip.getId());
        assertFalse(newBlankTrip.getId().isBlank());
        assertFalse(newBlankTrip.getOpenToPublic(), "homepage visibility is opt-in for new trips");
        assertEquals(newBlankTrip.getStartDate().minusDays(90).getDayOfMonth(), now.getDayOfMonth());
        assertEquals(newBlankTrip.getEndDate().minusDays(100).getDayOfMonth(), now.getDayOfMonth());
        assertEquals(newBlankTrip.getPeople(), List.of());
        assertEquals(newBlankTrip.getTripEvents(), List.of());
        assertEquals(newBlankTrip.getRegOptions(), List.of());
        assertNull(newBlankTrip.getRegLimit());
        assertNull(newBlankTrip.getProvider());
        assertNull(newBlankTrip.getLanguage());
        assertNull(newBlankTrip.getEstimatedPrice());
        assertNull(newBlankTrip.getDirector());
        assertNull(newBlankTrip.getLocalGuide());
        assertNull(newBlankTrip.getFacilitators());
        assertNull(newBlankTrip.getNonHostedTripUrl());
        assertNull(newBlankTrip.getNonHostedRegNumber());
    }

    @Test
    void peopleListValuesAreNotShared() {
        final List<Person.Id> people = new ArrayList<>(FakeData.getFakePeople().stream().map(Person::getId).toList());
        final Trip trip1 = Trip.builder()
                .people(people)
                .build();
        // Mutate List
        people.add(Person.Id.newInstance());
        final Trip trip2 = Trip.builder()
                .people(people)
                .build();
        assertNotEquals(trip1.getPeople(), trip2.getPeople(), "Sharing people lists!! Bad!");
    }

    @Test
    void peopleListValuesAreNotSharedSetLater() {
        final List<Person.Id> people = new ArrayList<>(FakeData.getFakePeople().stream().map(Person::getId).toList());
        final Trip trip1 = Trip.builder()
                .build();
        trip1.setPeople(people);
        // Mutate List
        final Trip trip2 = Trip.builder()
                .build();
        trip1.setPeople(people);

        trip2.getPeople().add(Person.Id.newInstance());

        assertNotEquals(trip1.getPeople(), trip2.getPeople(), "Sharing people lists!! Bad!");
    }

    @Test
    void regOptionsAreNotShared() {
        final List<RegistrationOption> regOptions = new ArrayList<>(FakeData.getDefaultOptions());
        final Trip trip1 = Trip.builder()
                .regOptions(regOptions)
                .build();
        regOptions.remove(0);
        final Trip trip2 = Trip.builder()
                .regOptions(regOptions)
                .build();
        assertNotEquals(trip1.getRegOptions(), trip2.getRegOptions(), "Sharing regOptions lists!! Bad!");
    }

    @Test
    void regOptionsAreNotSharedDelLater() {
        final List<RegistrationOption> regOptions = new ArrayList<>(FakeData.getDefaultOptions());
        final Trip trip1 = Trip.builder()
                .build();
        final Trip trip2 = Trip.builder()
                .build();
        trip1.setRegOptions(regOptions);
        trip2.setRegOptions(regOptions);
        trip1.getRegOptions().remove(0);
        assertNotEquals(trip1.getRegOptions(), trip2.getRegOptions(), "Sharing regOptions lists!! Bad!");
    }

    @Test
    void eventsAreNotShared() {
        final List<TripEvent> tripEvents = new ArrayList<>(FakeData.getFakeTrips().get(1).getTripEvents());
        final Trip trip1 = Trip.builder()
                .tripEvents(tripEvents)
                .build();
        tripEvents.remove(0);
        final Trip trip2 = Trip.builder()
                .tripEvents(tripEvents)
                .build();
        assertNotEquals(trip1.getTripEvents(), trip2.getTripEvents(), "Sharing trip option lists!! Bad!");
    }

    @Test
    void eventsAreNotSharedDelLater() {
        final List<TripEvent> tripEvents = new ArrayList<>(FakeData.getFakeTrips().get(1).getTripEvents());
        final Trip trip1 = Trip.builder()
                .build();
        final Trip trip2 = Trip.builder()
                .build();
        trip1.setTripEvents(tripEvents);
        trip2.setTripEvents(tripEvents);
        trip1.getTripEvents().remove(0);
        assertNotEquals(trip1.getTripEvents(), trip2.getTripEvents(), "Sharing trip option lists!! Bad!");
    }

    @Test
    void canSerializeAndDeserializeFullTrip() throws Exception {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final Trip before = Trip.builder()
                .id("myId")
                .title("myTitle")
                .openToPublic(false)
                .description("myDesc")
                .startDate(LocalDateTime.now().plusDays(90))
                .endDate(LocalDateTime.now().plusDays(100))
                .people(FakeData.getFakePeople().stream().map(Person::getId).toList())
                .regLimit(35)
                .provider("Somebody Else")
                .language(Language.English)
                .estimatedPrice("53.23 CAD")
                .director("Fr John")
                .localGuide("Marija")
                .facilitators("Ken + Audie")
                .flyerUrl("https://somewhere/flyer.pdf")
                .nonHostedTripUrl("https://somewhere")
                .nonHostedRegNumber(12)
                .tripEvents(FakeData.getFakeTrips().get(0).getTripEvents())
                .regOptions(FakeData.getDefaultOptions())
                .build();
        final String serialized = mapper.writeValueAsString(before);
        final Trip after = mapper.readValue(serialized, Trip.class);
        assertEquals(after, before);
    }

    private String getMonthString(final LocalDateTime date) {
        return date.getMonth().getDisplayName(TextStyle.SHORT, Util.getLocale(null));
    }
}