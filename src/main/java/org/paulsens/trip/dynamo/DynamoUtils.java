package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
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

    public CompletableFuture<PutItemResponse> savePerson(final Person person) throws IOException {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(ID, AttributeValue.builder().s(person.getId()).build());
        map.put(CONTENT, AttributeValue.builder().s(mapper.writeValueAsString(person)).build());
        return client.putItem(b -> b.tableName(PERSON_TABLE).item(map));
    }

    public CompletableFuture<List<Person>> getPeople() {
        return client.scan(b -> b.consistentRead(false).limit(100).tableName(PERSON_TABLE).build())
                .thenApply(resp -> resp.items().stream()
                        .map(it -> toPerson(it.get(CONTENT))).collect(Collectors.toList()));
    }

    public CompletableFuture<Person> getPerson(final String id) {
        final Map<String, AttributeValue> key = Collections.singletonMap(ID, AttributeValue.builder().s(id).build());
        return client.getItem(b -> b.key(key).tableName(PERSON_TABLE).build())
                .thenApply(it -> toPerson(it.item().get(CONTENT)));
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
        return client.query(qb -> txByUserId(qb, userId)).thenApply(
                resp -> resp.items().stream().map(m -> toTransaction(m.get(CONTENT))).collect(Collectors.toList()));
    }

    public CompletableFuture<Optional<Transaction>> getTransaction(final String userId, final OffsetDateTime date) {
        return client.query(qb -> txByUserIdAndDate(qb, userId, date)).thenApply(
                resp -> resp.items().stream().map(m -> toTransaction(m.get(CONTENT))).findAny());
    }

    public CompletableFuture<PutItemResponse> saveTransaction(final Transaction tx) throws IOException {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(USER_ID, AttributeValue.builder().s(tx.getUserId()).build());
        map.put(TX_DATE, AttributeValue.builder().n("" + tx.getTxDate().toInstant().toEpochMilli()).build());
        map.put(CONTENT, AttributeValue.builder().s(mapper.writeValueAsString(tx)).build());
        return client.putItem(b -> b.tableName(TRANSACTION_TABLE).item(map));
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
        final String emailVal = "\"email\":\"" + email + "\"";
        return client.scan(b -> b.tableName(PERSON_TABLE).consistentRead(false).limit(100)
                .expressionAttributeValues(
                        Collections.singletonMap(":emailVal", AttributeValue.builder().s(emailVal).build()))
                .filterExpression("contains(content, :emailVal)").build())
                .thenApply(this::getFirstScanResult)
                .thenApply(optm -> optm.map(m -> toPerson(m.get(CONTENT))).orElse(null));
    }

    private Optional<Map<String, AttributeValue>> getFirstScanResult(final ScanResponse resp) {
        final List<Map<String, AttributeValue>> items = resp.items();
        return Optional.ofNullable(items.isEmpty() ? null : items.get(0));
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
}
