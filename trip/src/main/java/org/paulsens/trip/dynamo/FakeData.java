package org.paulsens.trip.dynamo;

import jakarta.faces.context.FacesContext;
import jakarta.servlet.ServletContext;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.Getter;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Person.Sex;
import org.paulsens.trip.model.RegistrationOption;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

public class FakeData {
    private static final String LOCAL = "local";
    private static final String FACES_SERVLET = "Faces Servlet";

    @Getter
    private static List<Person> fakePeople;
    @Getter
    private static List<Trip> fakeTrips;

    public static boolean isLocal() {
        // fc will be null in a test environment that doesn't full start the server w/ JSF installed.
        final FacesContext fc = FacesContext.getCurrentInstance();
        return (fc == null) || "true".equals(((ServletContext) fc.getExternalContext().getContext())
                .getServletRegistration(FACES_SERVLET).getInitParameter(LOCAL));
    }

    public static void initFakeData() {
        fakePeople = initFakePeople();
        fakeTrips = initFakeTrips();
    }

    public static Persistence createFakePersistence() {
        return new Persistence() { };
    }

    public static Persistence createFakePersistenceWithQueryMonitor(
            final Consumer<Consumer<QueryRequest.Builder>> whenQueryCalled) {
        return new Persistence() {
            @Override
            public CompletableFuture<QueryResponse> query(Consumer<QueryRequest.Builder> queryRequest) {
                whenQueryCalled.accept(queryRequest);
                return CompletableFuture.completedFuture(QueryResponse.builder().items(new ArrayList<>()).build());
            }
        };
    }

    public static void addFakeData() {
        if (isLocal()) {
            // Setup some sample data
            FakeData.getFakePeople().forEach(p -> {
                try {
                    DAO.getInstance().savePerson(p);
                } catch (IOException ex) {
                    throw new IllegalStateException("Should have worked...");
                }
            });
            FakeData.getFakeTrips().forEach(t -> {
                try {
                    DAO.getInstance().saveTrip(t);
                } catch (IOException ex) {
                    throw new IllegalStateException("Should have worked...");
                }
            });
        }
    }

    static List<Person> initFakePeople() {
        final List<Person> people = new ArrayList<>();
        people.add(new Person(null, "Joe", "Joseph", "Bob", "Smith", Sex.Male,
                LocalDate.of(1947, 2, 11), null, "u1", null, null, null, null, null, null, null, null));
        people.add(Person.builder()
                .id(Person.Id.from("admin"))
                .first("admin")
                .last("user")
                .email("admin")
                .build());
        people.add(new Person(null, "Ken", "Kenneth", "", "Paulsen", Sex.Male,
                LocalDate.of(1977, 12, 11), null, "user2", null, null, null, null, null, null, null, null));
        people.add(new Person(null, null, "Kevin", "David", "Paulsen", Sex.Male,
                LocalDate.of(1987, 9, 27), null,"user3", null, null, null, null, null, null, null, null));
        people.add(new Person(null, "Trinity", "Trinity", "Anne", "Paulsen", Sex.Female,
                LocalDate.of(1979, 12, 11), null, "user4", null, null, null, null, null, null, null, null));
        people.add(new Person(null, "Dave", "David", "A", "Robinson", Sex.Male,
                LocalDate.of(1999, 1, 30), null, "user5", null, null, null, null, null, null, null, null));
        people.add(new Person(null, "Matt", "Matthew", null, "Smith", Sex.Male,
                LocalDate.of(2010, 6, 1), null, "user6", null, null, null, null, null, null, null, null));
        return people;
    }

    static List<Trip> initFakeTrips() {
        // Trip 1
        final List<Trip> trips = new ArrayList<>();
        final List<Person.Id> allPeople = getFakePeople().stream().map(Person::getId).collect(Collectors.toList());
        final List<TripEvent> events = new ArrayList<>();
        events.add(newTripEvent("t1e2", TripEvent.Type.LODGING, "Hotel", "Super Duper Palace",
                LocalDateTime.now().plusDays(48), LocalDateTime.now().plusDays(60), List.of(allPeople.get(2)), null));
        events.add(newTripEvent("t1e4", TripEvent.Type.FLIGHT, "SEA -> EWR", "Alaska flight 94",
                LocalDateTime.now().plusDays(50), null, List.of(allPeople.get(2)), null));
        events.add(newTripEvent("t1e1", TripEvent.Type.FLIGHT, "PDX -> EWR", "Alaska flight 54",
                LocalDateTime.now().plusDays(48), null, List.of(allPeople.get(3)), null));
        events.add(newTripEvent("t1e5", TripEvent.Type.FLIGHT, "SPU -> SEA", "Direct charter flight",
                LocalDateTime.now().plusDays(55), null, List.of(allPeople.get(2)), null));
        final TripEvent charter = newTripEvent("t1e3", TripEvent.Type.FLIGHT, "SPU -> SEA", "Direct charter flight",
                LocalDateTime.now().plusDays(60), null, null, null);
        charter.getParticipants().add(allPeople.get(2));
        charter.getParticipants().add(allPeople.get(5));
        charter.getParticipants().add(allPeople.get(3));
        charter.getParticipants().add(allPeople.get(0));
        events.add(charter);
        trips.add(Trip.builder()
                .id("faketrip")
                .title("Spring Demo Trip")
                .openToPublic(false)
                .description("desc")
                .startDate(LocalDateTime.now().plusDays(48))
                .endDate(LocalDateTime.now().plusDays(60))
                .people(allPeople)
                .tripEvents(events)
                .regOptions(getDefaultOptions())
                .build());

        // Trip 2
        final List<Person.Id> somePeople = getFakePeople().stream()
                .filter(p -> !p.getLast().equals("Paulsen"))
                .map(Person::getId)
                .collect(Collectors.toList());
        final List<TripEvent> events2 = new ArrayList<>();
        events2.add(newTripEvent("t2e2", TripEvent.Type.LODGING, "Hotel", "Hilton",
                LocalDateTime.now().minusDays(4), LocalDateTime.now(), null, null));
        events2.add(newTripEvent("t2e1", TripEvent.Type.FLIGHT, "SEA -> LGW", "Alaska flight 255",
                LocalDateTime.now().minusDays(5), null, null, null));
        events2.add(newTripEvent("t2e3", TripEvent.Type.FLIGHT, "DBV -> KEF", "Trip for 1 to Iceland",
                LocalDateTime.now(), null, null, null));
        trips.add(Trip.builder()
                .id("Fake2")
                .title("Summer Demo Trip")
                .openToPublic(true)
                .description("Trip Description")
                .startDate(LocalDateTime.now().minusDays(4))
                .endDate(LocalDateTime.now().plusDays(7))
                .people(somePeople)
                .tripEvents(events2)
                .regOptions(getDefaultOptions())
                .build());
        return trips;
    }

    private static TripEvent newTripEvent(
            final String id,
            final TripEvent.Type type,
            final String title,
            final String notes,
            final LocalDateTime start,
            final LocalDateTime end,
            final List<Person.Id> participants,
            final Map<Person.Id, String> privNotes) {
        return new TripEvent(id, type, title, notes, start, end, participants, privNotes);
    }

    public static List<RegistrationOption> getDefaultOptions() {
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

    static Map<String, AttributeValue> getTestUserCreds(final GetItemRequest giReq) {
        final AttributeValue email = giReq.key().get(CredentialsDAO.EMAIL);
        final AttributeValue lowEmail = email.toBuilder().s(email.s().toLowerCase(Locale.getDefault())).build();
        final AttributeValue priv;
        if (lowEmail.s().startsWith("admin")) {
            priv = AttributeValue.builder().s("admin").build();
        } else if (lowEmail.s().startsWith("user")) {
            priv = AttributeValue.builder().s("user").build();
        } else {
            // Not authorized
            return null;
        }
        final Map<String, AttributeValue> attrs = new HashMap<>();
        attrs.put(CredentialsDAO.EMAIL, lowEmail);
        final AttributeValue userId = DAO.getInstance().getPeople().join().stream()
                .filter(person -> lowEmail.s().equalsIgnoreCase(person.getEmail())).findAny()
                .map(Person::getId).map(id -> AttributeValue.builder().s(id.getValue()).build())
                .orElse(lowEmail);
        attrs.put(CredentialsDAO.USER_ID, userId);
        attrs.put(CredentialsDAO.PRIV, priv);
        attrs.put(CredentialsDAO.PW, priv);
        attrs.put(CredentialsDAO.LAST_LOGIN,
                AttributeValue.builder().n("" + (System.currentTimeMillis() - 86_400_000L)).build());
        return attrs;
    }
}