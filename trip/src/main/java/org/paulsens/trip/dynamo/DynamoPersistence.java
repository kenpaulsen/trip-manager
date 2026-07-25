package org.paulsens.trip.dynamo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
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
        this.client = DynamoDbAsyncClient.builder()
                .region(resolveRegion())
                // Default chain: finds the ECS task role / instance role in AWS, and ~/.aws [default] on a laptop.
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .build();
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
