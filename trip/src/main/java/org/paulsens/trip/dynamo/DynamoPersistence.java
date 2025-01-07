package org.paulsens.trip.dynamo;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
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
                .region(Region.US_EAST_1)
                //.credentialsProvider(ProfileCredentialsProvider.builder().build())
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

    public CompletableFuture<DeleteItemResponse> deleteItem(Consumer<DeleteItemRequest.Builder> delItemRequest) {
        return client.deleteItem(delItemRequest);
    }
}
