package org.paulsens.trip.dynamo;

import com.amazonaws.services.dynamodbv2.local.main.ServerRunner;
import com.amazonaws.services.dynamodbv2.local.server.DynamoDBProxyServer;
import java.net.ServerSocket;
import java.net.URI;
import java.util.Map;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * That DynamoDB Local itself works, kept separate from the DAO tests that rely on it.
 *
 * <p>Its job is to fail with an obvious cause when the harness breaks rather than the schema. DynamoDBLocal
 * drags in an old log4j-core that the pom pins forward; if that pin is ever lost, this fails in
 * {@code @BeforeClass} with a NoSuchMethodError and says so here instead of taking every DAO test with it.
 *
 * <p>It also pins the reason for using a real engine at all: {@code day} is a DynamoDB reserved keyword.
 * {@code InMemoryPersistence} does not care, so a query missing its {@code ExpressionAttributeNames} passes
 * against a fake and fails in production. Here it fails in the test.
 */
public class DynamoLocalSmokeTest {

    private DynamoDBProxyServer server;
    private DynamoDbAsyncClient client;

    @BeforeClass
    public void start() throws Exception {
        final int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        server = ServerRunner.createServerFromCommandLineArgs(
                new String[] {"-inMemory", "-port", String.valueOf(port)});
        server.start();
        client = DynamoDbAsyncClient.builder()
                .region(Region.US_WEST_2)
                .endpointOverride(URI.create("http://localhost:" + port))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("fake", "fake")))
                .build();
        client.createTable(b -> b.tableName("audit")
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("day").attributeType(ScalarAttributeType.S)
                                .build(),
                        AttributeDefinition.builder().attributeName("millis").attributeType(ScalarAttributeType.N)
                                .build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("day").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("millis").keyType(KeyType.RANGE).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)).join();
    }

    @AfterClass(alwaysRun = true)
    public void stop() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    public void putAndGetRoundTrip() {
        client.putItem(b -> b.tableName("audit").item(Map.of(
                "day", AttributeValue.fromS("2026-08-03"),
                "millis", AttributeValue.fromN("1"),
                "message", AttributeValue.fromS("hello")))).join();

        final Map<String, AttributeValue> got = client.getItem(b -> b.tableName("audit").key(Map.of(
                "day", AttributeValue.fromS("2026-08-03"),
                "millis", AttributeValue.fromN("1")))).join().item();

        Assert.assertEquals(got.get("message").s(), "hello");
    }

    /**
     * The point of a real engine: "day" is a DynamoDB reserved keyword. InMemoryPersistence does not care, so a
     * query that forgets ExpressionAttributeNames passes in the current suite and fails in production.
     */
    @Test
    public void unaliasedReservedKeywordIsRejected() {
        Assert.assertThrows(Exception.class, () -> client.query(b -> b.tableName("audit")
                .keyConditionExpression("day = :d")
                .expressionAttributeValues(Map.of(":d", AttributeValue.fromS("2026-08-03")))).join());
    }

    @Test
    public void aliasedReservedKeywordSucceeds() {
        // Self-contained: TestNG gives no ordering guarantee, so this cannot rely on another test's row.
        client.putItem(b -> b.tableName("audit").item(Map.of(
                "day", AttributeValue.fromS("2026-08-03"),
                "millis", AttributeValue.fromN("1"),
                "message", AttributeValue.fromS("hello")))).join();

        Assert.assertEquals(client.query(b -> b.tableName("audit")
                .keyConditionExpression("#d = :d")
                .expressionAttributeNames(Map.of("#d", "day"))
                .expressionAttributeValues(Map.of(":d", AttributeValue.fromS("2026-08-03"))))
                .join().count().intValue(), 1);
    }
}
