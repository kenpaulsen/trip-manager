package org.paulsens.trip.dynamo;

import com.amazonaws.services.dynamodbv2.local.main.ServerRunner;
import com.amazonaws.services.dynamodbv2.local.server.DynamoDBProxyServer;
import java.net.ServerSocket;
import java.net.URI;
import java.util.List;
import java.util.function.Consumer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * One in-process DynamoDB engine for the whole test JVM, with the real table schema.
 *
 * <p>This exists because the unit suite had no persistence for most tables. {@link InMemoryPersistence} models
 * only {@code audit} and the four {@code chat_*} tables; every other table fell through to
 * {@link Persistence}'s interface defaults, where {@code putItem} returns success and stores NOTHING and
 * {@code getItem} answers empty. For those tables the per-DAO cache was the store: a write went nowhere, the
 * read-back was served from cache, and clearing the cache lost the row. So the marshalling, the key-condition
 * expressions and the pagination all ran -- and counted as covered -- while nothing ever verified their
 * results.
 *
 * <p>That is exactly the gap the {@code day} reserved-keyword bug came through: the query built fine, passed
 * against the fake, and failed only against real DynamoDB.
 *
 * <p><b>Lifecycle.</b> One server per JVM, started on first use and stopped by a shutdown hook. Starting it
 * costs ~4s, so per-class would be minutes across the suite. Tests therefore SHARE the tables and must isolate
 * themselves by key -- use {@link #uniqueId} rather than deleting rows, the same discipline the fake-store
 * tests already need.
 */
public final class DynamoLocal {

    private static final Object LOCK = new Object();
    private static DynamoDBProxyServer server;
    private static URI endpoint;
    private static Persistence persistence;

    private DynamoLocal() {
    }

    /** A {@link Persistence} backed by the real engine. Starts it on first call. */
    public static Persistence persistence() {
        ensureStarted();
        return persistence;
    }

    /** The endpoint, for a test that wants its own client. */
    public static URI endpoint() {
        ensureStarted();
        return endpoint;
    }

    /**
     * A value unique to this call, for keying rows.
     *
     * <p>Tables are shared across the suite, so tests isolate by key rather than by cleaning up: a shared
     * engine plus per-test deletes is how one test's teardown starts deleting another's rows.
     */
    public static String uniqueId(final String prefix) {
        return prefix + '-' + System.nanoTime();
    }

    private static void ensureStarted() {
        synchronized (LOCK) {
            if (server != null) {
                return;
            }
            try {
                final int port;
                try (ServerSocket socket = new ServerSocket(0)) {
                    port = socket.getLocalPort();
                }
                server = ServerRunner.createServerFromCommandLineArgs(
                        new String[] {"-inMemory", "-port", String.valueOf(port)});
                server.start();
                endpoint = URI.create("http://localhost:" + port);
                createTables();
                persistence = new DynamoPersistence(endpoint);
                Runtime.getRuntime().addShutdownHook(new Thread(DynamoLocal::stop, "dynamo-local-stop"));
            } catch (final Exception ex) {
                throw new IllegalStateException("Unable to start DynamoDB Local", ex);
            }
        }
    }

    private static void stop() {
        synchronized (LOCK) {
            if (server == null) {
                return;
            }
            try {
                server.stop();
            } catch (final Exception ex) {
                // Nothing useful to do while the JVM is exiting.
                server = null;
            }
            server = null;
        }
    }

    /**
     * The real schema, mirroring what CDK creates.
     *
     * <p>Kept here rather than derived from the DAOs on purpose: a schema derived from the code under test
     * would agree with it by construction, which is the property that makes the fake useless.
     */
    private static void createTables() {
        try (DynamoDbClient admin = DynamoDbClient.builder()
                .region(Region.US_WEST_2)
                .endpointOverride(endpoint)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("local", "local")))
                .build()) {

            // Partition key only.
            create(admin, "people", "id", null, builder -> builder
                    .attributeDefinitions(stringAttr("id"), stringAttr("email"))
                    .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                            .indexName("email-index")
                            .keySchema(KeySchemaElement.builder()
                                    .attributeName("email").keyType(KeyType.HASH).build())
                            .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                            .build()));
            create(admin, "trips", "id", null, null);
            create(admin, "trip_events", "id", null, null);
            create(admin, "privs", "name", null, null);
            create(admin, "config", "name", null, null);
            create(admin, "media", "id", null, null);
            create(admin, "pass", "email", null, null);
            create(admin, "chat_channels", "channelId", null, null);

            // Partition + sort key.
            create(admin, "registrations", "tripId", "userId", null);
            create(admin, "todo_items", "tripId", "dataId", null);
            create(admin, "person_data", "userId", "dataId", null);
            create(admin, "transactions", "userId", "txId", null);
            create(admin, "bindings", "id1", "id2", null);
            create(admin, "chat_members", "channelId", "personId", null);
            create(admin, "chat_messages", "channelId", "msgId", null);
            create(admin, "chat_reactions", "channelId", "sk", null);
            // "ts" is epoch millis but AuditDAO writes it with toStrAttr, so the key type is STRING. Getting
            // this wrong is not cosmetic: DynamoDB rejects the write outright on a type mismatch.
            create(admin, "audit", "day", "ts", null);
        }
    }

    private static void create(final DynamoDbClient admin, final String table, final String pk, final String sk,
            final Consumer<CreateTableRequest.Builder> extra) {
        createWithTypes(admin, table, pk, ScalarAttributeType.S, sk, ScalarAttributeType.S, extra);
    }

    private static void createWithTypes(final DynamoDbClient admin, final String table, final String pk,
            final ScalarAttributeType pkType, final String sk, final ScalarAttributeType skType) {
        createWithTypes(admin, table, pk, pkType, sk, skType, null);
    }

    private static void createWithTypes(final DynamoDbClient admin, final String table, final String pk,
            final ScalarAttributeType pkType, final String sk, final ScalarAttributeType skType,
            final Consumer<CreateTableRequest.Builder> extra) {
        final CreateTableRequest.Builder builder = CreateTableRequest.builder()
                .tableName(table)
                .billingMode(BillingMode.PAY_PER_REQUEST);
        if (sk == null) {
            builder.attributeDefinitions(attr(pk, pkType))
                    .keySchema(KeySchemaElement.builder().attributeName(pk).keyType(KeyType.HASH).build());
        } else {
            builder.attributeDefinitions(attr(pk, pkType), attr(sk, skType))
                    .keySchema(
                            KeySchemaElement.builder().attributeName(pk).keyType(KeyType.HASH).build(),
                            KeySchemaElement.builder().attributeName(sk).keyType(KeyType.RANGE).build());
        }
        if (extra != null) {
            // The extra consumer may replace attributeDefinitions to add GSI key attributes.
            extra.accept(builder);
        }
        try {
            admin.createTable(builder.build());
        } catch (final RuntimeException ex) {
            if (!(ex instanceof ResourceNotFoundException) && !ex.toString().contains("preexist")
                    && !ex.toString().contains("Table already exists")) {
                throw ex;
            }
        }
    }

    private static AttributeDefinition stringAttr(final String name) {
        return attr(name, ScalarAttributeType.S);
    }

    private static AttributeDefinition attr(final String name, final ScalarAttributeType type) {
        return AttributeDefinition.builder().attributeName(name).attributeType(type).build();
    }

    /** The tables this harness creates, for a test that wants to assert the list is complete. */
    public static List<String> tableNames() {
        return List.of("people", "trips", "trip_events", "privs", "config", "media", "pass", "chat_channels",
                "registrations", "todo_items", "person_data", "transactions", "bindings", "chat_members",
                "chat_messages", "chat_reactions", "audit");
    }
}
