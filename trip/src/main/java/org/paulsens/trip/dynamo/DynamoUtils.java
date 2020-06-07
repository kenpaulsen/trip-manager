package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.faces.context.FacesContext;
import javax.servlet.ServletContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent2;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

@Slf4j
public class DynamoUtils {
    @Getter
    private final ObjectMapper mapper;
    private final TripPersistence client;

    private final Map<String, Person> peopleCache = new ConcurrentHashMap<>();
    private final Map<String, Trip> tripCache = new ConcurrentHashMap<>();
    private final Map<String, TripEvent2> tripEventCache = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Transaction>> txCache = new ConcurrentHashMap<>();

    // This flag is set in the web.xml via the login page via the
    private static final String FACES_SERVLET = "Faces Servlet";
    private static final String LOCAL = "local";
    private static final String PASS_TABLE = "pass";
    private static final String PERSON_TABLE = "people";
    private static final String TRANSACTION_TABLE = "transactions";
    private static final String TRIP_TABLE = "trips";
    private static final String TRIP_EVENT_TABLE = "trip_events";
    private static final String ID = "id";
    private static final String USER_ID = "userId";
    private static final String CONTENT = "content";
    private static final String TX_ID = "txId";
    private static final String EMAIL = "email";
    private static final String PRIV = "priv";
    private static final String PW = "pass";
    private static final String LAST_LOGIN = "lastLogin";
    private static final String USER_PRIV = "user";

    private static final DynamoUtils INSTANCE = new DynamoUtils();

    private DynamoUtils() {
        final ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.mapper = mapper;
        if (isLocal()) {
            // Local development only -- don't talk to dynamo
            this.client = new DynamoUtils.TripPersistence() {};
            // Setup some sample data
            FakeData.getFakePeople().forEach(p -> {
                try {
                    savePerson(p);
                } catch (IOException ex) {
                    throw new IllegalStateException("Should have worked...");
                }
            });
            FakeData.getFakeTrips().forEach(t -> {
                try {
                    saveTrip(t);
                } catch (IOException ex) {
                    throw new IllegalStateException("Should have worked...");
                }
            });
        } else {
            // The real deal
            this.client = new DynamoUtils.DynamoTripPersistence();
        }
    }

    public static DynamoUtils getInstance() {
        return INSTANCE;
    }

    public static boolean isLocal() {
        // fc will be null in a test environment that doesn't full start the server w/ JSF installed.
        final FacesContext fc = FacesContext.getCurrentInstance();
        return (fc == null) || "true".equals(((ServletContext) fc.getExternalContext().getContext())
                .getServletRegistration(FACES_SERVLET).getInitParameter(LOCAL));
    }

    public CompletableFuture<Boolean> savePerson(final Person person) throws IOException {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(ID, AttributeValue.builder().s(person.getId()).build());
        map.put(CONTENT, AttributeValue.builder().s(mapper.writeValueAsString(person)).build());
        return client.putItem(b -> b.tableName(PERSON_TABLE).item(map))
            .thenApply(resp -> resp.sdkHttpResponse().isSuccessful())
            .thenApply(r -> r ? cacheOne(peopleCache, person, person.getId(), true) : clearCache(peopleCache, false));
    }

    public CompletableFuture<List<Person>> getPeople() {
        if (!peopleCache.isEmpty()) {
            return CompletableFuture.completedFuture(peopleCache.values().stream()
                    .sorted(Comparator.comparing(Person::getLast))
                    .collect(Collectors.toList()));
        }
        return client.scan(b -> b.consistentRead(false).limit(1000).tableName(PERSON_TABLE).build())
                .thenApply(resp -> resp.items().stream()
                        .map(it -> toPerson(it.get(CONTENT)))
                        .sorted(Comparator.comparing(Person::getLast))
                        .collect(Collectors.toList()))
                .thenApply(list -> cacheAll(peopleCache, list, Person::getId));
    }

    public CompletableFuture<Optional<Person>> getPerson(final String id) {
        final Person person = peopleCache.get(id);
        if (person != null) {
            return CompletableFuture.completedFuture(Optional.of(person));
        }
        return getPeople().thenApply(people -> Optional.ofNullable(peopleCache.get(id))); // Load all the people
    }

    public CompletableFuture<Boolean> saveTrip(final Trip trip) throws IOException {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(ID, toStrAttr(trip.getId()));
        map.put(CONTENT, toStrAttr(mapper.writeValueAsString(trip)));
        final CompletableFuture<Boolean> saveTripEvents = saveAllTripEvents(trip);
        final CompletableFuture<Boolean> saveTrip = client.putItem(b -> b.tableName(TRIP_TABLE).item(map))
                .thenApply(resp -> resp.sdkHttpResponse().isSuccessful())
                .thenApply(r -> r ? cacheOne(tripCache, trip, trip.getId(), true) : clearCache(tripCache, false));
        return CompletableFuture.allOf(saveTrip, saveTripEvents)
                .thenApply(v -> saveTrip.join() && saveTripEvents.join())
                .exceptionally(ex -> false);
    }

    private CompletableFuture<Boolean> saveAllTripEvents(final Trip trip) {
        final CompletableFuture<?>[] saves = trip.getTripEvents().stream()
                .map(this::saveTripEvent).toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(saves)
                // If any returned false, then fail
                .thenApply((v) -> Arrays.stream(saves).allMatch(fut -> (Boolean) fut.join()));
    }

    public CompletableFuture<Boolean> saveTripEvent(final TripEvent2 te) {
        // Fixme: should we check if we need to save?
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(ID, toStrAttr(te.getId()));
        try {
            map.put(CONTENT, toStrAttr(mapper.writeValueAsString(te)));
        } catch (final IOException ex) {
            throw new RuntimeException(ex);
        }
        return client.putItem(b -> b.tableName(TRIP_EVENT_TABLE).item(map))
                .thenApply(resp -> resp.sdkHttpResponse().isSuccessful())
                .thenApply(r -> r ? cacheOne(tripEventCache, te, te.getId(), true) : clearCache(tripEventCache, false));
    }

    public CompletableFuture<Optional<Trip>> getTrip(final String id) {
        final Trip trip = tripCache.get(id);
        if (trip != null) {
            return CompletableFuture.completedFuture(Optional.of(trip));
        }
        return getTrips().thenApply(trips -> Optional.ofNullable(tripCache.get(id))); // Load all the trips
    }

    public CompletableFuture<List<Trip>> getTrips() {
        if (!tripCache.isEmpty()) {
            return CompletableFuture.completedFuture(tripCache.values().stream()
                    .sorted(Comparator.comparing(Trip::getStartDate))
                    .collect(Collectors.toList()));
        }
        return client.scan(b -> b.consistentRead(false).limit(1000).tableName(TRIP_TABLE).build())
                .thenApply(resp -> resp.items().stream().map(it -> toTrip(it.get(CONTENT)))
                        .sorted(Comparator.comparing(Trip::getStartDate))
                        .collect(Collectors.toList())
                )
                .thenApply(list -> cacheAll(tripCache, list, Trip::getId));
    }

    public CompletableFuture<TripEvent2> getTripEvent(final String id) {
        final TripEvent2 te = tripEventCache.get(id);
        if (te != null) {
            return CompletableFuture.completedFuture(te);
        }
        final Map<String, AttributeValue> key = Collections.singletonMap(ID, AttributeValue.builder().s(id).build());
        return client.getItem(b -> b.key(key).tableName(TRIP_EVENT_TABLE).build())
                .thenApply(item -> toTripEvent(item, id));
    }

    public CompletableFuture<Creds> getCredsByEmailAndPass(final String email, final String pass) {
        if ((email == null) || email.isEmpty() || (pass == null) || pass.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        final Map<String, AttributeValue> key =
                Collections.singletonMap(EMAIL, AttributeValue.builder().s(email.toLowerCase()).build());
        return client.getItem(b -> b.key(key).tableName(PASS_TABLE).build())
                .thenApply(item -> toCreds(item, email, pass));
    }

    /**
     * This method sets the last login timestamp for the given user (via {@link Creds}). It returns the previous last
     * login, or {@code null} if the user hasn't logged in before.
     *
     * @param creds     The {@link Creds} to update.
     * @return The previous last login in Epoch seconds.
     */
    public Long updateLastLogin(final Creds creds) {
        if (creds == null) {
            return null;
        }
        final Long prevLast = creds.getLastLogin();
        creds.setLastLogin(Instant.now().getEpochSecond());
        // If the previous login was more than 2 seconds ago, save the new login time.
        if ((prevLast == null) || (prevLast < creds.getLastLogin() - 2)) {
            saveCreds(creds);
        }
        return prevLast;
    }

    public CompletableFuture<List<Transaction>> getTransactions(final String userId) {
        return getTxCacheForUser(userId).thenApply(map -> new ArrayList<>(map.values()));
    }

    public CompletableFuture<Optional<Transaction>> getTransaction(final String userId, final String txId) {
        return getTxCacheForUser(userId) // Ensure user transactions are already loaded into memory
                .thenApply(map -> map.get(txId))  // Read from cache
                .thenApply(Optional::ofNullable);
    }

    public CompletableFuture<Boolean> saveTransaction(final Transaction tx) throws IOException {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(USER_ID, AttributeValue.builder().s(tx.getUserId()).build());
        map.put(TX_ID, AttributeValue.builder().s(tx.getTxId()).build());
        map.put(CONTENT, AttributeValue.builder().s(mapper.writeValueAsString(tx)).build());
        return client.putItem(b -> b.tableName(TRANSACTION_TABLE).item(map))
                .thenApply(resp -> resp.sdkHttpResponse().isSuccessful())
                .thenCompose(r -> cacheOneTxAsync(r, tx));
    }

    /* Package-private for testing */
    void clearAllCaches() {
        peopleCache.clear();
        tripCache.clear();
        tripEventCache.clear();
        txCache.clear();
    }

    private CompletableFuture<Boolean> cacheOneTxAsync(final boolean success, final Transaction tx) {
        return getTxCacheForUser(tx.getUserId()).thenApply(userTxs -> cacheOneTx(userTxs, success, tx));
    }

    private boolean cacheOneTx(final Map<String, Transaction> userTxs, final boolean success, final Transaction tx) {
        if (success) {
            if (tx.getDeleted() == null) {
                cacheOne(userTxs, tx, tx.getTxId(), true); // Normal, just cache it
            } else {
                userTxs.remove(tx.getTxId()); // It is now deleted, remove it from cache
            }
        } else {
            clearCache(userTxs, false); // Error... clear all cache values
        }
        return success;
    }

    /* Package-private for testing */
    CompletableFuture<Map<String, Transaction>> getTxCacheForUser(final String userId) {
        final Map<String, Transaction> result = txCache.get(userId);
        return (result == null) ? cacheTxData(userId) : CompletableFuture.completedFuture(result);
    }

    private CompletableFuture<Map<String, Transaction>> cacheTxData(final String userId) {
        return loadUserTxData(userId)
                .thenApply(cache -> {
                    txCache.put(userId, cache);
                    return cache;
                });
    }

    private CompletableFuture<Map<String, Transaction>> loadUserTxData(final String userId) {
        System.out.println("Cache Miss for tx userId: " + userId);
        // Use a map that preserves order for sorting
        final Map<String, Transaction> result = new ConcurrentSkipListMap<>();
        return client.query(qb -> txByUserId(qb, userId))
                .thenApply(resp -> resp.items().stream()
                        .map(m -> toTransaction(m.get(CONTENT)))
                        .filter(tx -> (tx != null) && (tx.getDeleted() == null))
                        .sorted(Comparator.comparing(Transaction::getTxDate))
                        .collect(Collectors.toList()))
                .thenApply(list -> cacheAll(result, list, Transaction::getTxId))
                .thenApply(na -> result);
    }

    private Creds toCreds(final GetItemResponse resp, final String email, final String pass) {
        if (!resp.hasItem()) {
            log.warn("User with email (" + email + ") has not logged in before! Checking if user exists...");
            return createCreds(email).map(creds -> validateCreds(email, pass, creds)).orElse(null);
        }
        final Map<String, AttributeValue> at = resp.item();
        final AttributeValue last = at.get(LAST_LOGIN);
        final Creds creds = new Creds(at.get(EMAIL).s(), at.get(USER_ID).s(), at.get(PRIV).s(), at.get(PW).s(),
                last == null ? null : Long.parseLong(last.n()));
        return validateCreds(email, pass, creds);
    }

    private Creds validateCreds(final String email, final String pass, final Creds creds) {
        if (!pass.equals(creds.getPass())) {
            log.warn("Invalid password for user: {}", email);
            return null;
        }
        return creds;
    }

    private Optional<Creds> createCreds(final String email) {
        final Person user = getPersonByEmail(email).join();
        if (user == null) {
            throw new IllegalArgumentException("Invalid Email Address!");
        }
        final Creds creds = new Creds(
                email.toLowerCase(), user.getId(), USER_PRIV, user.getLast(), Instant.now().getEpochSecond());
        return Optional.ofNullable(saveCreds(creds).join() ? creds : null);
    }

    private CompletableFuture<Boolean> saveCreds(final Creds creds) {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(EMAIL, AttributeValue.builder().s(creds.getEmail()).build());
        map.put(USER_ID, AttributeValue.builder().s(creds.getUserId()).build());
        map.put(PW, AttributeValue.builder().s(creds.getPass()).build());
        map.put(PRIV, AttributeValue.builder().s(creds.getPriv()).build());
        map.put(LAST_LOGIN, AttributeValue.builder().n("" + creds.getLastLogin()).build());
        return client.putItem(b -> b.tableName(PASS_TABLE).item(map))
                .thenApply(resp -> resp.sdkHttpResponse().isSuccessful())
                .exceptionally(ex -> {
                    log.error("Failed to save creds!", ex);
                    return false;
                });
    }

    private CompletableFuture<Person> getPersonByEmail(final String email) {
        return getPeople().thenApply(people -> people.stream()
                .filter(person -> email.equalsIgnoreCase(person.getEmail())).findAny().orElse(null));
    }

    private Transaction toTransaction(final AttributeValue content) {
        try {
            return mapper.readValue(content.s(), Transaction.class);
        } catch (IOException ex) {
            log.error("Unable to parse record: " + content, ex);
            return null;
        }
    }

    private void txByUserId(final QueryRequest.Builder qb, final String userId) {
        qb.tableName(TRANSACTION_TABLE)
                .keyConditionExpression("userId = :userIdVal")
                .expressionAttributeValues(
                        Collections.singletonMap(":userIdVal", AttributeValue.builder().s(userId).build()));
    }

    private Person toPerson(final AttributeValue content) {
        if (content == null) {
            return null;
        }
        try {
            return mapper.readValue(content.s(), Person.class);
        } catch (final IOException ex) {
            log.error("Unable to parse person record: " + content, ex);
            return null;
        }
    }

    private Trip toTrip(final AttributeValue content) {
        if (content == null) {
            return null;
        }
        try {
            return mapper.readValue(content.s(), Trip.class);
        } catch (final IOException ex) {
            log.error("Unable to parse trip record: " + content, ex);
            return null;
        }
    }

    private TripEvent2 toTripEvent(final GetItemResponse resp, final String teId) {
        if (!resp.hasItem()) {
            log.warn("TripEvent (" + teId + ") not found!");
            return null;
        }
        final AttributeValue content = resp.item().get(CONTENT);
        if (content == null) {
            log.error("TripEvent (" + teId + ") is missing content!!");
            return null;
        }
        try {
            return mapper.readValue(content.s(), TripEvent2.class);
        } catch (final IOException ex) {
            log.error("Unable to parse TripEvent record: " + content, ex);
            return null;
        }
    }

    private <T, R> R clearCache(Map<String, T> cacheMap, R returnValue) {
        // To avoid the chance of a slow scan returning and caching the result AFTER we clear, but before we may
        // have added/deleted content, introduce a cache clear delay.
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
            }
            cacheMap.clear();
        });
        return returnValue;
    }

    /* Package-private for testing */
    <T> List<T> cacheAll(final Map<String, T> cacheMap, final List<T> items, final Function<T, String> getKey) {
        cacheMap.clear();
        items.forEach(item -> cacheMap.put(getKey.apply(item), item));
        return items;
    }

    /**
     * This method caches a single value, typically used when a single value is updated. In fact, it does not support
     * the use case of saving a single value to the cache if the cache isn't fully populated. In other words, it will
     * do nothing if the cache is completely empty. This allows checking if the cache is empty to know if it is
     * completely populated.
     *
     * @param cacheMap      The Map used to cache values.
     * @param item          The value to cache.
     * @param key           The key to cache it under.
     * @param returnValue   The return value (only to help functional style, pass through).
     * @param <T>           The type of the thing being cached.
     * @param <R>           The return value type.
     * @return  It always returns the {@code returnValue} passed in.
     */
    private <T, R> R cacheOne(final Map<String, T> cacheMap, final T item, String key, final R returnValue) {
        cacheMap.put(key, item);
        return returnValue;
    }

    private AttributeValue toStrAttr(final String val) {
        return AttributeValue.builder().s(val).build();
    }

    private interface TripPersistence {
        default CompletableFuture<PutItemResponse> putItem(Consumer<PutItemRequest.Builder> putItemRequest) {
            final PutItemResponse.Builder builder = PutItemResponse.builder();
            builder.sdkHttpResponse(SdkHttpResponse.builder().statusCode(200).build());
            return CompletableFuture.completedFuture(builder.build());
        }

        default CompletableFuture<ScanResponse> scan(Consumer<ScanRequest.Builder> scanRequest) {
            return CompletableFuture.completedFuture(ScanResponse.builder().items(Collections.emptyList()).build());
        }

        default CompletableFuture<QueryResponse> query(Consumer<QueryRequest.Builder> queryRequest) {
            return CompletableFuture.completedFuture(QueryResponse.builder().items(Collections.emptyList()).build());
        }

        default CompletableFuture<GetItemResponse> getItem(Consumer<GetItemRequest.Builder> getItemRequest) {
            final Map<String, AttributeValue> attrs = new HashMap<>();
            final GetItemRequest.Builder builder = GetItemRequest.builder();
            getItemRequest.accept(builder); // Populate it from their consumer
            final GetItemRequest giReq = builder.build();
            if (PASS_TABLE.equals(giReq.tableName())) {
                final AttributeValue email = giReq.key().get(EMAIL);
                final AttributeValue priv;
                if (email.s().toLowerCase().startsWith("admin")) {
                    priv = AttributeValue.builder().s("admin").build();
                } else if (email.s().toLowerCase().startsWith("user")) {
                    priv = AttributeValue.builder().s("user").build();
                } else {
                    // Not authorized
                    return CompletableFuture.completedFuture(GetItemResponse.builder().item(null).build());
                }
                attrs.put(EMAIL, email);
                final AttributeValue userId = DynamoUtils.getInstance().getPeople().join().stream()
                        .filter(p -> email.s().equalsIgnoreCase(p.getEmail())).findAny()
                        .map(Person::getId).map(id -> AttributeValue.builder().s(id).build())
                        .orElse(email);
                attrs.put(USER_ID, userId);
                attrs.put(PRIV, priv);
                attrs.put(PW, priv);
            }
            return CompletableFuture.completedFuture(GetItemResponse.builder().item(attrs).build());
        }
    }

    private static class DynamoTripPersistence implements TripPersistence {
        private final DynamoDbAsyncClient client;

        DynamoTripPersistence() {
            this.client = DynamoDbAsyncClient.builder()
                .region(Region.US_WEST_2)
                    .credentialsProvider(ProfileCredentialsProvider.builder().build())
                .build();
        }

        public CompletableFuture<ScanResponse> scan(Consumer<ScanRequest.Builder> scanRequest) {
            return client.scan(scanRequest);
        }
        public CompletableFuture<PutItemResponse> putItem(Consumer<PutItemRequest.Builder> putItemRequest) {
            return client.putItem(putItemRequest);
        }
        public CompletableFuture<QueryResponse> query(Consumer<QueryRequest.Builder> queryRequest) {
            return client.query(queryRequest);
        }
        public CompletableFuture<GetItemResponse> getItem(Consumer<GetItemRequest.Builder> getItemRequest) {
            return client.getItem(getItemRequest);
        }
    }
}
