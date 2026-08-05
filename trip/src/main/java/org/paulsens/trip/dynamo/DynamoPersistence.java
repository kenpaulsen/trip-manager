package org.paulsens.trip.dynamo;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

/**
 * The real (deployed) {@link Persistence}: a synchronous DynamoDB client called from virtual threads.
 *
 * <p>Deliberately the SYNC client since the virtual-threads migration: a blocking socket read parks the
 * calling virtual thread (the JDK schedules it off its carrier), so nothing is gained by NIO here -- and
 * with no {@code CompletableFuture} in the path there is no completion thread whose safety anyone has to
 * reason about. The old async client ran DAO continuations (including Jackson parses that joined nested
 * futures) on the SDK's small {@code sdk-async-response} pool, with caller-runs overflow onto the Netty
 * event loop.</p>
 */
class DynamoPersistence implements Persistence {
    /**
     * The connection pool is the DynamoDB admission bound -- the explicit replacement for the implicit cap
     * the old 100-platform-thread Tomcat pool provided. Acquisition beyond the pool queues briefly
     * (better slow...) and then fails fast (...but not a pile-up).
     */
    private static final int MAX_CONNECTIONS = 100;
    private static final Duration ACQUISITION_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration ATTEMPT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);

    private final DynamoDbClient client;

    DynamoPersistence() {
        this(resolveEndpoint());
    }

    /**
     * @param endpoint a DynamoDB endpoint to talk to instead of the real service, or null for AWS. Used to point
     *                 at DynamoDB Local -- by the test harness, and by anyone running the app against a local
     *                 engine rather than the account's real tables.
     */
    DynamoPersistence(final URI endpoint) {
        final DynamoDbClientBuilder builder = DynamoDbClient.builder()
                .region(resolveRegion())
                // Default chain: finds the ECS task role / instance role in AWS, and ~/.aws [default] on a laptop.
                .credentialsProvider(endpoint == null
                        ? DefaultCredentialsProvider.builder().build()
                        // A local engine accepts any credentials and there may be none configured; asking the
                        // default chain would fail before the first request.
                        : StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                .httpClientBuilder(ApacheHttpClient.builder()
                        .maxConnections(MAX_CONNECTIONS)
                        .connectionAcquisitionTimeout(ACQUISITION_TIMEOUT))
                // Explicit timeouts rather than inherited defaults; a hung call must release its thread.
                .overrideConfiguration(o -> o.apiCallAttemptTimeout(ATTEMPT_TIMEOUT).apiCallTimeout(CALL_TIMEOUT));
        if (endpoint != null) {
            builder.endpointOverride(endpoint);
        }
        this.client = builder.build();
    }

    /**
     * An explicit DynamoDB endpoint, or null to use the real service.
     *
     * <p>System property ONLY -- no environment fallback, on purpose, and the precedent is
     * {@code trip.cache.local.useConfigured}: a -D flag has to be typed into the launch command, while an
     * environment variable can arrive ambiently (an exported shell var, a copy-pasted task definition) and
     * silently repoint the production datastore at an endpoint that does not exist. The failure direction has
     * to be "a laptop talks to AWS", never "the deployment talks to nothing". Tests do not use this at all --
     * they pass the endpoint straight to the package-private constructor.
     */
    static URI resolveEndpoint() {
        final String endpoint = System.getProperty("trip.dynamo.endpoint");
        return (endpoint == null || endpoint.isBlank()) ? null : URI.create(endpoint.trim());
    }

    // Region defaults to us-west-2 (the active deployment) but can be overridden so a one-off tool -- e.g. the
    // password-pepper rotation/migration script -- can target another deployment's account/region without a rebuild.
    static Region resolveRegion() {
        String region = System.getProperty("trip.dynamo.region");
        if (region == null || region.isBlank()) {
            region = System.getenv("TRIP_DYNAMO_REGION");
        }
        return (region == null || region.isBlank()) ? Region.US_WEST_2 : Region.of(region.trim());
    }

    @Override
    public ScanResponse scan(final Consumer<ScanRequest.Builder> scanRequest) {
        return client.scan(scanRequest);
    }

    @Override
    public PutItemResponse putItem(final Consumer<PutItemRequest.Builder> putItemRequest) {
        return client.putItem(putItemRequest);
    }

    @Override
    public QueryResponse query(final Consumer<QueryRequest.Builder> queryRequest) {
        return client.query(queryRequest);
    }

    @Override
    public GetItemResponse getItem(final Consumer<GetItemRequest.Builder> getItemRequest) {
        return client.getItem(getItemRequest);
    }

    @Override
    public DeleteItemResponse deleteItem(final Consumer<DeleteItemRequest.Builder> delItemRequest) {
        return client.deleteItem(delItemRequest);
    }

    @Override
    public List<Map<String, AttributeValue>> scanAll(final Consumer<ScanRequest.Builder> scanRequest) {
        final List<Map<String, AttributeValue>> items = new ArrayList<>();
        for (final ScanResponse page : client.scanPaginator(scanRequest)) {
            items.addAll(page.items());
        }
        return items;
    }

    @Override
    public List<Map<String, AttributeValue>> queryAll(final Consumer<QueryRequest.Builder> queryRequest) {
        final List<Map<String, AttributeValue>> items = new ArrayList<>();
        for (final QueryResponse page : client.queryPaginator(queryRequest)) {
            items.addAll(page.items());
        }
        return items;
    }
}
