package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.model.Person;

import java.io.IOException;
import org.paulsens.trip.model.Transaction;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

@Slf4j
public class DynamoUtils {
    private final ObjectMapper mapper;
    private final DynamoDbAsyncClient client;

    private static final DynamoUtils instance = new DynamoUtils();
    private static final String PERSON_TABLE = "people";
    private static final String TRANSACTION_TABLE = "transactions";
    private static final String ID = "id";
    private static final String USER_ID = "userId";
    private static final String CONTENT = "content";
    private static final String TX_DATE = "date";

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

    public CompletableFuture<List<Transaction>> getTransactions(final String userId) {
        return client.query(qb -> txByUserIdQuery(qb, userId)).thenApply(
                resp -> resp.items().stream().map(m -> toTransaction(m.get(CONTENT))).collect(Collectors.toList()));
    }

    public CompletableFuture<PutItemResponse> saveTransaction(final Transaction tx) throws IOException {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(USER_ID, AttributeValue.builder().s(tx.getUserId()).build());
        map.put(TX_DATE, AttributeValue.builder().s(
                tx.getTxDate().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).build());
        map.put(CONTENT, AttributeValue.builder().s(mapper.writeValueAsString(tx)).build());
        return client.putItem(b -> b.tableName(TRANSACTION_TABLE).item(map));
    }

    private Transaction toTransaction(final AttributeValue content) {
        try {
            return mapper.readValue(content.s(), Transaction.class);
        } catch (IOException ex) {
            log.error("Unable to parse record: " + content, ex);
            return null;
        }
    }

    private void txByUserIdQuery(final QueryRequest.Builder qb, final String userId) {
        qb.tableName(TRANSACTION_TABLE)
                .keyConditionExpression("userId = :userIdVal")
                .expressionAttributeValues(
                        Collections.singletonMap(":userIdVal", AttributeValue.builder().s(userId).build()));
    }

    private Person toPerson(final AttributeValue content) {
        try {
            return mapper.readValue(content.s(), Person.class);
        } catch (IOException ex) {
            log.error("Unable to parse record: " + content, ex);
            return null;
        }
    }
}
