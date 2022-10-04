package org.paulsens.trip.dynamo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Getter;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.RegistrationOption;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;

public class FakeData {
    @Getter
    private static final List<Person> fakePeople = initFakePeople();
    @Getter
    private static final List<Trip> fakeTrips = initFakeTrips();

    static List<Person> initFakePeople() {
        final List<Person> people = new ArrayList<>();
        people.add(new Person(null, "Joe", "Joseph", "Bob", "Smith",
                LocalDate.of(1947, 2, 11), null, "user1", null, null, null, null, null, null, null));
        people.add(new Person(null, "Ken", "Kenneth", "", "Paulsen",
                LocalDate.of(1977, 12, 11), null, "user2", null, null, null, null, null, null, null));
        people.add(new Person(null, null, "Kevin", "David", "Paulsen",
                LocalDate.of(1987, 9, 27), null,"user3", null, null, null, null, null, null, null));
        people.add(new Person(null, "Trinity", "Trinity", "Anne", "Paulsen",
                LocalDate.of(1979, 12, 11), null, "user4", null, null, null, null, null, null, null));
        people.add(new Person(null, "Dave", "David", "A", "Robinson",
                LocalDate.of(1999, 1, 30), null, "user5", null, null, null, null, null, null, null));
        people.add(new Person(null, "Matt", "Matthew", null, "Smith",
                LocalDate.of(2010, 6, 1), null, "user6", null, null, null, null, null, null, null));
        return people;
    }

    static List<Trip> initFakeTrips() {
        // Trip 1
        final List<Trip> trips = new ArrayList<>();
        final List<Person.Id> allPeople = getFakePeople().stream().map(Person::getId).collect(Collectors.toList());
        final List<TripEvent> events = new ArrayList<>();
        events.add(new TripEvent("te2", "Hotel", "Super Duper Palace", LocalDateTime.of(2020, 3, 27, 14, 0), null, null));
        events.add(new TripEvent("te1", "PDX -> EWR", "Alaska flight 54", LocalDateTime.of(2020, 3, 26, 6, 10), null, null));
        final TripEvent charter = new TripEvent("te3", "SPU -> SEA", "Direct charter flight",
                LocalDateTime.of(2020, 4, 6, 8, 33), null, null);
        charter.getParticipants().add(allPeople.get(2));
        charter.getParticipants().add(allPeople.get(5));
        charter.getParticipants().add(allPeople.get(3));
        charter.getParticipants().add(allPeople.get(0));
        events.add(charter);
        trips.add(new Trip("faketrip", "Spring Demo Trip", false, "desc", LocalDateTime.of(2021, 3, 26, 6, 10),
                LocalDateTime.of(2021, 4, 6, 16, 40), allPeople, events, getDefaultOptions()));

        // Trip 2
        final List<Person.Id> somePeople = getFakePeople().stream()
                .filter(p -> !p.getLast().equals("Paulsen"))
                .map(Person::getId)
                .collect(Collectors.toList());
        final List<TripEvent> events2 = new ArrayList<>();
        events2.add(new TripEvent("te2", "Hotel", "Hilton", LocalDateTime.of(2020, 7, 30, 14, 0), null, null));
        events2.add(new TripEvent("te1", "SEA -> LGW", "Alaska flight 255",
                LocalDateTime.of(2020, 7, 29, 6, 10), null, null));
        events2.add(new TripEvent("te3", "DBV -> KEF", "Trip for 1 to Iceland",
                LocalDateTime.of(2020, 8, 6, 8, 33), null, null));
        trips.add(new Trip("Fake2", "Summer Demo Trip", true, "Trip Description", LocalDateTime.of(2021, 7, 29, 6, 10),
                LocalDateTime.of(2021, 8, 6, 16, 40), somePeople, events2, getDefaultOptions()));
        return trips;
    }

    static List<RegistrationOption> getDefaultOptions() {
        final List<RegistrationOption> result = new ArrayList<>();
        result.add(new RegistrationOption(1, "Room Preference:",
                "Private room ($15 more per night) or shared?", true));
        result.add(new RegistrationOption(2, "Roommate request?",
                "If you are sharing a room, do you have someone in mind?", true));
        result.add(new RegistrationOption(3, "Preferred Departure Airport?",
                "What airport would you like to leave from?", true));
        result.add(new RegistrationOption(4, "Trip Insurance?",
                "Price is will be paid directly to insurance company, typically $100+.", true));
        result.add(new RegistrationOption(6, "Portugal excursion?",
                "Those interested will visit Fatima before the main trip.", true));
        result.add(new RegistrationOption(5, "Check luggage?",
                "Will you need to check luggage?", true));
        result.add(new RegistrationOption(7, "Special Requests?",
                "Do you have any special requests for this trip?", true));
        result.add(new RegistrationOption(8, "Agree to Terms?",
                "Type your full name to agree.", true));
        return result;
    }
}