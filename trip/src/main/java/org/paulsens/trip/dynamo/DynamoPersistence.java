package org.paulsens.trip.dynamo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClientBuilder;
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

class DynamoPersistence implements Persistence {
    private final DynamoDbAsyncClient client;

    DynamoPersistence() {
        this(resolveEndpoint());
    }

    /**
     * @param endpoint a DynamoDB endpoint to talk to instead of the real service, or null for AWS. Used to point
     *                 at DynamoDB Local -- by the test harness, and by anyone running the app against a local
     *                 engine rather than the account's real tables.
     */
    DynamoPersistence(final URI endpoint) {
        final DynamoDbAsyncClientBuilder builder = DynamoDbAsyncClient.builder()
                .region(resolveRegion())
                // Default chain: finds the ECS task role / instance role in AWS, and ~/.aws [default] on a laptop.
                .credentialsProvider(endpoint == null
                        ? DefaultCredentialsProvider.builder().build()
                        // A local engine accepts any credentials and there may be none configured; asking the
                        // default chain would fail before the first request.
                        : StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")));
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

    public CompletableFuture<DeleteItemResponse> deleteItem(Consumer<DeleteItemRequest.Builder> delItemRequest) {
        return client.deleteItem(delItemRequest);
    }

    @Override
    public CompletableFuture<List<Map<String, AttributeValue>>> scanAll(
            final Consumer<ScanRequest.Builder> scanRequest) {
        final List<Map<String, AttributeValue>> items = Collections.synchronizedList(new ArrayList<>());
        return client.scanPaginator(scanRequest)
                .subscribe(page -> items.addAll(page.items()))
                .thenApply(ignored -> items);
    }

    @Override
    public CompletableFuture<List<Map<String, AttributeValue>>> queryAll(
            final Consumer<QueryRequest.Builder> queryRequest) {
        final List<Map<String, AttributeValue>> items = Collections.synchronizedList(new ArrayList<>());
        return client.queryPaginator(queryRequest)
                .subscribe(page -> items.addAll(page.items()))
                .thenApply(ignored -> items);
    }
}
