package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.Person;

import java.io.IOException;
import org.paulsens.trip.model.Transaction;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;

@Slf4j
public class DynamoUtils {
    private final ObjectMapper mapper;
    private final DynamoDbAsyncClient client;
    private Map<String, Person> peopleCache = new ConcurrentHashMap<>();
    private Map<String, Map<String, Transaction>> txCache = new ConcurrentHashMap<>();

    private static final DynamoUtils instance = new DynamoUtils();
    private static final String PERSON_TABLE = "people";
    private static final String TRANSACTION_TABLE = "transactions";
    private static final String PASS_TABLE = "pass";
    private static final String ID = "id";
    private static final String USER_ID = "userId";
    private static final String CONTENT = "content";
    private static final String TX_DATE = "txDate";
    private static final String EMAIL = "email";
    private static final String PRIV = "priv";
    private static final String PW = "pass";
    private static final String USER_PRIV = "user";

    private DynamoUtils() {
        final ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.client = DynamoDbAsyncClient.builder()
                .region(Region.US_WEST_2)
                .credentialsProvider(ProfileCredentialsProvider.builder().build())
                .build();
        this.mapper = mapper;
    }

    public static DynamoUtils getInstance() {
        return instance;
    }

    public CompletableFuture<Boolean> savePerson(final Person person) throws IOException {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(ID, AttributeValue.builder().s(person.getId()).build());
        map.put(CONTENT, AttributeValue.builder().s(mapper.writeValueAsString(person)).build());
        return client.putItem(b -> b.tableName(PERSON_TABLE).item(map))
            .thenApply(resp -> resp.sdkHttpResponse().isSuccessful())
            .thenApply(r -> r ? cacheOne(peopleCache, person, Person::getId, true) : clearCache(peopleCache, false));
    }

    public CompletableFuture<List<Person>> getPeople() {
        if (!peopleCache.isEmpty()) {
            return CompletableFuture.completedFuture(new ArrayList<>(peopleCache.values()));
        }
        return client.scan(b -> b.consistentRead(false).limit(1000).tableName(PERSON_TABLE).build())
                .thenApply(resp -> resp.items().stream()
                        .map(it -> toPerson(it.get(CONTENT))).collect(Collectors.toList()))
                .thenApply(list -> cacheAll(peopleCache, list, Person::getId));
    }

    public CompletableFuture<Optional<Person>> getPerson(final String id) {
        final Person person = peopleCache.get(id);
        if (person != null) {
            return CompletableFuture.completedFuture(Optional.of(person));
        }
        return getPeople().thenApply(people -> Optional.ofNullable(peopleCache.get(id))); // Load all the people
    }

    public CompletableFuture<Creds> getCredsByEmailAndPass(final String email, final String pass) {
        if ((email == null) || email.isEmpty() || (pass == null) || pass.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        final Map<String, AttributeValue> key =
                Collections.singletonMap(EMAIL, AttributeValue.builder().s(email).build());
        return client.getItem(b -> b.key(key).tableName(PASS_TABLE).build())
                .thenApply(item -> toCreds(item, email, pass));
    }

    public CompletableFuture<List<Transaction>> getTransactions(final String userId) {
        final Map<String, Transaction> userTxCache = getTxCacheForUser(userId);
        if (!userTxCache.isEmpty()) {
            return CompletableFuture.completedFuture(new ArrayList<>(userTxCache.values()));
        }
        return client.query(qb -> txByUserId(qb, userId))
                .thenApply(resp -> resp.items().stream()
                        .map(m -> toTransaction(m.get(CONTENT))).collect(Collectors.toList()))
                .thenApply(list -> cacheAll(userTxCache, list, this::getTxCacheId));
    }

    public CompletableFuture<Optional<Transaction>> getTransaction(final String userId, final OffsetDateTime date) {
        return getTransactions(userId) // Ensure user transactions are already loaded into memory
                .thenApply(na -> getTxCacheForUser(userId).get(getTxCacheId(userId, date)))  // Read from cache
                .thenApply(Optional::ofNullable);
    }

    public CompletableFuture<Boolean> saveTransaction(final Transaction tx) throws IOException {
        final Map<String, Transaction> userTxs = getTxCacheForUser(tx.getUserId());
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(USER_ID, AttributeValue.builder().s(tx.getUserId()).build());
        map.put(TX_DATE, AttributeValue.builder().n("" + tx.getTxDate().toInstant().toEpochMilli()).build());
        map.put(CONTENT, AttributeValue.builder().s(mapper.writeValueAsString(tx)).build());
        return client.putItem(b -> b.tableName(TRANSACTION_TABLE).item(map))
                .thenApply(resp -> resp.sdkHttpResponse().isSuccessful())
                .thenApply(r -> r ? cacheOne(userTxs, tx, this::getTxCacheId, true) : clearCache(userTxs, false));
    }

    private String getTxCacheId(final String userId, final OffsetDateTime dateTime) {
        return userId + "_" + dateTime.toInstant().toEpochMilli();
    }

    private String getTxCacheId(final Transaction tx) {
        return tx.getUserId() + "_" + tx.getTxDate().toInstant().toEpochMilli();
    }

    private Map<String, Transaction> getTxCacheForUser(final String userId) {
        return txCache.computeIfAbsent(userId, k -> new HashMap<>());
    }

    private Creds toCreds(final GetItemResponse resp, final String email, final String pass) {
        if (!resp.hasItem()) {
            log.warn("No Creds for user with email (" + email + ")! Checking if user exists...");
            return createCreds(email).map(creds -> validateCreds(email, pass, creds)).orElse(null);
        }
        final Map<String, AttributeValue> at = resp.item();
        final Creds creds = new Creds(at.get(EMAIL).s(), at.get(USER_ID).s(), at.get(PRIV).s(), at.get(PW).s());
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
        final Creds creds = new Creds(email, user.getId(), USER_PRIV, user.getLast());
        return Optional.ofNullable(saveCreds(creds).join() ? creds : null);
    }

    private CompletableFuture<Boolean> saveCreds(Creds creds) {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(EMAIL, AttributeValue.builder().s(creds.getEmail()).build());
        map.put(USER_ID, AttributeValue.builder().s(creds.getUserId()).build());
        map.put(PW, AttributeValue.builder().s(creds.getPass()).build());
        map.put(PRIV, AttributeValue.builder().s(creds.getPriv()).build());
        return client.putItem(b -> b.tableName(PASS_TABLE).item(map))
                .thenApply(resp -> resp.sdkHttpResponse().isSuccessful())
                .exceptionally(ex -> {
                    log.error("Failed to save creds!", ex);
                    return false;
                });
    }

    private CompletableFuture<Person> getPersonByEmail(final String email) {
        return getPeople().thenApply(people -> people.stream()
                .filter(person -> email.equals(person.getEmail())).findAny().orElse(null));
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

    private void txByUserIdAndDate(final QueryRequest.Builder qb, final String userId, final OffsetDateTime date) {
        final String dateStr = "" + date.toInstant().toEpochMilli();
        final Map<String, AttributeValue> attVals = new HashMap<>();
        attVals.put(":userIdVal", AttributeValue.builder().s(userId).build());
        attVals.put(":dateVal", AttributeValue.builder().n(dateStr).build());
        qb.tableName(TRANSACTION_TABLE)
                .keyConditionExpression("userId = :userIdVal and txDate = :dateVal")
                .expressionAttributeValues(attVals);
    }

    private Person toPerson(final AttributeValue content) {
        if (content == null) {
            return null;
        }
        try {
            return mapper.readValue(content.s(), Person.class);
        } catch (IOException ex) {
            log.error("Unable to parse record: " + content, ex);
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

    private <T> List<T> cacheAll(final Map<String, T> cacheMap, final List<T> items, final Function<T, String> getKey) {
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
     * @param keySupplier   The key to cache it under.
     * @param returnValue   The return value (only to help functional style, pass through).
     * @param <T>           The type of the thing being cached.
     * @param <R>           The return value type.
     * @return  It always returns the {@code returnValue} passed in.
     */
    private <T, R> R cacheOne(
            final Map<String, T> cacheMap, final T item, final Function<T, String> keySupplier, final R returnValue) {
        cacheMap.put(keySupplier.apply(item), item);
        return returnValue;
    }
}
