package org.paulsens.trip.dynamo;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.Getter;
import org.paulsens.trip.action.ChatPhotos;
import org.paulsens.trip.content.StarterTemplates;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.MediaItem;
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

    @Getter
    private static List<Person> fakePeople;
    @Getter
    private static List<Trip> fakeTrips;

    /**
     * Whether this JVM is in local mode.
     *
     * <p>Delegates to {@link LocalMode}, which resolves the answer once at context startup. This used to read
     * {@code FacesContext} directly and report local whenever there was none -- so every non-request thread in a
     * real deployment (a startup listener, a JAX-RS resource, a scheduled task) said "local", and whichever of
     * them reached the {@code DAO} singleton first made the whole deployment serve fake data. See
     * {@link LocalMode} for the incident and why the default is now production.
     */
    public static boolean isLocal() {
        return LocalMode.isLocal();
    }

    public static void initFakeData() {
        fakePeople = initFakePeople();
        fakeTrips = initFakeTrips();
    }

    /**
     * The fake datastore for local mode and tests.
     *
     * <p>Every table answers the way {@link Persistence}'s defaults do -- empty reads, successful writes --
     * EXCEPT {@code audit}, which is backed by a real in-memory table.
     *
     * <p>The exception is needed because {@link AuditDAO} is deliberately uncached, so it is the one DAO whose
     * reads go all the way to the datastore. With the plain fake, an audit record written locally could never
     * be read back: the admin page would be permanently empty on a laptop, and any test of it could only ever
     * assert that the page renders -- not that the feature works. Which is exactly the gap that let a
     * reserved-word bug reach production.
     */
    public static Persistence createFakePersistence() {
        return new InMemoryPersistence();
    }

    public static Persistence createFakePersistenceWithQueryMonitor(
            final Consumer<Consumer<QueryRequest.Builder>> whenQueryCalled) {
        return new Persistence() {
            @Override
            public QueryResponse query(Consumer<QueryRequest.Builder> queryRequest) {
                whenQueryCalled.accept(queryRequest);
                return QueryResponse.builder().items(new ArrayList<>()).build();
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
            addFakeMedia();
            addFakeContent();
        }
    }

    /**
     * Media rows exercising every landing-page rule: the Documents section's age window and hidden flag, and
     * the picture-album minimum count and per-photo visibility. Every photo row gets real (tiny) bytes
     * seeded into the local photo store -- see {@link #TINY_JPEG} for why broken images are not benign.
     */
    private static void addFakeMedia() {
        final LocalDateTime now = LocalDateTime.now();
        // Documents slot: one current and visible, one hidden by flag, one aged out of the window.
        saveMedia(new MediaItem("fake-doc-1", "downloads/FakeTravelGuide.pdf", "Fake Travel Guide",
                "Current, visible", "application/pdf", 2_400_000L, "home-docs", 0,
                now.minusDays(10), "fake-seed", null, null));
        saveMedia(new MediaItem("fake-doc-2", "downloads/FakeHiddenDoc.pdf", "Hidden Doc",
                "Hidden by flag", "application/pdf", 1_000_000L, "home-docs", 1,
                now.minusDays(5), "fake-seed", null, Boolean.TRUE));
        saveMedia(new MediaItem("fake-doc-3", "downloads/FakeOldSlides.pdf", "Old Meeting Slides",
                "Aged out", "application/pdf", 9_000_000L, "home-docs", 2,
                now.minusDays(130), "fake-seed", null, null));
        // The galleria trip's album: 12 photos, one hidden -> 11 visible, above the min-count of 10.
        for (int i = 1; i <= 12; i++) {
            saveMedia(new MediaItem("fake-photo-past-" + i, "chat/pub-past-3d/fake-" + i + ".jpg",
                    "Fake photo " + i, "Seeded album photo", "image/jpeg", 500_000L,
                    "tripChat-pub-past-3d", 0, now.minusDays(13).plusHours(i), "fake-seed", null,
                    i == 12 ? Boolean.TRUE : null));
            seedPhotoBytes("chat/pub-past-3d/fake-" + i + ".jpg");
        }
        // Below the minimum: no album on the landing page.
        for (int i = 1; i <= 4; i++) {
            saveMedia(new MediaItem("fake-photo-f2-" + i, "chat/Fake2/fake-" + i + ".jpg",
                    "Fake2 photo " + i, "Seeded album photo", "image/jpeg", 500_000L,
                    "tripChat-Fake2", 0, now.minusDays(2).plusHours(i), "fake-seed", null, null));
            seedPhotoBytes("chat/Fake2/fake-" + i + ".jpg");
        }
    }

    /**
     * An 8x8 JPEG, so every seeded album row has real bytes behind its key. Rows without servable bytes are
     * not merely ugly locally: each broken img 404s into the app's error page, and error-page renders are
     * JSF views -- a dozen of them evict the landing page's own view from Mojarra's logical-view LRU, and
     * its first postback dies ViewExpiredException. See ChatPhotos.seedLocalObject.
     */
    private static final byte[] TINY_JPEG = Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAoHBwgHBgoICAgLCgoLDhgQDg0NDh0VFhEYIx8lJCIfIiEmKzcvJik0KSEiMEExNDk7"
            + "Pj4+JS5ESUM8SDc9Pjv/2wBDAQoLCw4NDhwQEBw7KCIoOzs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7"
            + "Ozs7Ozs7Ozv/wAARCAAIAAgDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUF"
            + "BAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVW"
            + "V1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi"
            + "4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAEC"
            + "AxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVm"
            + "Z2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq"
            + "8vP09fb3+Pn6/9oADAMBAAIRAxEAPwC1RRRXtHGf/9k=");

    private static void seedPhotoBytes(final String key) {
        ChatPhotos.getChatPhotos().seedLocalObject(key, TINY_JPEG, "image/jpeg");
    }

    /** The starter templates plus one visible event, one expired event (proves auto-hide), and an intro. */
    private static void addFakeContent() {
        // Idempotent: addFakeData runs once per DAO instance, but tests re-run it against a shared local
        // store, and a re-save of an existing versioned row trips the lost-update guard.
        StarterTemplates.all().stream()
                .filter(t -> DAO.getInstance().getTemplate(t.getId()).isEmpty())
                .forEach(t -> DAO.getInstance().saveTemplate(t, 5));
        final LocalDateTime now = LocalDateTime.now();
        saveContent(new ContentInstance("fake-event-future", "home.events", "Monthly Prayer Group Meeting",
                StarterTemplates.TEXT_ONLY_ID, 1,
                new HashMap<>(Map.of("body", "<b>Monthly Prayer Group Meeting</b> — first Saturday, 10am.")),
                now.plusDays(30), 0, 0, null, "fake-seed"));
        saveContent(new ContentInstance("fake-event-past", "home.events", "Past Peace Mass",
                StarterTemplates.TEXT_ONLY_ID, 1,
                new HashMap<>(Map.of("body", "This event already happened and must not render.")),
                now.minusDays(3), 1, 0, null, "fake-seed"));
        saveContent(new ContentInstance("fake-intro", "home.intro", "Introduction",
                StarterTemplates.TEXT_ONLY_ID, 1,
                new HashMap<>(Map.of("body", "<p>Welcome to Visit Queen of Peace — fake local intro.</p>")),
                null, 0, 0, null, "fake-seed"));
    }

    private static void saveMedia(final MediaItem item) {
        if (!DAO.getInstance().saveMedia(item)) {
            throw new IllegalStateException("Failed to seed fake media: " + item.getId());
        }
    }

    private static void saveContent(final ContentInstance instance) {
        if (DAO.getInstance().getContent(instance.getId()).isPresent()) {
            return;     // already seeded (tests re-run addFakeData against a shared local store)
        }
        if (!DAO.getInstance().saveContent(instance, 5)) {
            throw new IllegalStateException("Failed to seed fake content: " + instance.getId());
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
        // Landing-page seeds: one trip per public-listing rule, so the data-driven home page renders every
        // branch locally (language sections, CFPW sidebar filter, countdown rules, 7-day removal, galleria).
        trips.add(Trip.builder()
                .id("pub-en-1")
                .title("2026 Sep: Holy Angels Demo")     // prefixed title exercises getShortTitle
                .openToPublic(true)
                .provider("CFPW")
                .language(Language.English)
                .estimatedPrice("$3,000")
                .director("Fr. Demo Director")
                .startDate(LocalDateTime.now().plusDays(30))
                .endDate(LocalDateTime.now().plusDays(40))
                .people(new ArrayList<>(List.of(allPeople.get(2))))
                .regLimit(40)
                .regOptions(getDefaultOptions())
                .tripEvents(seedEvents("pub-en-1", 30))
                .build());
        trips.add(Trip.builder()
                .id("pub-en-2")
                .title("2027 Jan: Winter Demo")
                .openToPublic(true)
                .provider("CFPW")
                .language(Language.English)
                .startDate(LocalDateTime.now().plusDays(120))     // beyond 60d and not next: no countdown
                .endDate(LocalDateTime.now().plusDays(130))
                .regLimit(40)
                .regOptions(getDefaultOptions())
                .tripEvents(seedEvents("pub-en-2", 120))
                .build());
        trips.add(Trip.builder()
                .id("pub-es-1")
                .title("2026 dic: Bajo el Manto Demo")
                .openToPublic(true)
                .provider("CFPW")
                .language(Language.Spanish)
                .startDate(LocalDateTime.now().plusDays(100))     // countdown ONLY as next-of-its-language
                .endDate(LocalDateTime.now().plusDays(110))
                .regLimit(40)
                .regOptions(getDefaultOptions())
                .tripEvents(seedEvents("pub-es-1", 100))
                .build());
        trips.add(Trip.builder()
                .id("pub-ext-1")
                .title("2026 Oct: External Demo")
                .openToPublic(true)
                .provider("Queen of Peace Medjugorje Centre, Toronto, Canada")
                .language(Language.English)
                .nonHostedTripUrl("https://example.com/external-pilgrimage")
                .nonHostedRegNumber(35)
                .startDate(LocalDateTime.now().plusDays(70))
                .endDate(LocalDateTime.now().plusDays(80))
                .regLimit(45)
                .tripEvents(seedEvents("pub-ext-1", 70))
                .build());
        trips.add(Trip.builder()
                .id("pub-past-3d")
                .title("2026 Aug: Just Ended Demo")     // within the 7-day window; the galleria album trip
                .openToPublic(true)
                .provider("CFPW")
                .language(Language.English)
                .startDate(LocalDateTime.now().minusDays(13))
                .endDate(LocalDateTime.now().minusDays(3))
                .people(allPeople)
                .regOptions(getDefaultOptions())
                .tripEvents(seedEvents("pub-past-3d", -13))
                .build());
        trips.add(Trip.builder()
                .id("pub-past-30d")
                .title("2026 Jul: Long Gone Demo")     // past the 7-day window: must NOT be listed
                .openToPublic(true)
                .provider("CFPW")
                .language(Language.English)
                .startDate(LocalDateTime.now().minusDays(40))
                .endDate(LocalDateTime.now().minusDays(30))
                .tripEvents(seedEvents("pub-past-30d", -40))
                .build());
        trips.add(Trip.builder()
                .id("pub-hidden")
                .title("2026 Nov: Unlisted Demo")     // openToPublic=false: must NOT be listed
                .openToPublic(false)
                .provider("CFPW")
                .language(Language.English)
                .startDate(LocalDateTime.now().plusDays(90))
                .endDate(LocalDateTime.now().plusDays(97))
                .tripEvents(seedEvents("pub-hidden", 90))
                .build());
        return trips;
    }

    /** One minimal event per seed trip (a FakeData invariant: every fake trip has at least one). */
    private static List<TripEvent> seedEvents(final String tripId, final int startOffsetDays) {
        return List.of(newTripEvent(tripId + "-e1", TripEvent.Type.LODGING, "Hotel", "Seeded lodging",
                LocalDateTime.now().plusDays(startOffsetDays), null, null, null));
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
        final AttributeValue lowEmail = email.toBuilder().s(email.s().toLowerCase(Locale.ROOT)).build();
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
        final AttributeValue userId = Optional.ofNullable(DAO.getInstance().getPersonByEmail(lowEmail.s()))
                .map(Person::getId).map(id -> AttributeValue.builder().s(id.getValue()).build())
                .orElse(lowEmail);
        attrs.put(CredentialsDAO.USER_ID, userId);
        attrs.put(CredentialsDAO.PRIV, priv);
        attrs.put(CredentialsDAO.PW, priv);
        // Epoch SECONDS, matching CredentialsDAO.updateLastLogin() and Audit.formatEpochSeconds(). Storing millis
        // here made the local audit log report the previous login as the year 58524.
        attrs.put(CredentialsDAO.LAST_LOGIN,
                AttributeValue.builder().n("" + ((System.currentTimeMillis() - 86_400_000L) / 1000L)).build());
        return attrs;
    }
}