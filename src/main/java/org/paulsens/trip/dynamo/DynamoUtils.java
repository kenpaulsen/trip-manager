package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.model.Person;

import java.io.IOException;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;


@Slf4j
public class DynamoUtils {
    private final ObjectMapper mapper;
    private final DynamoDbAsyncClient client;

    private static final DynamoUtils instance = new DynamoUtils();
    private static final String PERSON_TABLE = "people";
    private static final String ID = "id";
    private static final String CONTENT = "content";

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
        return client.getItem(b -> b.key(key).build())
                .thenApply(it -> toPerson(it.item().get(CONTENT)));
    }

    private Person toPerson(final AttributeValue content) {
        try {
            return mapper.readValue(content.s(), Person.class);
        } catch (IOException ex) {
            log.error("Unable to parse record: " + content, ex);
            return null;
        }
    }

//    private final Table personTable;
//        final DynamoDB dynamo = new DynamoDB(AmazonDynamoDBClientBuilder.standard().withRegion("us-west-2").build());
//        this.personTable = dynamo.getTable(PERSON_TABLE);
/*
    public void savePerson(final Person person) throws IOException {
        final Item item = new Item()
                .withPrimaryKey(ID, person.getId())
                .withJSON(CONTENT, mapper.writeValueAsString(person));
        personTable.putItem(item);
    }

    public Person getPerson(final String id) throws IOException {
        return mapper.readValue(personTable.getItem(ID, id).getJSON(CONTENT), Person.class);
    }
*/
}
