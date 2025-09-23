package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jsft.util.Util;
import java.time.LocalDateTime;
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
        assertTrue(newBlankTrip.getOpenToPublic());
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